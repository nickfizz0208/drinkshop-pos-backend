package com.example.drinkshop.repository;

import com.example.drinkshop.entity.Staff;
import com.example.drinkshop.exception.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class PosRepository {
    private final JdbcTemplate jdbc;
    public PosRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String,Object>> rows(String sql, Object... args) { return jdbc.queryForList(sql, args); }
    public Map<String,Object> one(String sql, Object... args) {
        var rows = rows(sql, args);
        if (rows.isEmpty()) throw ApiException.missing("找不到指定資料");
        return rows.getFirst();
    }
    public int update(String sql, Object... args) { return jdbc.update(sql, args); }
    public long insert(String keyColumn, String sql, Object... args) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, new String[]{keyColumn});
            for (int i=0; i<args.length; i++) statement.setObject(i+1, args[i]);
            return statement;
        }, holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }
    public Optional<Staff> staff(String username) {
        return jdbc.query("SELECT * FROM staff_account WHERE username=?", (r,n) ->
            new Staff(r.getLong("staff_id"),r.getString("username"),r.getString("password_hash"),
                r.getString("name"),r.getString("level"),r.getString("pin_hash"),r.getBoolean("active")), username)
            .stream().findFirst();
    }
    public Map<String,Object> product(long id) { return one("SELECT * FROM product WHERE product_id=?", id); }
    public Map<String,Object> topping(long id) { return one("SELECT * FROM topping WHERE topping_id=?", id); }
    public List<Map<String,Object>> rules() { return rows("SELECT * FROM conflict_rule WHERE enabled=TRUE ORDER BY rule_id"); }
    public Map<String,Object> order(long id) { return one("SELECT * FROM pos_order WHERE order_id=?", id); }
    public List<Map<String,Object>> items(long orderId) {
        return rows("SELECT * FROM order_item WHERE order_id=? ORDER BY item_id", orderId);
    }
    public Map<String,Object> detail(long id) {
        var order = new LinkedHashMap<>(order(id));
        order.remove("request_hash");
        var items = items(id);
        for (var item : items) {
            item.remove("deducted_inventory_json");
            item.remove("photo_path");
            item.put("photo_url", hasPhoto(id, number(item,"item_id"))
                ? "/api/orders/"+id+"/items/"+item.get("item_id")+"/photo" : null);
            item.put("toppings", rows("SELECT order_topping_id,topping_id,topping_name,price_at_order FROM order_item_topping WHERE item_id=? ORDER BY topping_id",item.get("item_id")));
        }
        order.put("items", items);
        return order;
    }
    public boolean hasPhoto(long orderId, long itemId) {
        return one("SELECT photo_path FROM order_item WHERE order_id=? AND item_id=?", orderId,itemId).get("photo_path") != null;
    }
    public static long number(Map<String,Object> row, String key) { return ((Number)row.get(key)).longValue(); }
    public static int integer(Map<String,Object> row, String key) { return ((Number)row.get(key)).intValue(); }
    public static boolean flag(Map<String,Object> row, String key) {
        Object v=row.get(key); return Boolean.TRUE.equals(v) || (v instanceof Number n && n.intValue()!=0);
    }
}
