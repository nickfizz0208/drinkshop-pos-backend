package com.example.drinkshop.service;

import com.example.drinkshop.dto.InventoryCountDtos.*;
import com.example.drinkshop.exception.ApiException;
import com.example.drinkshop.repository.InventoryRepository;
import com.example.drinkshop.repository.PosRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 原料盤點：記錄實點數量與當時的帳面數量、差異，不修改帳面庫存（book_qty）。
 * 同一次送出的所有原料在同一筆交易；任何一項失敗就整批不記錄。
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class InventoryCountService {
    /** 一次最多查 31 天，避免一次撈出太多資料。 */
    static final int MAX_RANGE_DAYS = 31;

    private final PosRepository repo;
    private final InventoryRepository inventories;
    private final Clock clock;
    private final boolean enabled;

    public InventoryCountService(PosRepository repo, InventoryRepository inventories, Clock clock,
                                 @Value("${pos.inventory-count.enabled:false}") boolean enabled) {
        this.repo = repo; this.inventories = inventories; this.clock = clock; this.enabled = enabled;
    }

    public List<Row> submit(Submit request, long staffId) {
        requireEnabled();
        String requestId = request.requestId().toString();
        // 重送：同一個 requestId 已經記錄過，就回傳原本的結果，不再寫一次
        var existing = rowsOf("WHERE c.request_id=?", requestId);
        if (!existing.isEmpty()) {
            if (existing.getFirst().countedBy() != staffId) throw ApiException.conflict("這次盤點已由其他員工送出");
            return existing;
        }
        var ids = new HashSet<Long>();
        for (var line : request.lines())
            if (!ids.add(line.inventoryId())) throw ApiException.bad("同一項原料在一次盤點中只能出現一次");
        // 只能填整數的原料：實點數量不能有小數（例如紙杯不會有半個）
        var allowDecimal = new HashMap<Long, Boolean>();
        for (var r : repo.rows("SELECT * FROM inventory")) allowDecimal.put(PosRepository.number(r, "inventory_id"), allowsDecimal(r));
        for (var line : request.lines())
            if (!allowDecimal.getOrDefault(line.inventoryId(), true) && line.countedQty().stripTrailingZeros().scale() > 0) {
                String name = repo.one("SELECT name FROM inventory WHERE inventory_id=?", line.inventoryId()).get("name").toString();
                throw ApiException.bad("「" + name + "」只能填整數");
            }
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        LocalDateTime now = LocalDateTime.now(clock);
        // 依編號順序鎖原料，避免兩台同時盤點時互相等待（死結）
        for (var line : request.lines().stream().sorted(Comparator.comparing(Line::inventoryId)).toList()) {
            var inventory = inventories.findLocked(line.inventoryId())
                .orElseThrow(() -> ApiException.missing("查無原料編號 " + line.inventoryId()));
            BigDecimal counted = line.countedQty().setScale(2, RoundingMode.UNNECESSARY);
            BigDecimal book = inventory.getBookQty().setScale(2, RoundingMode.HALF_UP);
            repo.insert("count_id", """
                INSERT INTO inventory_count(request_id,inventory_id,counted_qty,book_qty,diff_qty,unit_cost,counted_by,counted_at,note)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, requestId, line.inventoryId(), counted, book, counted.subtract(book),
                inventory.getUnitCost(), staffId, now, note);
            // 最新一次盤點仍寫回原料主檔，舊畫面（reported_qty）繼續可用
            inventory.setReportedQty(counted); inventory.setReportedBy(staffId); inventory.setReportedAt(now);
            inventories.save(inventory);
        }
        return rowsOf("WHERE c.request_id=?", requestId);
    }

    /** 盤點頁的原料清單（含是否允許小數）。 */
    @Transactional(readOnly = true)
    public List<Item> items() {
        var out = new ArrayList<Item>();
        for (var r : repo.rows("SELECT * FROM inventory ORDER BY inventory_id"))
            out.add(new Item(PosRepository.number(r, "inventory_id"), r.get("name").toString(), r.get("base_unit").toString(),
                (String) r.get("pack_unit"), decimal(r, "per_pack"), decimal(r, "book_qty"), decimal(r, "safety_stock"),
                decimal(r, "unit_cost"), allowsDecimal(r)));
        return out;
    }

    /** 016 升級前 inventory 沒有 allow_decimal 欄位：一律當成可以填小數，不影響現有功能。 */
    private static boolean allowsDecimal(Map<String, Object> row) {
        return !row.containsKey("allow_decimal") || PosRepository.flag(row, "allow_decimal");
    }

    @Transactional(readOnly = true)
    public History history(LocalDate from, LocalDate to) {
        if (!enabled) return new History(false, List.of());
        LocalDate today = LocalDate.now(clock);
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(6);
        if (start.isAfter(end)) throw ApiException.bad("開始日期不能晚於結束日期");
        if (start.plusDays(MAX_RANGE_DAYS - 1).isBefore(end)) throw ApiException.bad("一次最多查詢 " + MAX_RANGE_DAYS + " 天");
        return new History(true, rowsOf("WHERE c.counted_at>=? AND c.counted_at<?",
            start.atStartOfDay(), end.plusDays(1).atStartOfDay()));
    }

    private void requireEnabled() {
        // 共用庫還沒升級 016 前不寫入，避免畫面顯示送出成功、其實沒有紀錄
        if (!enabled) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
            "盤點紀錄尚未啟用，請先由資料庫負責人確認 016 升級");
    }

    private List<Row> rowsOf(String where, Object... args) {
        var out = new ArrayList<Row>();
        for (var r : repo.rows("""
            SELECT c.*, i.name AS inventory_name, i.base_unit, s.name AS staff_name
            FROM inventory_count c JOIN inventory i ON i.inventory_id=c.inventory_id
            JOIN staff_account s ON s.staff_id=c.counted_by
            """ + where + " ORDER BY c.counted_at DESC, c.count_id", args)) {
            BigDecimal diff = decimal(r, "diff_qty"), unitCost = decimal(r, "unit_cost");
            out.add(new Row(PosRepository.number(r, "count_id"), r.get("request_id").toString(),
                PosRepository.number(r, "inventory_id"), r.get("inventory_name").toString(), r.get("base_unit").toString(),
                decimal(r, "counted_qty"), decimal(r, "book_qty"), diff, unitCost,
                unitCost == null ? null : diff.multiply(unitCost).setScale(2, RoundingMode.HALF_UP),
                PosRepository.number(r, "counted_by"), Objects.toString(r.get("staff_name"), null),
                timestamp(r.get("counted_at")), (String) r.get("note")));
        }
        return out;
    }
    private static LocalDateTime timestamp(Object value) {
        // 不同 JDBC 驅動可能回傳 Timestamp 或 LocalDateTime
        return value instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime) value;
    }
    private static BigDecimal decimal(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : new BigDecimal(row.get(key).toString());
    }
}
