package com.example.drinkshop.service;

import com.example.drinkshop.dto.ShiftClosingSummaryResponse;
import com.example.drinkshop.entity.DailyShift;
import com.example.drinkshop.exception.ApiException;
import com.example.drinkshop.repository.DailyShiftRepository;
import com.example.drinkshop.repository.PosRepository;
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
    private final DailyShiftRepository shifts;
    private final PosRepository repo;
    private final WastageService wastage;
    private final RuleService json;
    private final Clock clock;

    public DailyShiftService(DailyShiftRepository shifts, PosRepository repo, WastageService wastage,
                             RuleService json, Clock clock) {
        this.shifts = shifts; this.repo = repo; this.wastage = wastage; this.json = json; this.clock = clock;
    }

    public DailyShift openShift(LocalDate date, Long staffId, BigDecimal pettyCash, String rolesJson) {
        if (shifts.existsById(date)) throw ApiException.conflict("這個營業日已經開過班");
        var roles = json.parse(rolesJson == null ? "[]" : rolesJson);
        if (!roles.isArray()) throw ApiException.bad("排班內容必須是 JSON 陣列");
        DailyShift shift = new DailyShift();
        shift.setBusinessDate(date); shift.setStatus("OPEN"); shift.setOpenedBy(staffId);
        shift.setOpenedAt(LocalDateTime.now(clock)); shift.setPettyCash(Money.checked(pettyCash));
        shift.setRolesJson(json.write(roles));
        return shifts.saveAndFlush(shift);
    }

    public ShiftClosingSummaryResponse closeShift(LocalDate date, Long staffId, BigDecimal countedCash, String diffReason) {
        // 與 POS 建單、退款和原料報廢共用同一把班次鎖；等待中的交易醒來後要再確認 OPEN。
        var rows = repo.rows("SELECT * FROM daily_shift WHERE business_date=? FOR UPDATE", date);
        if (rows.isEmpty()) throw ApiException.missing("找不到這個營業日的班次");
        if (!"OPEN".equals(rows.getFirst().get("status"))) throw ApiException.conflict("這個營業日已日結");
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
            throw ApiException.bad("現金有差額，請填寫差額原因");
        var waste = wastage.getDailyWastageSummary(date);
        shift.setStatus("CLOSED"); shift.setCups(cups); shift.setExpectedCash(expected);
        shift.setCountedCash(counted); shift.setDiffReason(diffReason); shift.setClosedBy(staffId);
        shift.setClosedAt(LocalDateTime.now(clock)); shift.setRevenueByPayment(json.write(receipts));
        shifts.saveAndFlush(shift);
        return new ShiftClosingSummaryResponse(date, "CLOSED", shift.getPettyCash(), revenue,
            waste.totalWastageCost(), expected, counted, difference, cups, diffReason,
            waste.costComplete(), waste.rawWastageEnabled());
    }

    @Transactional(readOnly = true)
    public DailyShift getShift(LocalDate date) {
        return shifts.findById(date).orElseThrow(() -> ApiException.missing("查無這個營業日的班次"));
    }
}
