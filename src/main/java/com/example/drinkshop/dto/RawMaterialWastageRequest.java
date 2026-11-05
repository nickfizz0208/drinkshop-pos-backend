package com.example.drinkshop.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 原料報廢登記請求傳輸物件（扁平化 DTO）
 */
public record RawMaterialWastageRequest(
        // 原料庫存編號（必填）
        @NotNull @Positive Long inventoryId,

        // 報廢損耗數量（必填，必須大於 0）
        @NotNull(message = "報廢數量不能為空")
        @Positive(message = "報廢數量必須大於 0")
        @Digits(integer = 10, fraction = 2, message = "報廢數量格式不正確")
        BigDecimal wastageQty,

        // 報廢損耗原因說明（例如 過期、變質、打翻、煮壞）
        @NotBlank(message = "報廢原因不能為空")
        @Size(max = 255, message = "報廢原因不能超過 255 字元")
        String reason,

        // 舊版相容欄位；後端忽略此值，實際操作人以登入帳號為準
        Long staffId
) {
    public RawMaterialWastageRequest(BigDecimal wastageQty, String reason) {
        this(null, wastageQty, reason, null);
    }
}

