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
 * 原料盤點：保留盤點時的帳面及差異，店長送出或確認後才更新帳面庫存。
 * 點餐、取消、報廢都不扣帳面，帳面只由盤點（與手動校正）決定。
 * 資料表（017）：inventory_count 是表頭（一次盤點一列），inventory_count_line 是明細（每項原料一列）。
 * 盤點類型（018）：inventory_count_type 決定輸入方式與要盤哪些原料；表頭另存當時的類型名稱與輸入方式。
 * 同一次送出的所有原料在同一筆交易；任何一項失敗就整批不記錄。
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class InventoryCountService {
    /** 一次最多查 31 天，避免一次撈出太多資料。 */
    static final int MAX_RANGE_DAYS = 31;
    private static final String PENDING = "[待店長審核]";
    private static final String APPROVED = "[店長已確認]";

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
        // 盤點類型：要存在且啟用中；只能盤類型裡的原料
        var type = findType(request.typeId())
            .orElseThrow(() -> ApiException.bad("找不到這個盤點類型"));
        if (!type.active()) throw ApiException.conflict("「" + type.name() + "」已停用，請重新整理後選擇其他盤點類型");
        var materials = new HashMap<Long, Map<String, Object>>();
        for (var r : repo.rows("SELECT * FROM inventory")) materials.put(PosRepository.number(r, "inventory_id"), r);
        var included = type.allItems() ? materials.keySet() : new HashSet<>(type.itemIds());
        for (var line : request.lines()) {
            var m = materials.get(line.inventoryId());
            if (m == null) throw ApiException.missing("查無原料編號 " + line.inventoryId());
            String name = m.get("name").toString();
            // 盤點一律整數：數包裝或數得出的個數，開封、用到一半的不算（95.00 視為 95）
            if (line.countedQty().stripTrailingZeros().scale() > 0)
                throw ApiException.bad("「" + name + "」實點數量只能填整數（開封、用到一半的不算）");
            if (!included.contains(line.inventoryId())) throw ApiException.bad("「" + name + "」不在「" + type.name() + "」要盤的原料裡");
            // 只填箱數的類型：有包裝單位的原料，數量一定是每箱數量的整數倍
            BigDecimal perPack = decimal(m, "per_pack");
            if (type.inputMode() == InputMode.PACK_ONLY && m.get("pack_unit") != null && perPack != null && perPack.signum() > 0
                    && line.countedQty().remainder(perPack).signum() != 0)
                throw ApiException.bad("「" + type.name() + "」只填箱數：「" + name + "」要是 " + perPack.stripTrailingZeros().toPlainString() + " 的倍數");
        }
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        var staffRows = repo.rows("SELECT * FROM staff_account WHERE staff_id=?", staffId);
        boolean isManager = !staffRows.isEmpty() && ("MANAGER".equals(staffRows.getFirst().get("level")) || "HQ".equals(staffRows.getFirst().get("level")));
        note = (note == null ? "" : note + " ") + (isManager ? APPROVED : PENDING);
        LocalDateTime now = LocalDateTime.now(clock);
        // 表頭：一次盤點一列（盤點人、時間、備註只存一次）
        long countId;
        try {
            countId = repo.insert("count_id", """
                INSERT INTO inventory_count(request_id,counted_by,counted_at,note,type_id,type_name,input_mode)
                VALUES (?,?,?,?,?,?,?)
                """, requestId, staffId, now, note, type.typeId(), type.name(), type.inputMode().name());
        } catch (org.springframework.dao.DuplicateKeyException sameRequestInProgress) {
            // 同一筆重送剛好同時送達：另一個請求正在寫入
            throw ApiException.conflict("這次盤點正在處理中，請稍後重新整理確認");
        }
        // 明細：依編號順序鎖原料，避免兩台同時盤點時互相等待（死結）
        for (var line : request.lines().stream().sorted(Comparator.comparing(Line::inventoryId)).toList()) {
            var inventory = inventories.findLocked(line.inventoryId())
                .orElseThrow(() -> ApiException.missing("查無原料編號 " + line.inventoryId()));
            BigDecimal counted = line.countedQty().setScale(2, RoundingMode.UNNECESSARY);
            BigDecimal book = inventory.getBookQty().setScale(2, RoundingMode.HALF_UP);
            repo.update("""
                INSERT INTO inventory_count_line(count_id,inventory_id,counted_qty,book_qty,diff_qty,unit_cost)
                VALUES (?,?,?,?,?,?)
                """, countId, line.inventoryId(), counted, book, counted.subtract(book), inventory.getUnitCost());
            // 盤點後：最新一次盤點寫回原料主檔的 reported_* 欄位
            inventory.setReportedQty(counted); inventory.setReportedBy(staffId); inventory.setReportedAt(now);
            // 只有店長送出才直接更新帳面；員工送出時保留原帳面，待店長審核確認後才更新
            if (isManager) {
                inventory.setBookQty(counted);
            }
            inventories.save(inventory);
        }
        return rowsOf("WHERE c.request_id=?", requestId);
    }

    // ---- 盤點類型 ----

    /** 盤點類型清單；includeInactive = false 只列啟用中的（盤點時用），true 連停用的一起列（店長管理用）。 */
    @Transactional(readOnly = true)
    public List<CountType> types(boolean includeInactive) {
        var items = new HashMap<Long, List<Long>>();
        for (var r : repo.rows("SELECT * FROM inventory_count_type_item ORDER BY inventory_id"))
            items.computeIfAbsent(PosRepository.number(r, "type_id"), k -> new ArrayList<>()).add(PosRepository.number(r, "inventory_id"));
        var out = new ArrayList<CountType>();
        for (var r : repo.rows("SELECT t.*, s.name AS staff_name FROM inventory_count_type t "
                + "LEFT JOIN staff_account s ON s.staff_id=t.updated_by "
                + (includeInactive ? "" : "WHERE t.active=TRUE ") + "ORDER BY t.type_id"))
            out.add(type(r, items.getOrDefault(PosRepository.number(r, "type_id"), List.of())));
        return out;
    }

    public CountType createType(SaveType request, long staffId) {
        var itemIds = checkType(request);
        long id;
        try {
            id = repo.insert("type_id",
                "INSERT INTO inventory_count_type(name,input_mode,all_items,active,updated_by,updated_at) VALUES (?,?,?,?,?,?)",
                request.name().trim(), request.inputMode().name(), request.allItems(),
                request.active() == null || request.active(), staffId, LocalDateTime.now(clock));
        } catch (org.springframework.dao.DuplicateKeyException sameName) {
            throw ApiException.conflict("已經有叫「" + request.name().trim() + "」的盤點類型");
        }
        saveItems(id, itemIds);
        return findType(id).orElseThrow();
    }

    public CountType updateType(long typeId, SaveType request, long staffId) {
        var itemIds = checkType(request);
        var current = findType(typeId).orElseThrow(() -> ApiException.missing("找不到這個盤點類型"));
        try {
            repo.update("UPDATE inventory_count_type SET name=?, input_mode=?, all_items=?, active=?, updated_by=?, updated_at=? WHERE type_id=?",
                request.name().trim(), request.inputMode().name(), request.allItems(),
                request.active() == null ? current.active() : request.active(), staffId, LocalDateTime.now(clock), typeId);
        } catch (org.springframework.dao.DuplicateKeyException sameName) {
            throw ApiException.conflict("已經有叫「" + request.name().trim() + "」的盤點類型");
        }
        repo.update("DELETE FROM inventory_count_type_item WHERE type_id=?", typeId);
        saveItems(typeId, itemIds);
        return findType(typeId).orElseThrow();
    }

    /** 檢查類型內容，回傳要存的原料編號（全部原料時為空）。 */
    private List<Long> checkType(SaveType request) {
        if (Boolean.TRUE.equals(request.allItems())) return List.of();
        var ids = request.itemIds() == null ? List.<Long>of() : request.itemIds().stream().distinct().sorted().toList();
        if (ids.isEmpty()) throw ApiException.bad("請至少選一項要盤的原料，或勾選「全部原料」");
        var known = new HashSet<Long>();
        for (var r : repo.rows("SELECT inventory_id FROM inventory")) known.add(PosRepository.number(r, "inventory_id"));
        for (long id : ids) if (!known.contains(id)) throw ApiException.bad("查無原料編號 " + id);
        return ids;
    }
    private void saveItems(long typeId, List<Long> itemIds) {
        for (long id : itemIds) repo.update("INSERT INTO inventory_count_type_item(type_id,inventory_id) VALUES (?,?)", typeId, id);
    }
    private Optional<CountType> findType(long typeId) {
        var rows = repo.rows("SELECT t.*, s.name AS staff_name FROM inventory_count_type t "
            + "LEFT JOIN staff_account s ON s.staff_id=t.updated_by WHERE t.type_id=?", typeId);
        if (rows.isEmpty()) return Optional.empty();
        var items = repo.rows("SELECT inventory_id FROM inventory_count_type_item WHERE type_id=? ORDER BY inventory_id", typeId)
            .stream().map(r -> PosRepository.number(r, "inventory_id")).toList();
        return Optional.of(type(rows.getFirst(), items));
    }
    private static CountType type(Map<String, Object> r, List<Long> itemIds) {
        boolean all = PosRepository.flag(r, "all_items");
        return new CountType(PosRepository.number(r, "type_id"), r.get("name").toString(),
            InputMode.valueOf(r.get("input_mode").toString()), all, PosRepository.flag(r, "active"),
            all ? List.of() : itemIds, timestamp(r.get("updated_at")), (String) r.get("staff_name"));
    }

    /** 盤點頁的原料清單（含物料分類與是否允許小數）。 */
    @Transactional(readOnly = true)
    public List<Item> items() {
        var out = new ArrayList<Item>();
        for (var r : repo.rows("SELECT * FROM inventory ORDER BY inventory_id"))
            out.add(new Item(PosRepository.number(r, "inventory_id"), r.get("name").toString(), r.get("base_unit").toString(),
                (String) r.get("pack_unit"), decimal(r, "per_pack"), decimal(r, "book_qty"), decimal(r, "safety_stock"),
                decimal(r, "unit_cost"),
                r.containsKey("display_by_pack") && PosRepository.flag(r, "display_by_pack"),
                (String) r.get("category")));
        return out;
    }

    /** 店長審核確認盤點紀錄：將實點數量更新為帳面數量 (book_qty)。 */
    public Map<String, Object> approveCount(long countId, long staffId) {
        requireEnabled();
        var staffRows = repo.rows("SELECT * FROM staff_account WHERE staff_id=?", staffId);
        boolean isManager = !staffRows.isEmpty() && ("MANAGER".equals(staffRows.getFirst().get("level")) || "HQ".equals(staffRows.getFirst().get("level")));
        if (!isManager) {
            throw ApiException.forbidden("只限店長或總部審核盤點紀錄");
        }
        // 同一筆盤點只審核一次。另一個請求取得表頭鎖時會先看到已確認的狀態。
        var countRows = repo.rows("SELECT note FROM inventory_count WHERE count_id=? FOR UPDATE", countId);
        if (countRows.isEmpty()) {
            throw ApiException.missing("查無此盤點紀錄");
        }
        String note = Objects.toString(countRows.getFirst().get("note"), "").stripTrailing();
        if (note.endsWith(APPROVED) || note.endsWith("[店長已審核確認]") || note.endsWith("[半成品盤點-店長已確認扣庫]")) {
            return Map.of("success", true, "message", "這筆盤點已確認");
        }
        if (!note.endsWith(PENDING)) {
            throw ApiException.conflict("這筆盤點不在待審核狀態");
        }
        var lines = repo.rows("""
                SELECT inventory_id, counted_qty, book_qty
                FROM inventory_count_line WHERE count_id=? ORDER BY inventory_id
                """, countId);
        if (lines.isEmpty()) {
            throw ApiException.conflict("這筆盤點沒有原料明細");
        }
        for (var line : lines) {
            long invId = PosRepository.number(line, "inventory_id");
            var inventory = inventories.findLocked(invId)
                    .orElseThrow(() -> ApiException.conflict("盤點原料已變更，請重新盤點"));
            // 送出盤點和手動校正也會鎖原料。在鎖內核對最新盤點和帳面快照。
            var latest = repo.one("""
                    SELECT c.count_id FROM inventory_count_line l
                    JOIN inventory_count c ON c.count_id=l.count_id
                    WHERE l.inventory_id=?
                    ORDER BY c.counted_at DESC, c.count_id DESC LIMIT 1
                    """, invId);
            if (PosRepository.number(latest, "count_id") != countId
                    || inventory.getBookQty().compareTo(decimal(line, "book_qty")) != 0) {
                throw ApiException.conflict("「" + inventory.getName() + "」已有較新的盤點或帳面異動，請核對最新紀錄");
            }
            inventory.setBookQty(decimal(line, "counted_qty"));
        }
        repo.update("UPDATE inventory_count SET note=? WHERE count_id=?",
                note.substring(0, note.length() - PENDING.length()) + APPROVED, countId);

        return Map.of("success", true, "message", "店長已審核確認，帳面庫存已依盤點實點數量完成更新");
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
            "盤點紀錄尚未啟用，請先由資料庫負責人確認 016、017 升級");
    }

    private List<Row> rowsOf(String where, Object... args) {
        var out = new ArrayList<Row>();
        for (var r : repo.rows("""
            SELECT c.count_id, c.request_id, c.counted_by, c.counted_at, c.note, c.type_id, c.type_name, c.input_mode,
                   l.inventory_id, l.counted_qty, l.book_qty, l.diff_qty, l.unit_cost,
                   i.name AS inventory_name, i.base_unit, s.name AS staff_name
            FROM inventory_count c JOIN inventory_count_line l ON l.count_id=c.count_id
            JOIN inventory i ON i.inventory_id=l.inventory_id
            JOIN staff_account s ON s.staff_id=c.counted_by
            """ + where + " ORDER BY c.counted_at DESC, c.count_id DESC, l.inventory_id", args)) {
            BigDecimal diff = decimal(r, "diff_qty"), unitCost = decimal(r, "unit_cost");
            out.add(new Row(PosRepository.number(r, "count_id"), r.get("request_id").toString(),
                PosRepository.number(r, "type_id"), r.get("type_name").toString(), InputMode.valueOf(r.get("input_mode").toString()),
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
