package com.example.drinkshop.repository;

import com.example.drinkshop.entity.Product;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class ProductRepository {
    // 只選配方畫面需要的欄位；售價、成本等不撈
    private static final String COLUMNS = "product_id,name,category,recipe_steps,min_view_level,on_sale";
    private static final RowMapper<Product> ROW = (r, n) -> new Product(
        r.getLong("product_id"), r.getString("name"), r.getString("category"), r.getString("recipe_steps"),
        r.getString("min_view_level"), r.getBoolean("on_sale"));

    private final JdbcTemplate jdbc;
    public ProductRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // 上架、而且最低可看等級在 levels 裡的飲品；keyword、category 是 null 就不篩
    // 全部用 ? 參數，不把使用者輸入直接接進 SQL
    public List<Product> search(List<String> levels, String keyword, String category) {
        if (levels.isEmpty()) return List.of();
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM product WHERE on_sale=TRUE AND min_view_level IN (")
            .append(String.join(",", Collections.nCopies(levels.size(), "?"))).append(")");
        List<Object> args = new ArrayList<>(levels);
        if (keyword != null) {
            // LIKE 的 % 和 _ 是萬用字元，先跳脫，讓使用者打的字只當一般文字
            sql.append(" AND name LIKE ? ESCAPE '!'");
            args.add("%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        if (category != null) {
            sql.append(" AND category=?");
            args.add(category);
        }
        sql.append(" ORDER BY product_id");
        return jdbc.query(sql.toString(), ROW, args.toArray());
    }

    public List<String> categories(List<String> levels) {
        if (levels.isEmpty()) return List.of();
        String marks = String.join(",", Collections.nCopies(levels.size(), "?"));
        return jdbc.queryForList("SELECT DISTINCT category FROM product WHERE on_sale=TRUE AND min_view_level IN (" + marks
            + ") ORDER BY category", String.class, levels.toArray());
    }

    /** 有建材料清單的飲品編號（配方列表標示「尚未建立配方」用）。 */
    public Set<Long> productIdsWithRecipe() {
        return new HashSet<>(jdbc.queryForList("SELECT DISTINCT product_id FROM product_recipe", Long.class));
    }

    /** 一杯飲品的材料清單：一列是原料或半成品；半成品附上它由哪些原料做成（以「、」分隔）。 */
    public List<Map<String, Object>> materials(long productId) {
        return jdbc.queryForList("""
            SELECT r.inventory_id, r.prep_id, COALESCE(i.name, x.name) AS name,
                   (SELECT GROUP_CONCAT(pi.name ORDER BY pi.inventory_id SEPARATOR '、')
                      FROM prep_item_ingredient g JOIN inventory pi ON pi.inventory_id = g.inventory_id
                     WHERE g.prep_id = r.prep_id) AS made_from
            FROM product_recipe r
            LEFT JOIN inventory i ON i.inventory_id = r.inventory_id
            LEFT JOIN prep_item x ON x.prep_id = r.prep_id
            WHERE r.product_id = ?
            ORDER BY r.sort_order, r.line_id
            """, productId);
    }

    public Optional<Product> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM product WHERE product_id=?", ROW, id).stream().findFirst();
    }
}
