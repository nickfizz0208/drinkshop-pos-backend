package com.example.drinkshop.controller;

import com.example.drinkshop.exception.ApiException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 總部專屬營運報表 API（只限 HQ 帳號，由 SecurityConfig 控制）：
 * 支援日、月和自訂區間，
 * 統計總營收、訂單數、銷售杯數、報廢損耗、營收走勢圖、熱銷飲品排行與訂單明細。
 */
@RestController
@RequestMapping("/api/hq-reports")
public class HqReportController {

        private final JdbcTemplate jdbc;
        private final Clock clock;

        public HqReportController(JdbcTemplate jdbc, Clock clock) {
                this.jdbc = jdbc;
                this.clock = clock;
        }

    @GetMapping
    public Map<String, Object> report(
            @RequestParam(name = "mode", defaultValue = "month") String mode,
            @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "storeIds", required = false) List<Long> storeIds) {

        if (storeIds != null && !storeIds.isEmpty()) {
            throw ApiException.bad("目前的報表尚未支援分店篩選");
        }
        String reportMode = mode.toLowerCase(Locale.ROOT);
        if (!Set.of("day", "month", "range").contains(reportMode)) {
            throw ApiException.bad("報表期間只能選日、月或自訂區間");
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate targetDate = date != null ? date : today;
        int targetYear = year != null ? year : today.getYear();
        int targetMonth = (month != null && month >= 1 && month <= 12) ? month : today.getMonthValue();
        LocalDate periodFrom;
        LocalDate periodTo;
        switch (reportMode) {
            case "range" -> {
                if (from == null || to == null || from.isAfter(to)) {
                    throw ApiException.bad("請選擇有效的開始和結束日期");
                }
                if (ChronoUnit.DAYS.between(from, to) >= 92) {
                    throw ApiException.bad("一次最多查詢 92 天");
                }
                periodFrom = from;
                periodTo = to;
            }
            case "day" -> periodFrom = periodTo = targetDate;
            default -> {
                YearMonth period = YearMonth.of(targetYear, targetMonth);
                periodFrom = period.atDay(1);
                periodTo = period.atEndOfMonth();
            }
        }

        String orderDateCondition = "o.business_date BETWEEN ? AND ?";
        Object[] params = { periodFrom, periodTo };

        // 1. 總覽指標：營收、訂單數、銷售杯數
        Map<String, Object> salesRow = jdbc.queryForMap(
                """
                        SELECT COALESCE(SUM(CASE WHEN i.status NOT IN ('CANCELLED', 'WASTED') THEN i.qty * i.unit_price ELSE 0 END), 0) AS revenue,
                               COUNT(DISTINCT CASE WHEN o.status != 'CANCELLED' THEN o.order_id END) AS order_count,
                               COALESCE(SUM(CASE WHEN i.status NOT IN ('CANCELLED', 'WASTED') THEN i.qty ELSE 0 END), 0) AS total_cups,
                               COALESCE(SUM(CASE WHEN i.status = 'WASTED' THEN i.qty ELSE 0 END), 0) AS wasted_cups
                        FROM pos_order o
                        LEFT JOIN order_item i ON i.order_id = o.order_id
                        WHERE"""
                        + " " + orderDateCondition,
                params);

        BigDecimal rawWastageCost = jdbc.queryForObject(
                        "SELECT COALESCE(SUM(material_cost), 0) FROM raw_material_wastage WHERE business_date BETWEEN ? AND ?",
                BigDecimal.class, params);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("revenue", toDecimal(salesRow.get("revenue")));
        summary.put("orderCount", toLong(salesRow.get("order_count")));
        summary.put("totalCups", toLong(salesRow.get("total_cups")));
        summary.put("wastedCups", toLong(salesRow.get("wasted_cups")));
        summary.put("rawWastageCost", rawWastageCost != null ? rawWastageCost : BigDecimal.ZERO);

        // 2. 單日按時段，多日按日期。
        List<Map<String, Object>> trend;
        if (periodFrom.equals(periodTo)) {
            trend = jdbc.queryForList("""

        SELECT CONCAT(LPAD(HOUR(o.created_at), 2, '0'), ':00') AS label,
                           COALESCE(SUM(i.qty * i.unit_price), 0) AS revenue,
                           COALESCE(SUM(i.qty), 0) AS cups
                    FROM pos_order o
                    JOIN order_item i ON i.order_id = o.order_id
                    WHERE o.business_date = ?
                      AND o.status != 'CANCELLED'
                      AND i.status NOT IN ('CANCELLED', 'WASTED')
                    GROUP BY label
                    ORDER BY label ASC
                    """, periodFrom);
        } else {
                trend = jdbc.queryForList("""
                                SELECT DATE_FORMAT(o.business_date, '%m/%d') AS label,
                                       COALESCE(SUM(i.qty * i.unit_price), 0) AS revenue,
                                       COALESCE(SUM(i.qty), 0) AS cups
                                FROM pos_order o
                                JOIN order_item i ON i.order_id = o.order_id
                                WHERE o.business_date BETWEEN ? AND ?
                                  AND o.status != 'CANCELLED'
                                  AND i.status NOT IN ('CANCELLED', 'WASTED')
                                GROUP BY o.business_date
                                ORDER BY o.business_date ASC
                                """, params);
        }

        // 3. 熱銷飲品排行（賣的東西統計）
        List<Map<String, Object>> topProducts = jdbc.queryForList("""
                        SELECT i.product_name AS productName,
                               COALESCE(SUM(i.qty), 0) AS cups,
                               COALESCE(SUM(i.qty * i.unit_price), 0) AS revenue
                        FROM pos_order o
                        JOIN order_item i ON i.order_id = o.order_id
                        WHERE""" + " " + orderDateCondition + " \n" + """
                          AND o.status != 'CANCELLED'
                          AND i.status NOT IN ('CANCELLED', 'WASTED')
                        GROUP BY i.product_name
                        ORDER BY revenue DESC, cups DESC
                        LIMIT 15
                        """, params);

        // 4. 訂單紀錄明細（最新 100 筆）
        List<Map<String, Object>> orders = jdbc.queryForList(
                        """
                                        SELECT o.order_id AS orderId,
                                               DATE_FORMAT(o.business_date, '%Y-%m-%d') AS businessDate,
                                               DATE_FORMAT(o.created_at, '%Y-%m-%d %H:%i') AS createdAt,
                                               o.status AS status,
                                               COALESCE(CAST(o.payment AS CHAR), '未收款') AS payment,
                                               COALESCE(SUM(CASE WHEN i.status NOT IN ('CANCELLED', 'WASTED') THEN i.qty ELSE 0 END), 0) AS totalCups,
                                               COALESCE(SUM(CASE WHEN i.status NOT IN ('CANCELLED', 'WASTED') THEN i.qty * i.unit_price ELSE 0 END), 0) AS revenue,
                                               GROUP_CONCAT(CONCAT(i.product_name, '×', i.qty) ORDER BY i.item_id SEPARATOR '、') AS itemsSummary
                                        FROM pos_order o
                                        LEFT JOIN order_item i ON i.order_id = o.order_id
                                                        WHERE""" + " " + orderDateCondition + " \n"
                                        + """
                                                        GROUP BY o.order_id, o.business_date, o.created_at, o.status, o.payment
                                                        ORDER BY o.order_id DESC
                                                        LIMIT 100
                                                        """,
                        params);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", reportMode);
        result.put("from", periodFrom.toString());
        result.put("to", periodTo.toString());
        result.put("date", targetDate.toString());
        result.put("year", targetYear);
        result.put("month", targetMonth);
        result.put("summary", summary);
        result.put("trend", trend);
        result.put("topProducts", topProducts);
        result.put("orders", orders);
        return result;
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null)
            return BigDecimal.ZERO;
        if (value instanceof BigDecimal b)
            return b;
        return new BigDecimal(value.toString());
    }

    private static long toLong(Object value) {
        if (value == null)
            return 0L;
        if (value instanceof Number n)
            return n.longValue();
        return Long.parseLong(value.toString());
    }
}
