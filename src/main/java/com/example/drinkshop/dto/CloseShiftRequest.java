package com.example.drinkshop.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 營業日關班日結請求傳輸物件（扁平化 DTO）
 */
public record CloseShiftRequest(
        // 營業日期（選填，若為 null 則後端自動以今日日期為準）
        LocalDate businessDate,

        // 關班實際清點現金總額（必填，大於等於 0）
        @NotNull(message = "清點現金不能為空")
        @DecimalMin(value = "0", message = "清點現金不可小於 0")
        @Digits(integer = 8, fraction = 2, message = "清點現金格式不正確")
        BigDecimal countedCash,

        // 現金短溢差異原因說明（選填，最長 255 字元）
        @Size(max = 255, message = "差異原因說明不可超過 255 字元")
        String diffReason,

        // 舊版相容欄位；後端忽略此值，實際操作人以登入帳號為準
        Long staffId
) {
    public CloseShiftRequest(BigDecimal countedCash, String diffReason) {
        this(null, countedCash, diffReason, null);
    }
}

