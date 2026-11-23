package com.example.drinkshop.service;

import com.example.drinkshop.dto.PrepBatchDtos.*;
import com.example.drinkshop.entity.Staff;
import com.example.drinkshop.exception.ApiException;
import com.example.drinkshop.repository.PosRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 備料（050、051）：登記誰做了什麼半成品、幾份，以及剩下的半成品倒掉幾份。
 * POS 共用帳號登記時要選是誰（在職員工）；個人帳號登記時記成自己。
 * 只做紀錄：沒有半成品庫存、不扣原料帳面。做半成品用掉的原料由物料消耗報表照材料用量計算（ConsumptionController）。
 */
@Service
public class PrepBatchService {
    private final PosRepository repo;
    private final Clock clock;
    public PrepBatchService(PosRepository repo, Clock clock) { this.repo = repo; this.clock = clock; }

    @Transactional(readOnly = true)
    public List<PrepOption> preps() {
        return repo.rows("SELECT prep_id, name, category FROM prep_item ORDER BY category, prep_id").stream()
            .map(r -> new PrepOption(PosRepository.number(r, "prep_id"), (String) r.get("name"), (String) r.get("category")))
            .toList();
    }

    /** 某一天的備料、報廢與總覽；date 是 null＝今天。 */
    @Transactional(readOnly = true)
    public Day day(LocalDate date) {
        LocalDate target = date == null ? LocalDate.now(clock) : date;
        var rows = repo.rows(SELECT_BATCH + " WHERE b.business_date = ? ORDER BY b.made_at DESC, b.batch_id DESC", target)
            .stream().map(PrepBatchService::row).toList();
        var wastes = repo.rows(SELECT_WASTE + " WHERE w.business_date = ? ORDER BY w.wasted_at DESC, w.waste_id DESC", target)
            .stream().map(PrepBatchService::wasteRow).toList();
        return new Day(target.toString(), rows, wastes, summary(target, rows, wastes));
    }

    @Transactional
    public Row create(Create request, Staff current) {
        requirePrep(request.prepId());
        long madeBy = who(request.madeBy(), current, "請選擇是誰做的");
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        long id = repo.insert("batch_id", """
            INSERT INTO prep_batch(business_date, prep_id, qty, made_by, recorded_by, made_at, note)
            VALUES (?,?,?,?,?,?,?)
            """, now.toLocalDate(), request.prepId(), request.qty(), madeBy, current.id(), now, note);
        return row(repo.one(SELECT_BATCH + " WHERE b.batch_id = ?", id));
    }

    @Transactional
    public WasteRow createWaste(WasteCreate request, Staff current) {
        requirePrep(request.prepId());
        long wastedBy = who(request.wastedBy(), current, "請選擇是誰處理的");
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        long id = repo.insert("waste_id", """
            INSERT INTO prep_waste(business_date, prep_id, qty, wasted_by, recorded_by, wasted_at, note)
            VALUES (?,?,?,?,?,?,?)
            """, now.toLocalDate(), request.prepId(), request.qty(), wastedBy, current.id(), now, request.note().trim());
        return wasteRow(repo.one(SELECT_WASTE + " WHERE w.waste_id = ?", id));
    }

    private void requirePrep(long prepId) {
        if (repo.rows("SELECT prep_id FROM prep_item WHERE prep_id = ?", prepId).isEmpty())
            throw ApiException.missing("找不到這個半成品");
    }

    /** POS 機登記：要選人，只能選在職員工（POS 共用帳號不是真人）；個人帳號登記：記成自己。 */
    private long who(Long picked, Staff current, String missing) {
        if (!"POS".equals(current.level())) return current.id();
        if (picked == null) throw ApiException.bad(missing);
        if (repo.rows("SELECT staff_id FROM staff_account WHERE staff_id = ? AND active = TRUE AND level <> 'POS'", picked).isEmpty())
            throw ApiException.bad("要選在職員工");
        return picked;
    }

    /** 訂單用掉的半成品份數（飲品配方＋加料；取消的不算），加上當天做了、倒掉的份數。 */
    private List<Summary> summary(LocalDate date, List<Row> rows, List<WasteRow> wastes) {
        Map<Long, BigDecimal> made = new HashMap<>(), used = new HashMap<>(), wasted = new HashMap<>();
        for (var r : rows) made.merge(r.prepId(), r.qty(), BigDecimal::add);
        for (var w : wastes) wasted.merge(w.prepId(), w.qty(), BigDecimal::add);
        for (var r : repo.rows("""
            SELECT pr.prep_id, SUM(oi.qty * COALESCE(pr.portions, 1)) AS qty
              FROM pos_order o
              JOIN order_item oi ON oi.order_id = o.order_id
              JOIN product_recipe pr ON pr.product_id = oi.product_id
             WHERE o.business_date = ? AND o.status <> 'CANCELLED' AND oi.status <> 'CANCELLED' AND pr.prep_id IS NOT NULL
             GROUP BY pr.prep_id
            UNION ALL
            SELECT t.prep_id, SUM(oi.qty * oit.qty) AS qty
              FROM pos_order o
              JOIN order_item oi ON oi.order_id = o.order_id
              JOIN order_item_topping oit ON oit.item_id = oi.item_id
              JOIN topping t ON t.topping_id = oit.topping_id
             WHERE o.business_date = ? AND o.status <> 'CANCELLED' AND oi.status <> 'CANCELLED' AND t.prep_id IS NOT NULL
             GROUP BY t.prep_id
            """, date, date)) {
            used.merge(PosRepository.number(r, "prep_id"), new BigDecimal(r.get("qty").toString()), BigDecimal::add);
        }
        var out = new ArrayList<Summary>();
        for (var p : repo.rows("SELECT prep_id, name, category FROM prep_item ORDER BY category, prep_id")) {
            long id = PosRepository.number(p, "prep_id");
            if (!made.containsKey(id) && !used.containsKey(id) && !wasted.containsKey(id)) continue;
            BigDecimal m = scale(made.get(id)), u = scale(used.get(id)), w = scale(wasted.get(id));
            out.add(new Summary(id, (String) p.get("name"), (String) p.get("category"), m, u, w, m.subtract(u).subtract(w)));
        }
        return out;
    }
    private static BigDecimal scale(BigDecimal v) { return (v == null ? BigDecimal.ZERO : v).setScale(2, java.math.RoundingMode.HALF_UP); }

    private static final String SELECT_BATCH = """
        SELECT b.batch_id, b.business_date, b.prep_id, x.name AS prep_name, b.qty, b.made_by, m.name AS made_by_name,
               b.recorded_by, r.name AS recorded_by_name, b.made_at, b.note
          FROM prep_batch b
          JOIN prep_item x ON x.prep_id = b.prep_id
          JOIN staff_account m ON m.staff_id = b.made_by
          JOIN staff_account r ON r.staff_id = b.recorded_by
        """;
    private static final String SELECT_WASTE = """
        SELECT w.waste_id, w.business_date, w.prep_id, x.name AS prep_name, w.qty, w.wasted_by, m.name AS wasted_by_name,
               w.recorded_by, r.name AS recorded_by_name, w.wasted_at, w.note
          FROM prep_waste w
          JOIN prep_item x ON x.prep_id = w.prep_id
          JOIN staff_account m ON m.staff_id = w.wasted_by
          JOIN staff_account r ON r.staff_id = w.recorded_by
        """;

    private static Row row(Map<String, Object> r) {
        return new Row(PosRepository.number(r, "batch_id"), date(r.get("business_date")), PosRepository.number(r, "prep_id"),
            (String) r.get("prep_name"), new BigDecimal(r.get("qty").toString()), PosRepository.number(r, "made_by"),
            (String) r.get("made_by_name"), PosRepository.number(r, "recorded_by"), (String) r.get("recorded_by_name"),
            time(r.get("made_at")), (String) r.get("note"));
    }
    private static WasteRow wasteRow(Map<String, Object> r) {
        return new WasteRow(PosRepository.number(r, "waste_id"), date(r.get("business_date")), PosRepository.number(r, "prep_id"),
            (String) r.get("prep_name"), new BigDecimal(r.get("qty").toString()), PosRepository.number(r, "wasted_by"),
            (String) r.get("wasted_by_name"), PosRepository.number(r, "recorded_by"), (String) r.get("recorded_by_name"),
            time(r.get("wasted_at")), (String) r.get("note"));
    }
    private static String time(Object value) {
        return value instanceof java.sql.Timestamp t ? t.toLocalDateTime().truncatedTo(ChronoUnit.SECONDS).toString()
            : value instanceof LocalDateTime l ? l.truncatedTo(ChronoUnit.SECONDS).toString() : String.valueOf(value);
    }
    private static String date(Object value) {
        return value instanceof java.sql.Date d ? d.toLocalDate().toString() : String.valueOf(value);
    }
}
