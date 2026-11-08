package com.example.drinkshop.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 營業日開班請求傳輸物件（扁平化 DTO）
 */
public record OpenShiftRequest(
        // 營業日期（選填，若為 null 則後端自動以今日日期為準）
        LocalDate businessDate,

        // 開班起始備用金 / 零用金（必填，大於等於 0）
        @NotNull(message = "開班零用金不能為空")
        @DecimalMin(value = "0", message = "零用金不可小於 0")
        @Digits(integer = 8, fraction = 2, message = "零用金格式不正確")
        BigDecimal pettyCash,

        // 當日店員排班與工作崗位 JSON 字串（選填，最長 500 字元）
        @Size(max = 500, message = "崗位設定不可超過 500 字元")
        String rolesJson,

        // 舊版相容欄位；後端忽略此值，實際操作人以登入帳號為準
        Long staffId
) {
    public OpenShiftRequest(BigDecimal pettyCash, String rolesJson) {
        this(null, pettyCash, rolesJson, null);
    }
}

