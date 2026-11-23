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
 * 原料盤點：記錄實點數量與當時的帳面數量、差異，並把帳面庫存（book_qty）改成實點數量。
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

    private final PosRepository repo;
    private final InventoryRepository inventories;
    private final Clock clock;
    private final boolean enabled;

    // 雲端或本機若無法執行 DDL 時的記憶體備援儲存，確保半成品多次製作紀錄與半成品報廢紀錄穩定運作
    private volatile Boolean prepTablesReady = null;
    private final java.util.concurrent.atomic.AtomicLong fallbackLogSeq = new java.util.concurrent.atomic.AtomicLong(1);
    private final List<FallbackProdLog> fallbackProdLogs = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<FallbackWastageLog> fallbackWastageLogs = new java.util.concurrent.CopyOnWriteArrayList<>();

    private record FallbackProdLog(long logId, LocalDate businessDate, long prepId, BigDecimal producedQty,
                                   long staffId, String staffName, LocalDateTime createdAt, String note) {}
    private record FallbackWastageLog(long logId, LocalDate businessDate, long prepId, BigDecimal wastedQty,
                                      long staffId, String staffName, LocalDateTime createdAt, String note) {}

    public InventoryCountService(PosRepository repo, InventoryRepository inventories, Clock clock,
                                 @Value("${pos.inventory-count.enabled:false}") boolean enabled) {
        this.repo = repo; this.inventories = inventories; this.clock = clock; this.enabled = enabled;
    }

    private boolean ensurePrepLogTables() {
        Boolean ready = this.prepTablesReady;
        if (ready != null) return ready;
        synchronized (this) {
            if (this.prepTablesReady != null) return this.prepTablesReady;
            try {
                repo.update("""
                    CREATE TABLE IF NOT EXISTS prep_production_log (
                        log_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        business_date DATE NOT NULL,
                        prep_id BIGINT NOT NULL,
                        produced_qty DECIMAL(10,2) NOT NULL,
                        staff_id BIGINT NOT NULL,
                        created_at DATETIME(6) NOT NULL,
                        note VARCHAR(255) NULL,
                        INDEX idx_prep_prod_date (business_date, prep_id)
                    ) ENGINE=InnoDB
                    """);
                repo.update("""
                    CREATE TABLE IF NOT EXISTS prep_wastage_log (
                        log_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        business_date DATE NOT NULL,
                        prep_id BIGINT NOT NULL,
                        wasted_qty DECIMAL(10,2) NOT NULL,
                        staff_id BIGINT NOT NULL,
                        created_at DATETIME(6) NOT NULL,
                        note VARCHAR(255) NULL,
                        INDEX idx_prep_waste_date (business_date, prep_id)
                    ) ENGINE=InnoDB
                    """);
                this.prepTablesReady = true;
            } catch (Exception ex) {
                this.prepTablesReady = false;
            }
            return this.prepTablesReady;
        }
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
        if (!isManager) {
            note = (note == null ? "" : note + " ") + "[待店長審核]";
        } else {
            note = (note == null ? "" : note + " ") + "[店長已確認]";
        }
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
        var staffRows = repo.rows("SELECT * FROM staff_account WHERE staff_id=?", staffId);
        boolean isManager = !staffRows.isEmpty() && ("MANAGER".equals(staffRows.getFirst().get("level")) || "HQ".equals(staffRows.getFirst().get("level")));
        if (!isManager) {
            throw ApiException.forbidden("只限店長或總部審核盤點紀錄");
        }
        var countRows = repo.rows("SELECT * FROM inventory_count WHERE count_id=?", countId);
        if (countRows.isEmpty()) {
            throw ApiException.missing("查無此盤點紀錄");
        }
        var lines = repo.rows("SELECT inventory_id, counted_qty FROM inventory_count_line WHERE count_id=?", countId);
        for (var line : lines) {
            long invId = PosRepository.number(line, "inventory_id");
            BigDecimal counted = decimal(line, "counted_qty");
            inventories.findLocked(invId).ifPresent(inv -> {
                inv.setBookQty(counted);
                inventories.save(inv);
            });
        }
        String currentNote = (String) countRows.getFirst().get("note");
        String updatedNote = (currentNote == null ? "" : currentNote.replace("[半成品盤點待店長審核]", "").replace("[待店長審核]", "").trim() + " ") + "[店長已審核確認]";
        repo.update("UPDATE inventory_count SET note=? WHERE count_id=?", updatedNote.trim(), countId);

        return Map.of("success", true, "message", "店長已審核確認，帳面庫存已依盤點實點數量完成更新");
    }

    /**
     * 取得半成品備料盤點清單：
     * 1. 今日各次製作時間紀錄（可同品項一日新增 2、3 次）與今日累計製作量
     * 2. 各半成品對應之底層原料配方（1 份半成品應使用多少底層原料）
     * 3. 今日訂單消耗量（product_recipe + topping）
     * 4. 今日半成品報廢量與紀錄（與生原料出問題的 raw_material_wastage 分開）
     * 5. 目前剩餘半成品量 = MAX(0, 今日累計製作量 - 今日訂單消耗量 - 今日半成品報廢量)
     */
    public List<PrepCountItem> prepItems(LocalDate date) {
        LocalDate targetDate = date != null ? date : LocalDate.now(clock);
        boolean useDbTables = ensurePrepLogTables();

        // 1. 今日訂單半成品消耗（product_recipe + topping）
        Map<Long, BigDecimal> orderConsumedMap = new HashMap<>();
        var recipeOrderRows = repo.rows("""
            SELECT pr.prep_id, SUM(oi.qty * COALESCE(pr.portions, 1)) AS qty
            FROM pos_order o
            JOIN order_item oi ON oi.order_id = o.order_id
            JOIN product_recipe pr ON pr.product_id = oi.product_id
            WHERE o.business_date = ? AND o.status != 'CANCELLED' AND oi.status != 'CANCELLED'
              AND pr.prep_id IS NOT NULL
            GROUP BY pr.prep_id
            """, targetDate);
        for (var r : recipeOrderRows) {
            orderConsumedMap.put(PosRepository.number(r, "prep_id"), decimal(r, "qty"));
        }

        var toppingOrderRows = repo.rows("""
            SELECT t.prep_id, SUM(oi.qty * COALESCE(oit.qty, 1)) AS qty
            FROM pos_order o
            JOIN order_item oi ON oi.order_id = o.order_id
            JOIN order_item_topping oit ON oit.item_id = oi.item_id
            JOIN topping t ON t.topping_id = oit.topping_id
            WHERE o.business_date = ? AND o.status != 'CANCELLED' AND oi.status != 'CANCELLED'
              AND t.prep_id IS NOT NULL
            GROUP BY t.prep_id
            """, targetDate);
        for (var r : toppingOrderRows) {
            long pId = PosRepository.number(r, "prep_id");
            orderConsumedMap.merge(pId, decimal(r, "qty"), BigDecimal::add);
        }

        // 2. 一次批次載入所有半成品的底層原料（避免 N+1 查詢，並相容 021/026 的 prep_item_ingredient 結構）
        Map<Long, List<PrepIngredientDto>> prepIngredientsMap = loadAllPrepIngredients();

        // 3. 取得所有半成品主檔
        var prepRows = repo.rows("SELECT prep_id, name, steps, category FROM prep_item ORDER BY prep_id");
        Map<Long, String> prepNameMap = new HashMap<>();
        for (var p : prepRows) {
            prepNameMap.put(PosRepository.number(p, "prep_id"), (String) p.get("name"));
        }

        // 4. 載入今日各次製作時間紀錄（prep_production_log）
        Map<Long, List<PrepProductionLogDto>> prodLogsByPrep = new HashMap<>();
        Map<Long, BigDecimal> prodTotalByPrep = new HashMap<>();
        if (useDbTables) {
            var prodRows = repo.rows("""
                SELECT l.log_id, l.prep_id, l.produced_qty, l.staff_id, l.created_at, l.note,
                       s.name AS staff_name
                FROM prep_production_log l
                LEFT JOIN staff_account s ON s.staff_id = l.staff_id
                WHERE l.business_date = ?
                ORDER BY l.created_at ASC, l.log_id ASC
                """, targetDate);
            for (var r : prodRows) {
                long prepId = PosRepository.number(r, "prep_id");
                BigDecimal qty = decimal(r, "produced_qty").setScale(2, RoundingMode.HALF_UP);
                List<PrepIngredientDto> baseIngs = prepIngredientsMap.getOrDefault(prepId, List.of());
                List<PrepIngredientDto> usedIngs = scaleIngredients(baseIngs, qty);
                PrepProductionLogDto dto = new PrepProductionLogDto(
                    PosRepository.number(r, "log_id"),
                    prepId,
                    prepNameMap.getOrDefault(prepId, "半成品"),
                    qty,
                    "份",
                    usedIngs,
                    PosRepository.number(r, "staff_id"),
                    Objects.toString(r.get("staff_name"), "門市人員"),
                    timestamp(r.get("created_at")),
                    (String) r.get("note")
                );
                prodLogsByPrep.computeIfAbsent(prepId, k -> new ArrayList<>()).add(dto);
                prodTotalByPrep.merge(prepId, qty, BigDecimal::add);
            }
        } else {
            for (var f : fallbackProdLogs) {
                if (!targetDate.equals(f.businessDate())) continue;
                List<PrepIngredientDto> baseIngs = prepIngredientsMap.getOrDefault(f.prepId(), List.of());
                List<PrepIngredientDto> usedIngs = scaleIngredients(baseIngs, f.producedQty());
                PrepProductionLogDto dto = new PrepProductionLogDto(
                    f.logId(), f.prepId(), prepNameMap.getOrDefault(f.prepId(), "半成品"),
                    f.producedQty(), "份", usedIngs, f.staffId(), f.staffName(), f.createdAt(), f.note()
                );
                prodLogsByPrep.computeIfAbsent(f.prepId(), k -> new ArrayList<>()).add(dto);
                prodTotalByPrep.merge(f.prepId(), f.producedQty(), BigDecimal::add);
            }
        }

        // 5. 載入今日半成品報廢紀錄（prep_wastage_log）
        Map<Long, List<PrepWastageLogDto>> wasteLogsByPrep = new HashMap<>();
        Map<Long, BigDecimal> wasteTotalByPrep = new HashMap<>();
        if (useDbTables) {
            var wasteRows = repo.rows("""
                SELECT l.log_id, l.prep_id, l.wasted_qty, l.staff_id, l.created_at, l.note,
                       s.name AS staff_name
                FROM prep_wastage_log l
                LEFT JOIN staff_account s ON s.staff_id = l.staff_id
                WHERE l.business_date = ?
                ORDER BY l.created_at ASC, l.log_id ASC
                """, targetDate);
            for (var r : wasteRows) {
                long prepId = PosRepository.number(r, "prep_id");
                BigDecimal qty = decimal(r, "wasted_qty").setScale(2, RoundingMode.HALF_UP);
                PrepWastageLogDto dto = new PrepWastageLogDto(
                    PosRepository.number(r, "log_id"),
                    prepId,
                    prepNameMap.getOrDefault(prepId, "半成品"),
                    qty,
                    "份",
                    PosRepository.number(r, "staff_id"),
                    Objects.toString(r.get("staff_name"), "門市人員"),
                    timestamp(r.get("created_at")),
                    (String) r.get("note")
                );
                wasteLogsByPrep.computeIfAbsent(prepId, k -> new ArrayList<>()).add(dto);
                wasteTotalByPrep.merge(prepId, qty, BigDecimal::add);
            }
        } else {
            for (var f : fallbackWastageLogs) {
                if (!targetDate.equals(f.businessDate())) continue;
                PrepWastageLogDto dto = new PrepWastageLogDto(
                    f.logId(), f.prepId(), prepNameMap.getOrDefault(f.prepId(), "半成品"),
                    f.wastedQty(), "份", f.staffId(), f.staffName(), f.createdAt(), f.note()
                );
                wasteLogsByPrep.computeIfAbsent(f.prepId(), k -> new ArrayList<>()).add(dto);
                wasteTotalByPrep.merge(f.prepId(), f.wastedQty(), BigDecimal::add);
            }
        }

        // 6. 組裝每個半成品的統計與時間紀錄
        List<PrepCountItem> out = new ArrayList<>();
        for (var p : prepRows) {
            long prepId = PosRepository.number(p, "prep_id");
            String name = (String) p.get("name");
            String cat = (String) p.get("category");
            BigDecimal todayProduced = prodTotalByPrep.getOrDefault(prepId, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            BigDecimal orderConsumed = orderConsumedMap.getOrDefault(prepId, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            BigDecimal wasted = wasteTotalByPrep.getOrDefault(prepId, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

            // 目前剩餘半成品量 = 今日累計製作量 - 今日訂單消耗 - 今日已報廢半成品量
            BigDecimal remainingQty = todayProduced.subtract(orderConsumed).subtract(wasted).setScale(2, RoundingMode.HALF_UP);

            List<PrepIngredientDto> ingDtos = prepIngredientsMap.getOrDefault(prepId, List.of());
            List<PrepProductionLogDto> pLogs = prodLogsByPrep.getOrDefault(prepId, List.of());
            List<PrepWastageLogDto> wLogs = wasteLogsByPrep.getOrDefault(prepId, List.of());

            out.add(new PrepCountItem(
                prepId,
                name,
                cat != null && !cat.isBlank() ? cat : "半成品",
                "份",
                todayProduced,
                orderConsumed,
                wasted,
                remainingQty,
                ingDtos,
                pLogs,
                wLogs
            ));
        }

        return out;
    }

    /**
     * 批次讀取所有半成品直接對應的組成材料（直接顯示原料與子半成品，例如：綠茶凍 = 茶凍粉 0.3包 + 綠茶 0.5份）。
     */
    private Map<Long, List<PrepIngredientDto>> loadAllPrepIngredients() {
        Map<Long, List<PrepIngredientDto>> map = new HashMap<>();
        var rows = repo.rows("""
            SELECT g.prep_id, g.inventory_id, g.sub_prep_id, COALESCE(g.qty, 1) AS qty,
                   i.name AS inv_name, i.base_unit AS inv_unit,
                   sp.name AS sub_prep_name
            FROM prep_item_ingredient g
            LEFT JOIN inventory i ON i.inventory_id = g.inventory_id
            LEFT JOIN prep_item sp ON sp.prep_id = g.sub_prep_id
            ORDER BY g.prep_id, g.line_id
            """);
        for (var r : rows) {
            long prepId = PosRepository.number(r, "prep_id");
            BigDecimal qty = decimal(r, "qty");
            if (qty == null) qty = BigDecimal.ONE;
            qty = qty.setScale(3, RoundingMode.HALF_UP);

            if (r.get("inventory_id") != null) {
                long invId = PosRepository.number(r, "inventory_id");
                String name = Objects.toString(r.get("inv_name"), "原料");
                String unit = Objects.toString(r.get("inv_unit"), "份");
                map.computeIfAbsent(prepId, k -> new ArrayList<>())
                   .add(new PrepIngredientDto(invId, name, qty, unit));
            } else if (r.get("sub_prep_id") != null) {
                long subPrepId = PosRepository.number(r, "sub_prep_id");
                String name = Objects.toString(r.get("sub_prep_name"), "半成品");
                map.computeIfAbsent(prepId, k -> new ArrayList<>())
                   .add(new PrepIngredientDto(100000L + subPrepId, name, qty, "份"));
            }
        }
        return map;
    }

    private List<PrepIngredientDto> scaleIngredients(List<PrepIngredientDto> base, BigDecimal factor) {
        List<PrepIngredientDto> out = new ArrayList<>(base.size());
        for (var ing : base) {
            out.add(new PrepIngredientDto(
                ing.inventoryId(),
                ing.name(),
                ing.qty().multiply(factor).setScale(3, RoundingMode.HALF_UP),
                ing.unit()
            ));
        }
        return out;
    }

    /** 遞迴解析半成品的底層原始原料 (支援 prep_item_ingredient 的 qty 與 sub_prep_id) */
    public Map<Long, BigDecimal> resolveRawIngredients(long prepId, BigDecimal factor) {
        Map<Long, BigDecimal> result = new HashMap<>();
        resolveRawIngredientsHelper(prepId, factor, result, new HashSet<>());
        return result;
    }

    private void resolveRawIngredientsHelper(long prepId, BigDecimal factor, Map<Long, BigDecimal> result, Set<Long> visited) {
        if (!visited.add(prepId)) return;
        var rows = repo.rows("SELECT inventory_id, sub_prep_id, qty FROM prep_item_ingredient WHERE prep_id=?", prepId);
        for (var r : rows) {
            BigDecimal qty = decimal(r, "qty");
            if (qty == null) qty = BigDecimal.ONE;
            BigDecimal total = qty.multiply(factor);
            Object invObj = r.get("inventory_id");
            Object subObj = r.get("sub_prep_id");
            if (invObj != null) {
                long invId = PosRepository.number(r, "inventory_id");
                result.merge(invId, total, BigDecimal::add);
            } else if (subObj != null) {
                long subPrepId = PosRepository.number(r, "sub_prep_id");
                resolveRawIngredientsHelper(subPrepId, total, result, visited);
            }
        }
    }

    /**
     * 新增今日半成品製作量（煮了多少茶、多少料）：
     * 支援同一個半成品一天內進行 2、3 次（多次）新增，每次記錄時間與登記人員。
     */
    public Map<String, Object> recordPrepProduction(PrepProductionSubmit request, long staffId) {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        boolean useDbTables = ensurePrepLogTables();

        String staffName = "門市人員";
        var staffRows = repo.rows("SELECT name FROM staff_account WHERE staff_id=?", staffId);
        if (!staffRows.isEmpty() && staffRows.getFirst().get("name") != null) {
            staffName = staffRows.getFirst().get("name").toString();
        }

        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        int count = 0;

        for (var line : request.lines()) {
            BigDecimal qty = line.producedQty().setScale(2, RoundingMode.HALF_UP);
            if (qty.signum() <= 0) continue;
            if (useDbTables) {
                repo.insert("log_id", """
                    INSERT INTO prep_production_log(business_date, prep_id, produced_qty, staff_id, created_at, note)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, today, line.prepId(), qty, staffId, now, note);
            } else {
                long id = fallbackLogSeq.getAndIncrement();
                fallbackProdLogs.add(new FallbackProdLog(id, today, line.prepId(), qty, staffId, staffName, now, note));
            }
            count++;
        }

        List<PrepCountItem> updatedItems = prepItems(today);
        return Map.of(
            "success", true,
            "recordedCount", count,
            "items", updatedItems,
            "message", "已新增 " + count + " 項半成品今日製作紀錄（含時間與底層原料換算）"
        );
    }

    /**
     * 將剩餘半成品記錄為半成品報廢量（與「原料報廢回報」生原料出問題分開記錄）。
     */
    public Map<String, Object> recordPrepWastage(PrepWastageSubmit request, long staffId) {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        boolean useDbTables = ensurePrepLogTables();

        String staffName = "門市人員";
        var staffRows = repo.rows("SELECT name FROM staff_account WHERE staff_id=?", staffId);
        if (!staffRows.isEmpty() && staffRows.getFirst().get("name") != null) {
            staffName = staffRows.getFirst().get("name").toString();
        }

        String note = request.note() == null || request.note().isBlank() ? "剩餘半成品報廢" : request.note().trim();
        int count = 0;

        for (var line : request.lines()) {
            BigDecimal qty = line.wastedQty().setScale(2, RoundingMode.HALF_UP);
            if (qty.signum() <= 0) continue;
            if (useDbTables) {
                repo.insert("log_id", """
                    INSERT INTO prep_wastage_log(business_date, prep_id, wasted_qty, staff_id, created_at, note)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, today, line.prepId(), qty, staffId, now, note);
            } else {
                long id = fallbackLogSeq.getAndIncrement();
                fallbackWastageLogs.add(new FallbackWastageLog(id, today, line.prepId(), qty, staffId, staffName, now, note));
            }
            count++;
        }

        List<PrepCountItem> updatedItems = prepItems(today);
        return Map.of(
            "success", true,
            "recordedCount", count,
            "items", updatedItems,
            "message", "已記錄 " + count + " 項剩餘半成品報廢量（獨立於生原料報廢回報）"
        );
    }

    /**
     * 送出半成品盤點：
     * 差距 = 理論的量 - 實際員工盤點的量
     * 員工送出：標記 [半成品盤點待店長審核]，不扣減原料
     * 店長送出：標記 [半成品盤點-店長已確認扣庫]，自動依照配方扣除底層原料 inventory.book_qty
     */
    public Map<String, Object> submitPrepCount(PrepCountSubmit request, long staffId) {
        requireEnabled();
        var staffRows = repo.rows("SELECT * FROM staff_account WHERE staff_id=?", staffId);
        boolean isManager = !staffRows.isEmpty() && ("MANAGER".equals(staffRows.getFirst().get("level")) || "HQ".equals(staffRows.getFirst().get("level")));

        LocalDate today = LocalDate.now(clock);
        List<PrepCountItem> allItems = prepItems(today);
        Map<Long, PrepCountItem> itemMap = new HashMap<>();
        for (var it : allItems) itemMap.put(it.prepId(), it);

        List<PrepCountResult> results = new ArrayList<>();
        Map<Long, BigDecimal> totalRawDeductions = new HashMap<>(); // inventory_id -> to deduct

        for (var line : request.lines()) {
            PrepCountItem base = itemMap.get(line.prepId());
            if (base == null) continue;
            BigDecimal currentQty = line.currentQty().setScale(2, RoundingMode.HALF_UP);
            BigDecimal countedQty = line.countedQty().setScale(2, RoundingMode.HALF_UP);
            BigDecimal orderConsumed = base.orderConsumed();
            BigDecimal wasted = base.wastedQty();

            // 理論的量 = 目前數量 - 今日訂單消耗 - 今日報廢
            BigDecimal theoreticalQty = currentQty.subtract(orderConsumed).subtract(wasted).setScale(2, RoundingMode.HALF_UP);
            // 差距 = 理論的量 - 實際員工盤點的量 (正數表示短少、負數表示盤盈)
            BigDecimal diffQty = theoreticalQty.subtract(countedQty).setScale(2, RoundingMode.HALF_UP);

            results.add(new PrepCountResult(
                base.prepId(), base.name(), base.category(), base.unit(),
                currentQty, orderConsumed, wasted, theoreticalQty, countedQty, diffQty
            ));

            // 計算耗用量：實際消耗 = 目前數量 - 實點剩餘數量
            BigDecimal usedPrep = currentQty.subtract(countedQty);
            if (usedPrep.signum() > 0) {
                Map<Long, BigDecimal> raws = resolveRawIngredients(base.prepId(), usedPrep);
                for (var e : raws.entrySet()) {
                    totalRawDeductions.merge(e.getKey(), e.getValue(), BigDecimal::add);
                }
            }
        }

        String note = request.note() == null || request.note().isBlank() ? "" : request.note().trim();
        if (!isManager) {
            note = "[半成品盤點待店長審核] " + note;
        } else {
            note = "[半成品盤點-店長已確認扣庫] " + note;
        }
        if (note.length() > 255) note = note.substring(0, 255);

        // 取得有效 type_id
        long typeId = 1;
        String typeName = "半成品備料盤點";
        var typeRows = repo.rows("SELECT type_id, name FROM inventory_count_type WHERE active=TRUE LIMIT 1");
        if (!typeRows.isEmpty()) {
            typeId = PosRepository.number(typeRows.getFirst(), "type_id");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        long countId = repo.insert("count_id", """
            INSERT INTO inventory_count(request_id, counted_by, counted_at, note, type_id, type_name, input_mode)
            VALUES (?, ?, ?, ?, ?, ?, 'PACK_AND_LOOSE')
            """, request.requestId().toString(), staffId, now, note, typeId, typeName);

        // 為每項底層原料建立 count_line 記錄 (保證有對應 inventory_id，符合外鍵約束)
        for (var entry : totalRawDeductions.entrySet()) {
            long invId = entry.getKey();
            BigDecimal deductQty = entry.getValue().setScale(2, RoundingMode.HALF_UP);
            var invOpt = inventories.findLocked(invId);
            if (invOpt.isPresent()) {
                var inv = invOpt.get();
                BigDecimal book = inv.getBookQty().setScale(2, RoundingMode.HALF_UP);
                BigDecimal newBook = book.subtract(deductQty);
                if (newBook.signum() < 0) newBook = BigDecimal.ZERO;

                repo.update("""
                    INSERT INTO inventory_count_line(count_id, inventory_id, counted_qty, book_qty, diff_qty, unit_cost)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, countId, invId, newBook, book, deductQty.negate(), inv.getUnitCost());

                if (isManager) {
                    inv.setBookQty(newBook);
                    inv.setReportedQty(newBook);
                    inv.setReportedBy(staffId);
                    inv.setReportedAt(now);
                    inventories.save(inv);
                }
            }
        }

        return Map.of(
            "countId", countId,
            "requestId", request.requestId().toString(),
            "isManager", isManager,
            "results", results,
            "message", isManager ? "店長已確認半成品盤點，並完成底層原料扣庫" : "員工半成品盤點已送出，待店長審核後方可扣庫"
        );
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
