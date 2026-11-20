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
 * 統計訂單銷售所消耗之「半成品料（prep_item）」與「飲品／加料直接使用的配方原料（inventory，如牛奶等）」，
 * 不混入倉庫生原料（如生茶葉、茶凍粉、紙杯）與原料報廢紀錄。
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
            ) c
            GROUP BY c.business_date, c.material_id, c.material_name, c.unit
            ORDER BY c.business_date ASC, c.material_id ASC
        """;

        List<Map<String, Object>> records = jdbc.queryForList(sql, year, month, year, month, year, month, year, month);

        List<Map<String, Object>> allMaterials = jdbc.queryForList("""
            SELECT (100000 + p.prep_id) AS inventory_id, p.name AS name, '份' AS base_unit
            FROM prep_item p
            UNION ALL
            SELECT i.inventory_id AS inventory_id, i.name AS name, COALESCE(i.base_unit, '份') AS base_unit
            FROM inventory i
            WHERE EXISTS (SELECT 1 FROM product_recipe pr WHERE pr.inventory_id = i.inventory_id)
               OR EXISTS (SELECT 1 FROM topping t WHERE t.inventory_id = i.inventory_id)
            ORDER BY inventory_id
        """);

        // 獨立查詢當月原料報廢損耗（不與訂單消耗混加）
        List<Map<String, Object>> wastageRecords = jdbc.queryForList("""
            SELECT 
                DATE_FORMAT(w.business_date, '%Y-%m-%d') AS business_date,
                DAY(w.business_date) AS day_of_month,
                w.inventory_id AS inventory_id,
                i.name AS material_name,
                COALESCE(i.base_unit, '') AS unit,
                ROUND(SUM(w.quantity), 2) AS total_consumed
            FROM raw_material_wastage w
            JOIN inventory i ON i.inventory_id = w.inventory_id
            WHERE YEAR(w.business_date) = ? AND MONTH(w.business_date) = ?
            GROUP BY w.business_date, w.inventory_id, i.name, i.base_unit
            ORDER BY w.business_date ASC, w.inventory_id ASC
        """, year, month);

        List<Map<String, Object>> wastageMaterials = jdbc.queryForList("""
            SELECT inventory_id, name, COALESCE(base_unit, '') AS base_unit
            FROM inventory
            ORDER BY inventory_id
        """);

        return Map.of(
            "year", year,
            "month", month,
            "records", records,
            "materials", allMaterials,
            "wastage_records", wastageRecords,
            "wastage_materials", wastageMaterials
        );
    }
}
