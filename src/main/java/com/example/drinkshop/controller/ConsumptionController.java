package com.example.drinkshop.controller;

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

        String sql = """
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
                  AND YEAR(o.business_date) = ? AND MONTH(o.business_date) = ?
                GROUP BY o.business_date, pr.inventory_id

                UNION ALL

                -- 2. 訂單商品半成品配方
                SELECT o.business_date, pii.inventory_id, SUM(oi.qty * COALESCE(pr.portions, 1)) AS qty
                FROM pos_order o
                JOIN order_item oi ON oi.order_id = o.order_id
                JOIN product_recipe pr ON pr.product_id = oi.product_id
                JOIN prep_item_ingredient pii ON pii.prep_id = pr.prep_id
                WHERE o.status != 'CANCELLED' AND oi.status != 'CANCELLED'
                  AND pr.prep_id IS NOT NULL
                  AND YEAR(o.business_date) = ? AND MONTH(o.business_date) = ?
                GROUP BY o.business_date, pii.inventory_id

                UNION ALL

                -- 3. 訂單加料消耗
                SELECT o.business_date, t.inventory_id, SUM(oi.qty) AS qty
                FROM pos_order o
                JOIN order_item oi ON oi.order_id = o.order_id
                JOIN order_item_topping oit ON oit.item_id = oi.item_id
                JOIN topping t ON t.topping_id = oit.topping_id
                WHERE o.status != 'CANCELLED' AND oi.status != 'CANCELLED'
                  AND t.inventory_id IS NOT NULL
                  AND YEAR(o.business_date) = ? AND MONTH(o.business_date) = ?
                GROUP BY o.business_date, t.inventory_id

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
        """;

        List<Map<String, Object>> records = jdbc.queryForList(sql, year, month, year, month, year, month, year, month);

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
