package com.example.drinkshop.repository;

import com.example.drinkshop.entity.Product;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class ProductRepository {
    // 只選配方畫面需要的欄位；售價、成本等不撈
    private static final String COLUMNS = "product_id,name,category,recipe_steps,recipe_json,min_view_level,on_sale";
    private static final RowMapper<Product> ROW = (r, n) -> new Product(
        r.getLong("product_id"), r.getString("name"), r.getString("category"), r.getString("recipe_steps"),
        r.getString("recipe_json"), r.getString("min_view_level"), r.getBoolean("on_sale"));

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

    public Optional<Product> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM product WHERE product_id=?", ROW, id).stream().findFirst();
    }
}
