package com.example.drinkshop.service;

import com.example.drinkshop.dto.ShiftClosingSummaryResponse;
import com.example.drinkshop.entity.DailyShift;
import com.example.drinkshop.exception.ApiException;
import com.example.drinkshop.repository.DailyShiftRepository;
import com.example.drinkshop.repository.PosRepository;
import com.example.drinkshop.repository.ShiftStaffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.LinkedHashMap;

/** 日結分開記錄銷售、收退款與原料損耗；原料成本不代表錢櫃支出。 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class DailyShiftService {
    public static final String AUTO_CLOSE_REASON = "系統逾午夜自動收班";
    private final DailyShiftRepository shifts;
    private final PosRepository repo;
    private final WastageService wastage;
    private final RuleService json;
    private final Clock clock;
    private final ShiftStaffRepository shiftStaffRepo;

    public DailyShiftService(DailyShiftRepository shifts, PosRepository repo, WastageService wastage,
                             RuleService json, Clock clock, ShiftStaffRepository shiftStaffRepo) {
        this.shifts = shifts; this.repo = repo; this.wastage = wastage; this.json = json; this.clock = clock;
        this.shiftStaffRepo = shiftStaffRepo;
    }

    public DailyShift openShift(LocalDate date, Long staffId, BigDecimal pettyCash, String rolesJson) {
        if (shifts.existsById(date)) throw ApiException.conflict("今天已經開班，不可重複開班");
        var roles = json.parse(rolesJson == null ? "[]" : rolesJson);
        if (!roles.isArray()) throw ApiException.bad("排班內容必須是 JSON 陣列");
        DailyShift shift = new DailyShift();
        shift.setBusinessDate(date); shift.setStatus("OPEN"); shift.setOpenedBy(staffId);
        shift.setOpenedAt(LocalDateTime.now(clock)); shift.setPettyCash(Money.checked(pettyCash));
        shift.setRolesJson(json.write(roles));
        return shifts.saveAndFlush(shift);
    }

    public ShiftClosingSummaryResponse closeShift(LocalDate date, Long staffId, BigDecimal countedCash, String diffReason) {
        return closeShift(date, staffId, countedCash, diffReason, false);
    }

    public ShiftClosingSummaryResponse closeShiftFromPos(LocalDate date, Long staffId, BigDecimal countedCash, String diffReason) {
        return closeShift(date, staffId, countedCash, diffReason, true);
    }

    private ShiftClosingSummaryResponse closeShift(LocalDate date, Long staffId, BigDecimal countedCash,
                                                   String diffReason, boolean requireOnDuty) {
        // 與 POS 建單、退款和原料報廢共用同一把班次鎖；等待中的交易醒來後要再確認 OPEN。
        var rows = repo.rows("SELECT * FROM daily_shift WHERE business_date=? FOR UPDATE", date);
        if (rows.isEmpty()) throw ApiException.conflict("今天還沒開班，不能關班");
        if (!"OPEN".equals(rows.getFirst().get("status"))) throw ApiException.conflict("今天已經關班，不可重複關班");
        if (requireOnDuty && !shiftStaffRepo.existsByBusinessDateAndStaffIdAndClockOutAtIsNullAndClockOutSourceIsNull(date, staffId))
            throw ApiException.conflict("關班人員目前不在班，請選擇在班人員或先打卡上班");
        var shift = shifts.findById(date).orElseThrow();
        var sales = repo.one("""
            SELECT COALESCE(SUM(i.qty*i.unit_price),0) AS revenue, COALESCE(SUM(i.qty),0) AS cups
            FROM order_item i JOIN pos_order o ON o.order_id=i.order_id
            WHERE o.business_date=? AND i.status NOT IN ('CANCELLED','WASTED')
            """, date);
        BigDecimal revenue = new BigDecimal(sales.get("revenue").toString());
        int cups = PosRepository.integer(sales, "cups");
        var receipts = new LinkedHashMap<String,BigDecimal>();
        // paid_amount 已扣原找零；再減退款，不能再加品項售價或把非現金付款算進錢櫃。
        for (var row : repo.rows("""
            SELECT payment, SUM(paid_amount-refunded_amount) AS net
            FROM pos_order WHERE business_date=? AND payment IS NOT NULL GROUP BY payment
            """, date)) receipts.put(row.get("payment").toString(), new BigDecimal(row.get("net").toString()));
        BigDecimal expected = shift.getPettyCash().add(receipts.getOrDefault("CASH", Money.ZERO));
        BigDecimal counted = Money.checked(countedCash);
        BigDecimal difference = counted.subtract(expected);
        if (difference.signum()!=0 && (diffReason==null || diffReason.isBlank()))
            throw ApiException.bad("現金" + (difference.signum()<0 ? "短少" : "溢收") + " $"
                + difference.abs().stripTrailingZeros().toPlainString()
                + "（應有 $" + expected.stripTrailingZeros().toPlainString() + "），請填寫差異原因");
        var waste = wastage.getDailyWastageSummary(date);
        LocalDateTime closedAt = LocalDateTime.now(clock);
        shift.setStatus("CLOSED"); shift.setCups(cups); shift.setExpectedCash(expected);
        shift.setCountedCash(counted); shift.setDiffReason(diffReason); shift.setClosedBy(staffId);
        shift.setClosedAt(closedAt); shift.setRevenueByPayment(json.write(receipts));
        if (shift.getRevenueByPayment() == null) shift.setRevenueByPayment("{}");
        shifts.saveAndFlush(shift);

        // 關班時強制所有在班人員打卡下班
        for (var onDutyStaff : shiftStaffRepo.findByBusinessDateAndClockOutAtIsNullAndClockOutSourceIsNull(date)) {
            onDutyStaff.setClockOutAt(closedAt);
            onDutyStaff.setClockOutSource("AUTO_SHIFT_CLOSE");
            shiftStaffRepo.save(onDutyStaff);
        }
        shiftStaffRepo.flush();

        return new ShiftClosingSummaryResponse(date, "CLOSED", shift.getPettyCash(), revenue,
            waste.totalWastageCost(), expected, counted, difference, cups, diffReason,
            waste.costComplete(), waste.rawWastageEnabled());
    }

    /**
     * 處理逾期未關班的歷史班次（如超過午夜 12 點未收班）：
     * 自動結算並收班。個人工時由排班補登程序處理。
     */
    public int closeOverdueShifts(LocalDate today) {
        var overdue = shifts.findByBusinessDateBeforeAndStatus(today, "OPEN");
        int count = 0;
        for (var shift : overdue) {
            LocalDate date = shift.getBusinessDate();
            LocalDateTime midnight = date.atTime(23, 59, 59);

            var locked = repo.rows("SELECT status FROM daily_shift WHERE business_date=? FOR UPDATE", date);
            if (locked.isEmpty() || !"OPEN".equals(locked.getFirst().get("status"))) continue;
            // 計算營收與收款
            var sales = repo.one("""
                SELECT COALESCE(SUM(i.qty*i.unit_price),0) AS revenue, COALESCE(SUM(i.qty),0) AS cups
                FROM order_item i JOIN pos_order o ON o.order_id=i.order_id
                WHERE o.business_date=? AND i.status NOT IN ('CANCELLED','WASTED')
                """, date);
            int cups = PosRepository.integer(sales, "cups");
            var receipts = new LinkedHashMap<String, BigDecimal>();
            for (var row : repo.rows("""
                SELECT payment, SUM(paid_amount-refunded_amount) AS net
                FROM pos_order WHERE business_date=? AND payment IS NOT NULL GROUP BY payment
                """, date)) {
                receipts.put(row.get("payment").toString(), new BigDecimal(row.get("net").toString()));
            }
            BigDecimal expected = shift.getPettyCash().add(receipts.getOrDefault("CASH", Money.ZERO));

            // 3. 收班
            shift.setStatus("CLOSED");
            shift.setCups(cups);
            shift.setExpectedCash(expected);
            shift.setCountedCash(null);
            shift.setDiffReason(AUTO_CLOSE_REASON);
            shift.setClosedBy(null);
            shift.setClosedAt(midnight);
            shift.setRevenueByPayment(json.write(receipts));
            if (shift.getRevenueByPayment() == null) shift.setRevenueByPayment("{}");
            shifts.save(shift);
            count++;
        }
        shifts.flush();
        return count;
    }

    /**
     * 重新開班（取消關班、接續營業）
     * 清掉關班結果（關班人員、時間、杯數、應有／清點現金、差異原因、各付款方式營收），
     * 開班人員、開班時間與零用金維持原本的。之後再關班會重新計算整天的營收與現金。
     */
    public DailyShift reopenShift(LocalDate date) {
        // 與關班共用同一把班次鎖，避免同時關班又重開
        var rows = repo.rows("SELECT status FROM daily_shift WHERE business_date=? FOR UPDATE", date);
        if (rows.isEmpty()) throw new IllegalStateException("營業日 [" + date + "] 還沒開班");
        if (!"CLOSED".equals(rows.getFirst().get("status")))
            throw new IllegalStateException("營業日 [" + date + "] 還在營業中");
        var shift = shifts.findById(date).orElseThrow();
        shift.setStatus("OPEN");
        shift.setClosedBy(null); shift.setClosedAt(null); shift.setCups(null);
        shift.setExpectedCash(null); shift.setCountedCash(null); shift.setDiffReason(null);
        shift.setRevenueByPayment(null);
        return shifts.saveAndFlush(shift);
    }

    /** 系統關班後補記實點；使用原結算快照，且同一班次只確認一次。 */
    public DailyShift reconcileCash(LocalDate date, Long staffId, BigDecimal countedCash, String diffReason) {
        var rows = repo.rows("SELECT status FROM daily_shift WHERE business_date=? FOR UPDATE", date);
        if (rows.isEmpty()) throw ApiException.missing("查無這個營業日的班次");
        var shift = shifts.findById(date).orElseThrow();
        if (!"CLOSED".equals(shift.getStatus()) || shift.getDiffReason() == null
                || !shift.getDiffReason().startsWith(AUTO_CLOSE_REASON))
            throw ApiException.conflict("只有系統關班的紀錄可補記清點");
        BigDecimal counted = Money.checked(countedCash);
        String reason = diffReason == null || diffReason.isBlank() ? "無差額" : diffReason.trim();
        String reasonSuffix = "；差異原因：" + reason;
        if (!AUTO_CLOSE_REASON.equals(shift.getDiffReason())) {
            if (staffId.equals(shift.getClosedBy()) && counted.compareTo(shift.getCountedCash()) == 0
                    && shift.getDiffReason().endsWith(reasonSuffix)) return shift;
            throw ApiException.conflict("這筆清點已由店長確認，請重新整理查看");
        }
        if (shift.getExpectedCash() == null) throw ApiException.conflict("缺少應有現金，請先核對班次資料");
        if (counted.compareTo(shift.getExpectedCash()) != 0 && (diffReason == null || diffReason.isBlank()))
            throw ApiException.bad("現金有差異，請填寫差異原因");
        shift.setCountedCash(counted);
        shift.setClosedBy(staffId);
        shift.setDiffReason(AUTO_CLOSE_REASON + "；清點確認 " + LocalDateTime.now(clock).withNano(0) + reasonSuffix);
        return shifts.saveAndFlush(shift);
    }

    @Transactional(readOnly = true)
    public DailyShift getShift(LocalDate date) {
        return shifts.findById(date).orElseThrow(() -> ApiException.missing("查無這個營業日的班次"));
    }
}
