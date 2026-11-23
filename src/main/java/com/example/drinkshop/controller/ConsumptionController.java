package com.example.drinkshop.controller;

import com.example.drinkshop.exception.ApiException;
import jakarta.validation.Valid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 物料月消耗統計與店長確認扣庫 API：
 * 原料（inventory）的消耗只在兩個地方記：賣出時飲品／加料直接用的原料，以及登記備料（050）時半成品材料裡的原料
 * （份數 × prep_item_ingredient.qty；材料是別的半成品不算，它的原料在做它時已經記過）。
 * 半成品（prep_item，編號 100000＋prep_id、單位「份」）的消耗由訂單計算，只看份數、不再換算成原料，和原料分開列。
 * 另外列出門市原料報廢紀錄（raw_material_wastage）。
 * 支援店長登入確認後執行庫存扣除。
 */
@RestController
@RequestMapping("/api/inventory-consumption")
public class ConsumptionController {

    private final JdbcTemplate jdbc;

    public ConsumptionController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> getMonthlyConsumption(
            @RequestParam(name = "year", defaultValue = "2026") int year,
            @RequestParam(name = "month", defaultValue = "11") int month) {

        // 舊單紙杯（historical_cups）：依當時扣料快照 deducted_inventory_json 計算，避免現行配方缺少杯材或重算。
        String sql = """
            WITH historical_cups AS (
                SELECT oi.item_id, o.business_date, i.inventory_id,
                       CAST(JSON_UNQUOTE(JSON_EXTRACT(oi.deducted_inventory_json,
                           CONCAT('$."', i.inventory_id, '"'))) AS DECIMAL(20,6)) AS qty
                FROM pos_order o
                JOIN order_item oi ON oi.order_id = o.order_id
                JOIN inventory i ON i.name LIKE '%紙杯%' AND JSON_CONTAINS_PATH(
                    oi.deducted_inventory_json, 'one', CONCAT('$."', i.inventory_id, '"'))
                WHERE o.status != 'CANCELLED' AND oi.status != 'CANCELLED'
                  AND YEAR(o.business_date) = ? AND MONTH(o.business_date) = ?
            )
            SELECT 
                DATE_FORMAT(c.business_date, '%Y-%m-%d') AS business_date,
                DAY(c.business_date) AS day_of_month,
                c.material_id AS inventory_id,
                c.material_name,
                c.unit,
                ROUND(SUM(c.qty), 2) AS total_consumed
            FROM (
                -- 1. 訂單商品半成品配方（prep_item，例如紅茶、綠茶、茶凍等半成品料）
                SELECT o.business_date,
                       (100000 + p.prep_id) AS material_id,
                       p.name AS material_name,
                       '份' AS unit,
                       SUM(oi.qty * COALESCE(pr.portions, 1)) AS qty
                FROM pos_order o
                JOIN order_item oi ON oi.order_id = o.order_id
                JOIN product_recipe pr ON pr.product_id = oi.product_id
                JOIN prep_item p ON p.prep_id = pr.prep_id
                WHERE o.status != 'CANCELLED' AND oi.status != 'CANCELLED'
                  AND pr.prep_id IS NOT NULL
                  AND YEAR(o.business_date) = ? AND MONTH(o.business_date) = ?
                GROUP BY o.business_date, p.prep_id, p.name

                UNION ALL

                -- 2. 訂單加料半成品消耗（topping -> prep_item，例如加料茶凍等）
                SELECT o.business_date,
                       (100000 + p.prep_id) AS material_id,
                       p.name AS material_name,
                       '份' AS unit,
                       SUM(oi.qty * COALESCE(oit.qty, 1)) AS qty
                FROM pos_order o
                JOIN order_item oi ON oi.order_id = o.order_id
                JOIN order_item_topping oit ON oit.item_id = oi.item_id
                JOIN topping t ON t.topping_id = oit.topping_id
                JOIN prep_item p ON p.prep_id = t.prep_id
                WHERE o.status != 'CANCELLED' AND oi.status != 'CANCELLED'
                  AND t.prep_id IS NOT NULL
                  AND YEAR(o.business_date) = ? AND MONTH(o.business_date) = ?
                GROUP BY o.business_date, p.prep_id, p.name

                UNION ALL

                -- 3. 訂單商品直配原料（product_recipe -> inventory，例如牛奶／鮮奶等直接入杯原料）
                SELECT o.business_date,
                       i.inventory_id AS material_id,
                       i.name AS material_name,
                       COALESCE(i.base_unit, '份') AS unit,
                       SUM(oi.qty * COALESCE(pr.portions, 1)) AS qty
                FROM pos_order o
                JOIN order_item oi ON oi.order_id = o.order_id
                JOIN product_recipe pr ON pr.product_id = oi.product_id
                JOIN inventory i ON i.inventory_id = pr.inventory_id
                WHERE o.status != 'CANCELLED' AND oi.status != 'CANCELLED'
                  AND pr.inventory_id IS NOT NULL
                  AND YEAR(o.business_date) = ? AND MONTH(o.business_date) = ?
                  AND NOT EXISTS (SELECT 1 FROM historical_cups h WHERE h.item_id = oi.item_id AND h.inventory_id = pr.inventory_id)
                GROUP BY o.business_date, i.inventory_id, i.name, i.base_unit

                UNION ALL

                -- 4. 訂單加料直配原料（topping -> inventory，同一種加兩份就算兩份，039）
                SELECT o.business_date,
                       i.inventory_id AS material_id,
                       i.name AS material_name,
                       COALESCE(i.base_unit, '份') AS unit,
                       SUM(oi.qty * COALESCE(oit.qty, 1)) AS qty
                FROM pos_order o
                JOIN order_item oi ON oi.order_id = o.order_id
                JOIN order_item_topping oit ON oit.item_id = oi.item_id
                JOIN topping t ON t.topping_id = oit.topping_id
                JOIN inventory i ON i.inventory_id = t.inventory_id
                WHERE o.status != 'CANCELLED' AND oi.status != 'CANCELLED'
                  AND t.inventory_id IS NOT NULL
                  AND YEAR(o.business_date) = ? AND MONTH(o.business_date) = ?
                GROUP BY o.business_date, i.inventory_id, i.name, i.base_unit

                UNION ALL

                -- 5. 登記備料（050）：做了幾份半成品 × 每份用的原料（只算原料，材料是別的半成品不算）
                SELECT b.business_date,
                       i.inventory_id AS material_id,
                       i.name AS material_name,
                       COALESCE(i.base_unit, '份') AS unit,
                       SUM(b.qty * g.qty) AS qty
                FROM prep_batch b
                JOIN prep_item_ingredient g ON g.prep_id = b.prep_id
                JOIN inventory i ON i.inventory_id = g.inventory_id
                WHERE YEAR(b.business_date) = ? AND MONTH(b.business_date) = ?
                GROUP BY b.business_date, i.inventory_id, i.name, i.base_unit

                UNION ALL

                -- 6. 舊單紙杯：依當時扣料快照
                SELECT h.business_date,
                       i.inventory_id AS material_id,
                       i.name AS material_name,
                       COALESCE(i.base_unit, '份') AS unit,
                       SUM(h.qty) AS qty
                FROM historical_cups h
                JOIN inventory i ON i.inventory_id = h.inventory_id
                GROUP BY h.business_date, i.inventory_id, i.name, i.base_unit
            ) c
            GROUP BY c.business_date, c.material_id, c.material_name, c.unit
            ORDER BY c.business_date ASC, c.material_id ASC
        """;

        List<Map<String, Object>> records = jdbc.queryForList(sql, year, month, year, month, year, month, year, month, year, month, year, month);

        List<Map<String, Object>> allMaterials = jdbc.queryForList("""
            SELECT (100000 + p.prep_id) AS inventory_id, p.name AS name, '份' AS base_unit,
                   0.00 AS book_qty, 0.00 AS safety_stock, COALESCE(p.category, '半成品') AS category
            FROM prep_item p
            UNION ALL
            SELECT i.inventory_id AS inventory_id, i.name AS name, COALESCE(i.base_unit, '份') AS base_unit,
                   COALESCE(i.book_qty, 0.00) AS book_qty, COALESCE(i.safety_stock, 0.00) AS safety_stock,
                   COALESCE(i.category, '未分類') AS category
            FROM inventory i
            WHERE EXISTS (SELECT 1 FROM product_recipe pr WHERE pr.inventory_id = i.inventory_id)
               OR EXISTS (SELECT 1 FROM topping t WHERE t.inventory_id = i.inventory_id)
               OR EXISTS (SELECT 1 FROM prep_item_ingredient g WHERE g.inventory_id = i.inventory_id)
               OR i.name LIKE '%紙杯%'
            ORDER BY inventory_id
        """);

        // 獨立查詢當月原料報廢損耗（每日彙總折線圖資料）
        List<Map<String, Object>> wastageRecords = jdbc.queryForList("""
            SELECT 
                DATE_FORMAT(w.business_date, '%Y-%m-%d') AS business_date,
                DAY(w.business_date) AS day_of_month,
                w.inventory_id AS inventory_id,
                i.name AS material_name,
                COALESCE(i.base_unit, '份') AS unit,
                ROUND(SUM(w.quantity), 2) AS total_consumed
            FROM raw_material_wastage w
            JOIN inventory i ON i.inventory_id = w.inventory_id
            WHERE YEAR(w.business_date) = ? AND MONTH(w.business_date) = ?
            GROUP BY w.business_date, w.inventory_id, i.name, i.base_unit
            ORDER BY w.business_date ASC, w.inventory_id ASC
        """, year, month);

        List<Map<String, Object>> wastageMaterials = jdbc.queryForList("""
            SELECT inventory_id, name, COALESCE(base_unit, '份') AS base_unit,
                   COALESCE(book_qty, 0.00) AS book_qty, COALESCE(safety_stock, 0.00) AS safety_stock,
                   COALESCE(category, '未分類') AS category
            FROM inventory
            ORDER BY inventory_id
        """);

        // 當月報廢損耗完整明細（含日期、報廢人姓名、原因、成本等）
        List<Map<String, Object>> wastageDetails = jdbc.queryForList("""
            SELECT 
                w.wastage_id,
                DATE_FORMAT(w.business_date, '%Y-%m-%d') AS business_date,
                w.inventory_id,
                i.name AS material_name,
                COALESCE(i.base_unit, '份') AS unit,
                w.quantity,
                w.unit_cost,
                w.material_cost,
                w.reason,
                w.reported_by,
                s.name AS reported_by_name,
                DATE_FORMAT(w.reported_at, '%Y-%m-%d %H:%i') AS reported_at
            FROM raw_material_wastage w
            JOIN inventory i ON i.inventory_id = w.inventory_id
            LEFT JOIN staff_account s ON s.staff_id = w.reported_by
            WHERE YEAR(w.business_date) = ? AND MONTH(w.business_date) = ?
            ORDER BY w.business_date DESC, w.wastage_id DESC
        """, year, month);

        return Map.of(
            "year", year,
            "month", month,
            "records", records,
            "materials", allMaterials,
            "wastage_records", wastageRecords,
            "wastage_materials", wastageMaterials,
            "wastage_details", wastageDetails
        );
    }

    public record DeductItem(Long inventoryId, BigDecimal deductQty) {}
    public record ConfirmDeductRequest(List<DeductItem> items, String note) {}

    /**
     * 店長確認扣除原料庫存端點：
     * POST /api/inventory-consumption/confirm-deduct
     * 店長檢視消耗與報廢數據後，確認扣減 inventory 帳面庫存 (book_qty)。
     */
    @PostMapping("/confirm-deduct")
    public Map<String, Object> confirmDeduct(
            @Valid @RequestBody ConfirmDeductRequest request,
            Principal principal) {
        if (request.items() == null || request.items().isEmpty()) {
            throw ApiException.bad("請至少選擇一項欲扣除之原物料");
        }
        int count = 0;
        for (DeductItem item : request.items()) {
            if (item.inventoryId() == null || item.deductQty() == null || item.deductQty().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            int updated = jdbc.update(
                "UPDATE inventory SET book_qty = GREATEST(0, book_qty - ?) WHERE inventory_id = ?",
                item.deductQty(), item.inventoryId()
            );
            if (updated > 0) {
                count++;
            }
        }
        return Map.of(
            "success", true,
            "message", "店長確認扣除完成，共更新 " + count + " 項原料庫存",
            "deductedCount", count
        );
    }
}
