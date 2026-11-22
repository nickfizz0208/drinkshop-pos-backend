package com.example.drinkshop.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 停售狀態（028）。原料、半成品停售時，用到它的飲品與加料一起不能賣；
 * 飲品、加料自己的 on_sale 不會被改寫，所以原料恢復後會自動恢復。
 * 半成品的材料可以是原料或別的半成品（043）；每一項材料的 link（044）決定它停售時這個半成品要不要跟著停，
 * link＝TRUE 的半成品材料會一路往上連動（最多 MAX_SUB_PREP_LEVELS 層），例如生波霸 → 熟波霸 → 雙Q果。
 */
@Repository
public class SaleStatusRepository {
    /** 半成品裡的半成品最多往下追幾層（043）；停售連動、消耗報表、停售管理頁都照這個上限。 */
    public static final int MAX_SUB_PREP_LEVELS = 3;

    /** 原料停售：inventoryIdExpr 是 NULL 時為假。 */
    private static String inventoryStopped(String inventoryIdExpr) {
        return "EXISTS (SELECT 1 FROM inventory ss_i WHERE ss_i.inventory_id = " + inventoryIdExpr + " AND ss_i.on_sale = FALSE)";
    }
    /**
     * 半成品停售：自己停售，或 link＝TRUE 的材料停售——原料停售，或半成品材料（再往下最多 MAX_SUB_PREP_LEVELS 層）停售。
     * prepIdExpr 是 NULL 時為假。level 只用來讓每一層的別名不重複。
     */
    private static String prepStopped(String prepIdExpr, int level) {
        String x = "ss_x" + level, g = "ss_g" + level, gi = "ss_gi" + level, s = "ss_s" + level;
        String sql = "EXISTS (SELECT 1 FROM prep_item " + x + " WHERE " + x + ".prep_id = " + prepIdExpr + " AND (" + x + ".on_sale = FALSE"
            + " OR EXISTS (SELECT 1 FROM prep_item_ingredient " + g + " JOIN inventory " + gi + " ON " + gi + ".inventory_id = " + g + ".inventory_id"
            + " WHERE " + g + ".prep_id = " + x + ".prep_id AND " + g + ".link = TRUE AND " + gi + ".on_sale = FALSE)";
        if (level < MAX_SUB_PREP_LEVELS) {
            sql += " OR EXISTS (SELECT 1 FROM prep_item_ingredient " + s + " WHERE " + s + ".prep_id = " + x + ".prep_id AND " + s + ".link = TRUE AND "
                + prepStopped(s + ".sub_prep_id", level + 1) + ")";
        }
        return sql + "))";
    }
    /** 飲品（別名 p）現在能不能賣。點餐菜單用。 */
    public static final String PRODUCT_SELLABLE = "p.on_sale = TRUE AND p.sold_out = FALSE AND NOT EXISTS (SELECT 1 FROM product_recipe ss_r"
        + " WHERE ss_r.product_id = p.product_id AND (" + inventoryStopped("ss_r.inventory_id") + " OR " + prepStopped("ss_r.prep_id", 0) + "))";
    /** 加料（別名 t）現在能不能賣。點餐菜單用。 */
    public static final String TOPPING_SELLABLE = "t.on_sale = TRUE AND NOT " + inventoryStopped("t.inventory_id")
        + " AND NOT " + prepStopped("t.prep_id", 0);

    private final JdbcTemplate jdbc;
    public SaleStatusRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 讓某一杯飲品不能賣的材料名稱：停售的原料、停售的半成品、半成品裡 link＝TRUE 的停售材料（含更下層）。 */
    public List<String> stoppedMaterialsOfProduct(long productId) {
        var names = new LinkedHashSet<String>(jdbc.queryForList("""
            SELECT i.name FROM product_recipe r JOIN inventory i ON i.inventory_id = r.inventory_id
             WHERE r.product_id = ? AND i.on_sale = FALSE ORDER BY r.sort_order, r.line_id
            """, String.class, productId));
        for (long prep : jdbc.queryForList("SELECT prep_id FROM product_recipe WHERE product_id = ? AND prep_id IS NOT NULL"
                + " ORDER BY sort_order, line_id", Long.class, productId)) {
            stoppedInPrep(prep, 0, names, new HashSet<>());
        }
        return List.copyOf(names);
    }
    /** 讓某一項加料不能賣的材料名稱。 */
    public List<String> stoppedMaterialsOfTopping(long toppingId) {
        var names = new LinkedHashSet<String>(jdbc.queryForList("""
            SELECT i.name FROM topping t JOIN inventory i ON i.inventory_id = t.inventory_id
             WHERE t.topping_id = ? AND i.on_sale = FALSE
            """, String.class, toppingId));
        for (long prep : jdbc.queryForList("SELECT prep_id FROM topping WHERE topping_id = ? AND prep_id IS NOT NULL", Long.class, toppingId)) {
            stoppedInPrep(prep, 0, names, new HashSet<>());
        }
        return List.copyOf(names);
    }
    private void stoppedInPrep(long prepId, int level, Set<String> names, Set<Long> seen) {
        if (!seen.add(prepId)) return;
        names.addAll(jdbc.queryForList("SELECT name FROM prep_item WHERE prep_id = ? AND on_sale = FALSE", String.class, prepId));
        names.addAll(jdbc.queryForList("""
            SELECT i.name FROM prep_item_ingredient g JOIN inventory i ON i.inventory_id = g.inventory_id
             WHERE g.prep_id = ? AND g.link = TRUE AND i.on_sale = FALSE ORDER BY g.line_id
            """, String.class, prepId));
        if (level >= MAX_SUB_PREP_LEVELS) return;
        for (long sub : jdbc.queryForList("SELECT sub_prep_id FROM prep_item_ingredient WHERE prep_id = ? AND sub_prep_id IS NOT NULL"
                + " AND link = TRUE ORDER BY line_id", Long.class, prepId)) {
            stoppedInPrep(sub, level + 1, names, seen);
        }
    }

    public List<Map<String, Object>> inventories() {
        return jdbc.queryForList("SELECT inventory_id, name, category, on_sale, show_in_sale FROM inventory ORDER BY inventory_id");
    }
    public List<Map<String, Object>> preps() {
        return jdbc.queryForList("SELECT prep_id, name, category, on_sale, show_in_sale FROM prep_item ORDER BY prep_id");
    }
    public List<Map<String, Object>> prepIngredients() {
        return jdbc.queryForList("SELECT prep_id, inventory_id, sub_prep_id, link FROM prep_item_ingredient ORDER BY prep_id, line_id");
    }
    public List<Map<String, Object>> products() {
        return jdbc.queryForList("SELECT product_id, name, category, on_sale, show_in_sale, sold_out FROM product ORDER BY product_id");
    }
    public List<Map<String, Object>> recipeLines() {
        return jdbc.queryForList("SELECT product_id, inventory_id, prep_id FROM product_recipe ORDER BY product_id, sort_order, line_id");
    }
    public List<Map<String, Object>> toppings() {
        return jdbc.queryForList("SELECT topping_id, name, on_sale, show_in_sale, inventory_id, prep_id FROM topping ORDER BY topping_id");
    }

    public int setSoldOut(long id, boolean soldOut) {
        return jdbc.update("UPDATE product SET sold_out=? WHERE product_id=?", soldOut, id);
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
