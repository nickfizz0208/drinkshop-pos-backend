package com.example.drinkshop.controller;

import com.example.drinkshop.repository.SaleStatusRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 物料月消耗統計 API：
 * 結合訂單銷售（配方+加料扣料）與原料報廢，統計指定年月份每日原物料的消耗總量。
 * 半成品依 prep_item_ingredient 的用量展開成原料（043），下層半成品也會展開。
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

        // 半成品展開成原料（043）：一份半成品用多少原料，含下層半成品（最多 MAX_SUB_PREP_LEVELS 層），用量相乘
        String sql = """
            WITH RECURSIVE prep_expand (root_id, inventory_id, sub_prep_id, qty, depth) AS (
                SELECT g.prep_id, g.inventory_id, g.sub_prep_id, CAST(g.qty AS DECIMAL(20,6)), 0
                FROM prep_item_ingredient g
                UNION ALL
                SELECT e.root_id, g.inventory_id, g.sub_prep_id, CAST(e.qty * g.qty AS DECIMAL(20,6)), e.depth + 1
                FROM prep_expand e JOIN prep_item_ingredient g ON g.prep_id = e.sub_prep_id
                WHERE e.depth < {MAX_LEVELS}
            ),
            prep_flat AS (
                SELECT root_id AS prep_id, inventory_id, SUM(qty) AS qty
                FROM prep_expand WHERE inventory_id IS NOT NULL
                GROUP BY root_id, inventory_id
            ), historical_cups AS (
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
                c.inventory_id,
                i.name AS material_name,
                COALESCE(i.base_unit, '') AS unit,
                ROUND(SUM(c.qty), 2) AS total_consumed
            FROM (
                -- 1. 訂單商品直配配方
                SELECT o.business_date, pr.inventory_id, SUM(oi.qty * COALESCE(pr.portions, 1)) AS qty
                FROM pos_order o
                JOIN order_item oi ON oi.order_id = o.order_id
                JOIN product_recipe pr ON pr.product_id = oi.product_id
                WHERE o.status != 'CANCELLED' AND oi.status != 'CANCELLED'
                  AND pr.inventory_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM historical_cups h WHERE h.item_id=oi.item_id AND h.inventory_id=pr.inventory_id)
                  AND YEAR(o.business_date) = ? AND MONTH(o.business_date) = ?
                GROUP BY o.business_date, pr.inventory_id

                UNION ALL

                -- 2. 訂單商品半成品配方（展開成原料，043）
                SELECT o.business_date, pf.inventory_id, SUM(oi.qty * COALESCE(pr.portions, 1) * pf.qty) AS qty
                FROM pos_order o
                JOIN order_item oi ON oi.order_id = o.order_id
                JOIN product_recipe pr ON pr.product_id = oi.product_id
                JOIN prep_flat pf ON pf.prep_id = pr.prep_id
                WHERE o.status != 'CANCELLED' AND oi.status != 'CANCELLED'
                  AND pr.prep_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM historical_cups h WHERE h.item_id=oi.item_id AND h.inventory_id=pf.inventory_id)
                  AND YEAR(o.business_date) = ? AND MONTH(o.business_date) = ?
                GROUP BY o.business_date, pf.inventory_id

                UNION ALL

                -- 3. 訂單加料消耗（同一種加兩份就算兩份，039）
                SELECT o.business_date, t.inventory_id, SUM(oi.qty * oit.qty) AS qty
                FROM pos_order o
                JOIN order_item oi ON oi.order_id = o.order_id
                JOIN order_item_topping oit ON oit.item_id = oi.item_id
                JOIN topping t ON t.topping_id = oit.topping_id
                WHERE o.status != 'CANCELLED' AND oi.status != 'CANCELLED'
                  AND t.inventory_id IS NOT NULL
                  AND YEAR(o.business_date) = ? AND MONTH(o.business_date) = ?
                GROUP BY o.business_date, t.inventory_id

                UNION ALL

                -- 3b. 加料是半成品（例如茶凍、雙Q果）：展開成原料（043）
                SELECT o.business_date, pf.inventory_id, SUM(oi.qty * oit.qty * pf.qty) AS qty
                FROM pos_order o
                JOIN order_item oi ON oi.order_id = o.order_id
                JOIN order_item_topping oit ON oit.item_id = oi.item_id
                JOIN topping t ON t.topping_id = oit.topping_id
                JOIN prep_flat pf ON pf.prep_id = t.prep_id
                WHERE o.status != 'CANCELLED' AND oi.status != 'CANCELLED'
                  AND t.prep_id IS NOT NULL
                  AND YEAR(o.business_date) = ? AND MONTH(o.business_date) = ?
                GROUP BY o.business_date, pf.inventory_id

                UNION ALL

                -- 舊單紙杯依當時扣料快照，避免現行配方缺少杯材或重算。
                SELECT business_date, inventory_id, SUM(qty) FROM historical_cups GROUP BY business_date, inventory_id

                UNION ALL

                -- 4. 原料報廢消耗
                SELECT w.business_date, w.inventory_id, SUM(w.quantity) AS qty
                FROM raw_material_wastage w
                WHERE YEAR(w.business_date) = ? AND MONTH(w.business_date) = ?
                GROUP BY w.business_date, w.inventory_id
            ) c
            JOIN inventory i ON i.inventory_id = c.inventory_id
            GROUP BY c.business_date, c.inventory_id, i.name, i.base_unit
            ORDER BY c.business_date ASC, c.inventory_id ASC
        """.replace("{MAX_LEVELS}", String.valueOf(SaleStatusRepository.MAX_SUB_PREP_LEVELS));

        List<Map<String, Object>> records = jdbc.queryForList(sql, year, month, year, month, year, month, year, month, year, month, year, month);

        List<Map<String, Object>> allMaterials = jdbc.queryForList(
            "SELECT inventory_id, name, base_unit FROM inventory ORDER BY inventory_id"
        );

        return Map.of(
            "year", year,
            "month", month,
            "records", records,
            "materials", allMaterials
        );
    }
}
