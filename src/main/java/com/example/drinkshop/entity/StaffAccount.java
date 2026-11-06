package com.example.drinkshop.entity;

import java.util.Optional;

// staff_account 員工帳號。levelCode 存資料庫原本的字串，不認得的等級不會讓程式出錯
public record StaffAccount(long staffId, String username, String passwordHash, String name,
                           String levelCode, boolean active) {
    public Optional<StaffLevel> level() { return StaffLevel.fromCode(levelCode); }
}
