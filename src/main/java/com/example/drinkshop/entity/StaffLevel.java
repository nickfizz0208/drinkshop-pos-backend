package com.example.drinkshop.entity;

import java.util.Optional;

// 員工等級：和資料庫 staff_account.level 的 ENUM 值一樣，由低到高排列。POS 是門市 POS 機的共用帳號
public enum StaffLevel {
    TRAINEE("新進店員"),
    STAFF("正式店員"),
    POS("門市 POS"),
    MANAGER("店長"),
    HQ("總部");

    public final String label;

    StaffLevel(String label) { this.label = label; }

    // 資料庫的值轉成等級；空白、拼錯、不認得的值一律回傳 empty（等於沒有任何權限）
    public static Optional<StaffLevel> fromCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        for (StaffLevel l : values()) if (l.name().equals(code.trim())) return Optional.of(l);
        return Optional.empty();
    }
}
