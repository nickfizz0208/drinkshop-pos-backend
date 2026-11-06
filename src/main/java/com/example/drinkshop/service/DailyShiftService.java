package com.example.drinkshop.service;

import com.example.drinkshop.dto.DailyWastageSummaryResponse;
import com.example.drinkshop.dto.ShiftClosingSummaryResponse;
import com.example.drinkshop.entity.DailyShift;
import com.example.drinkshop.entity.OrderItem;
import com.example.drinkshop.exception.ResourceNotFoundException;
import com.example.drinkshop.repository.DailyShiftRepository;
import com.example.drinkshop.repository.OrderItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 每日班次與日結服務（處理開班、關班結算、現金對帳與短溢計算）
 */
@Service
@Transactional
public class DailyShiftService {

    private final DailyShiftRepository dailyShiftRepository;
    private final OrderItemRepository orderItemRepository;
    private final WastageService wastageService;

    public DailyShiftService(DailyShiftRepository dailyShiftRepository,
                             OrderItemRepository orderItemRepository,
                             WastageService wastageService) {
        this.dailyShiftRepository = dailyShiftRepository;
        this.orderItemRepository = orderItemRepository;
        this.wastageService = wastageService;
    }

    /**
     * 開班 / 登錄班次
     * 記錄開班員工、開班時間、起始零用金與排班人員崗位
     */
    public DailyShift openShift(LocalDate businessDate, Long staffId, BigDecimal pettyCash, String rolesJson) {
        if (dailyShiftRepository.existsById(businessDate)) {
            throw new IllegalStateException("營業日 [" + businessDate + "] 已經存在班次記錄，不可重複開班！");
        }

        DailyShift shift = new DailyShift();
        shift.setBusinessDate(businessDate);
        shift.setStatus("OPEN");
        shift.setOpenedBy(staffId);
        shift.setOpenedAt(LocalDateTime.now());
        shift.setPettyCash(pettyCash != null ? pettyCash : BigDecimal.ZERO);
        shift.setRolesJson(rolesJson != null ? rolesJson : "[]");

        return dailyShiftRepository.save(shift);
    }

    /**
     * 關班 / 日結清算 (Daily Closing)
     * 統計整日銷售營收、報廢損耗成本，推算理論應有現金並核對實點現金計算短溢差額
     */
    public ShiftClosingSummaryResponse closeShift(LocalDate businessDate, Long closedBy, BigDecimal countedCash, String diffReason) {
        DailyShift shift = dailyShiftRepository.findById(businessDate)
                .orElseThrow(() -> new ResourceNotFoundException("找不到營業日 [" + businessDate + "] 的班次記錄"));

        if ("CLOSED".equalsIgnoreCase(shift.getStatus())) {
            throw new IllegalStateException("該營業日班次已經處於已關班狀態 (CLOSED)！");
        }

        LocalDateTime startOfDay = businessDate.atStartOfDay();
        LocalDateTime endOfDay = businessDate.atTime(LocalTime.MAX);

        // 1. 統計當日飲品銷售（排除已取消與已作廢報廢品項）
        List<OrderItem> items = orderItemRepository.findByCreatedAtBetween(startOfDay, endOfDay);
        BigDecimal totalRevenue = BigDecimal.ZERO;
        int totalCups = 0;

        for (OrderItem item : items) {
            if (!"CANCELLED".equalsIgnoreCase(item.getStatus()) && !"WASTED".equalsIgnoreCase(item.getStatus())) {
                int itemQty = item.getQty() != null ? item.getQty() : 1;
                BigDecimal itemPrice = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
                totalRevenue = totalRevenue.add(itemPrice.multiply(BigDecimal.valueOf(itemQty)));
                totalCups += itemQty;
            }
        }

        // 2. 統計當日報廢損耗總額
        DailyWastageSummaryResponse wastageSummary = wastageService.getDailyWastageSummary(businessDate);
        BigDecimal totalWastageCost = wastageSummary.totalWastageCost();

        // 3. 計算系統理論應有現金：開班零用金 + 飲品營收 - 報廢成本
        BigDecimal startingCash = shift.getPettyCash() != null ? shift.getPettyCash() : BigDecimal.ZERO;
        BigDecimal expectedCash = startingCash.add(totalRevenue).subtract(totalWastageCost);

        // 4. 計算現金短溢差額 (實際清點現金 - 理論應有現金)
        BigDecimal actualCounted = countedCash != null ? countedCash : BigDecimal.ZERO;
        BigDecimal diffAmount = actualCounted.subtract(expectedCash);
        // 資料庫規定（daily_shift 的 CHECK）：現金短少時一定要填差異原因。先檢查，避免寫入時才失敗變成 500
        if (diffAmount.signum() < 0 && (diffReason == null || diffReason.isBlank())) {
            throw com.example.drinkshop.exception.ApiException.bad(
                "現金短少 $" + diffAmount.negate().stripTrailingZeros().toPlainString()
                + "（應有 $" + expectedCash.stripTrailingZeros().toPlainString() + "），請填寫差異原因");
        }

        // 5. 更新 DailyShift 資料庫記錄狀態並儲存（依 DB 規範，CLOSED 班次 revenue_by_payment 不可為 null）
        shift.setStatus("CLOSED");
        shift.setCups(totalCups);
        shift.setExpectedCash(expectedCash);
        shift.setCountedCash(actualCounted);
        shift.setDiffReason(diffReason);
        shift.setClosedBy(closedBy);
        shift.setClosedAt(LocalDateTime.now());
        if (shift.getRevenueByPayment() == null) {
            shift.setRevenueByPayment("{}");
        }

        dailyShiftRepository.save(shift);

        return new ShiftClosingSummaryResponse(
                businessDate,
                shift.getStatus(),
                shift.getPettyCash(),
                totalRevenue,
                totalWastageCost,
                expectedCash,
                actualCounted,
                diffAmount,
                totalCups,
                diffReason
        );
    }

    /**
     * 重新開班（取消關班、接續營業）
     * 清掉關班結果（關班人員、時間、杯數、應有／清點現金、差異原因），開班人員、開班時間與零用金維持原本的。
     * 之後再關班會重新計算整天的營收與現金。
     */
    public DailyShift reopenShift(LocalDate businessDate) {
        DailyShift shift = dailyShiftRepository.findById(businessDate)
                .orElseThrow(() -> new IllegalStateException("營業日 [" + businessDate + "] 還沒開班"));
        if (!"CLOSED".equalsIgnoreCase(shift.getStatus())) {
            throw new IllegalStateException("營業日 [" + businessDate + "] 還在營業中");
        }
        shift.setStatus("OPEN");
        shift.setClosedBy(null);
        shift.setClosedAt(null);
        shift.setCups(null);
        shift.setExpectedCash(null);
        shift.setCountedCash(null);
        shift.setDiffReason(null);
        shift.setRevenueByPayment(null);
        return dailyShiftRepository.save(shift);
    }

    /**
     * 查詢指定營業日的班次資訊
     */
    @Transactional(readOnly = true)
    public DailyShift getShift(LocalDate businessDate) {
        return dailyShiftRepository.findById(businessDate)
                .orElseThrow(() -> new ResourceNotFoundException("查無指定營業日 [" + businessDate + "] 之班次資料"));
    }
}

