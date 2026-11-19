package com.example.drinkshop.repository;

import com.example.drinkshop.entity.Staff;
import com.example.drinkshop.exception.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.util.*;

/**
 * 資料庫存取層：JdbcTemplate 執行 SQL，結果以 Map 的欄位名稱取值。
 * 這版使用 Spring JDBC，沒有 JPA Entity 自動建表；規則與交易流程在 Service。
 * SQL 的 ? 配合 args 傳值，不把使用者輸入直接拼入 SQL。
 */
@Repository
public class PosRepository {
    private final JdbcTemplate jdbc;
    public PosRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // rows 查多筆；one 查一筆且找不到就回 404；update 用於新增／修改／刪除。
    public List<Map<String,Object>> rows(String sql, Object... args) { return jdbc.queryForList(sql, args); }
    public Map<String,Object> one(String sql, Object... args) {
        var rows = rows(sql, args);
        if (rows.isEmpty()) throw ApiException.missing("找不到指定資料");
        return rows.getFirst();
    }
    public int update(String sql, Object... args) { return jdbc.update(sql, args); }
    // 新增資料後取回資料庫自動編號，供訂單明細與加料建立關聯。
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
    // 組成「訂單主檔 → 多筆明細 → 每筆多種加料」；移除內部快照與檔案路徑。
    public Map<String,Object> detail(long id) {
        var order = new LinkedHashMap<>(order(id));
        order.remove("request_hash");
        var items = items(id);
        for (var item : items) {
            item.remove("deducted_inventory_json");
            item.remove("photo_path");
            item.put("photo_url", hasPhoto(id, number(item,"item_id"))
                ? "/api/orders/"+id+"/items/"+item.get("item_id")+"/photo" : null);
            item.put("toppings", rows("SELECT order_topping_id,topping_id,topping_name,qty,price_at_order FROM order_item_topping WHERE item_id=? ORDER BY topping_id",item.get("item_id")));
            item.put("options", rows("SELECT order_option_id,option_id,option_name,qty FROM order_item_option WHERE item_id=? ORDER BY order_option_id",item.get("item_id")));
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
