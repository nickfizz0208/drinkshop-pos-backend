package com.example.drinkshop.repository;

import com.example.drinkshop.entity.StaffAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.Optional;

// 和團隊一樣用 JdbcTemplate 自己寫 SQL；只讀員工資料，不寫入
@Repository
public class StaffAccountRepository {
    private static final RowMapper<StaffAccount> ROW = (r, n) -> new StaffAccount(
        r.getLong("staff_id"), r.getString("username"), r.getString("password_hash"), r.getString("name"),
        r.getString("level"), r.getBoolean("active"));

    private final JdbcTemplate jdbc;
    public StaffAccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<StaffAccount> findByUsername(String username) {
        return jdbc.query("SELECT * FROM staff_account WHERE username=?", ROW, username).stream().findFirst();
    }
}
