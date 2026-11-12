package com.example.drinkshop.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;

/**
 * 停售狀態（022）。原料、半成品停售時，用到它的飲品與加料一起不能賣；
 * 飲品、加料自己的 on_sale 不會被改寫，所以原料恢復後會自動恢復。
 */
@Repository
public class SaleStatusRepository {
    /** 原料停售：inventoryIdExpr 是 NULL 時為假。 */
    private static String inventoryStopped(String inventoryIdExpr) {
        return "EXISTS (SELECT 1 FROM inventory ss_i WHERE ss_i.inventory_id = " + inventoryIdExpr + " AND ss_i.on_sale = FALSE)";
    }
    /** 半成品停售：自己停售，或做它的原料有停售。prepIdExpr 是 NULL 時為假。 */
    private static String prepStopped(String prepIdExpr) {
        return "EXISTS (SELECT 1 FROM prep_item ss_x WHERE ss_x.prep_id = " + prepIdExpr + " AND (ss_x.on_sale = FALSE"
            + " OR EXISTS (SELECT 1 FROM prep_item_ingredient ss_g JOIN inventory ss_gi ON ss_gi.inventory_id = ss_g.inventory_id"
            + " WHERE ss_g.prep_id = ss_x.prep_id AND ss_gi.on_sale = FALSE)))";
    }
    /** 飲品（別名 p）現在能不能賣。點餐菜單用。 */
    public static final String PRODUCT_SELLABLE = "p.on_sale = TRUE AND NOT EXISTS (SELECT 1 FROM product_recipe ss_r"
        + " WHERE ss_r.product_id = p.product_id AND (" + inventoryStopped("ss_r.inventory_id") + " OR " + prepStopped("ss_r.prep_id") + "))";
    /** 加料（別名 t）現在能不能賣。點餐菜單用。 */
    public static final String TOPPING_SELLABLE = "t.on_sale = TRUE AND NOT " + inventoryStopped("t.inventory_id")
        + " AND NOT " + prepStopped("t.prep_id");

    // 讓某一杯飲品、某一項加料不能賣的材料名稱：停售的原料、停售的半成品、半成品裡停售的原料
    private static final String STOPPED_FOR_PRODUCT = """
        SELECT i.name FROM product_recipe r JOIN inventory i ON i.inventory_id = r.inventory_id
         WHERE r.product_id = ? AND i.on_sale = FALSE
        UNION SELECT x.name FROM product_recipe r JOIN prep_item x ON x.prep_id = r.prep_id
         WHERE r.product_id = ? AND x.on_sale = FALSE
        UNION SELECT i.name FROM product_recipe r JOIN prep_item_ingredient g ON g.prep_id = r.prep_id
          JOIN inventory i ON i.inventory_id = g.inventory_id
         WHERE r.product_id = ? AND i.on_sale = FALSE
        """;
    private static final String STOPPED_FOR_TOPPING = """
        SELECT i.name FROM topping t JOIN inventory i ON i.inventory_id = t.inventory_id
         WHERE t.topping_id = ? AND i.on_sale = FALSE
        UNION SELECT x.name FROM topping t JOIN prep_item x ON x.prep_id = t.prep_id
         WHERE t.topping_id = ? AND x.on_sale = FALSE
        UNION SELECT i.name FROM topping t JOIN prep_item_ingredient g ON g.prep_id = t.prep_id
          JOIN inventory i ON i.inventory_id = g.inventory_id
         WHERE t.topping_id = ? AND i.on_sale = FALSE
        """;

    private final JdbcTemplate jdbc;
    public SaleStatusRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<String> stoppedMaterialsOfProduct(long productId) {
        return jdbc.queryForList(STOPPED_FOR_PRODUCT, String.class, productId, productId, productId);
    }
    public List<String> stoppedMaterialsOfTopping(long toppingId) {
        return jdbc.queryForList(STOPPED_FOR_TOPPING, String.class, toppingId, toppingId, toppingId);
    }

    public List<Map<String, Object>> inventories() {
        return jdbc.queryForList("SELECT inventory_id, name, category, on_sale FROM inventory ORDER BY inventory_id");
    }
    public List<Map<String, Object>> preps() {
        return jdbc.queryForList("SELECT prep_id, name, category, on_sale FROM prep_item ORDER BY prep_id");
    }
    public List<Map<String, Object>> prepIngredients() {
        return jdbc.queryForList("SELECT prep_id, inventory_id FROM prep_item_ingredient ORDER BY prep_id, inventory_id");
    }
    public List<Map<String, Object>> products() {
        return jdbc.queryForList("SELECT product_id, name, category, on_sale FROM product ORDER BY product_id");
    }
    public List<Map<String, Object>> recipeLines() {
        return jdbc.queryForList("SELECT product_id, inventory_id, prep_id FROM product_recipe ORDER BY product_id, sort_order, line_id");
    }
    public List<Map<String, Object>> toppings() {
        return jdbc.queryForList("SELECT topping_id, name, on_sale, inventory_id, prep_id FROM topping ORDER BY topping_id");
    }

    /** 改一項的 on_sale；回傳改到幾列（0＝找不到）。table、key 只用程式內的固定值。 */
    public int setOnSale(Kind kind, long id, boolean onSale) {
        return jdbc.update("UPDATE " + kind.table + " SET on_sale = ? WHERE " + kind.key + " = ?", onSale, id);
    }

    /** 改原料或半成品的分類；只接受 INVENTORY、PREP。回傳改到幾列（0＝找不到）。 */
    public int setCategory(Kind kind, long id, String category) {
        if (kind != Kind.INVENTORY && kind != Kind.PREP) throw new IllegalArgumentException("只有原料、半成品有分類");
        return jdbc.update("UPDATE " + kind.table + " SET category = ? WHERE " + kind.key + " = ?", category, id);
    }

    public enum Kind {
        INVENTORY("inventory", "inventory_id", "原料"),
        PREP("prep_item", "prep_id", "半成品"),
        PRODUCT("product", "product_id", "飲品"),
        TOPPING("topping", "topping_id", "加料");
        final String table, key;
        public final String label;
        Kind(String table, String key, String label) { this.table = table; this.key = key; this.label = label; }
    }
}
