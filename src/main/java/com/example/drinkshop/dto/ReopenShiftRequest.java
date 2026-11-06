package com.example.drinkshop.dto;

import java.time.LocalDate;

/**
 * 重新開班（取消關班、接續營業）請求。
 * businessDate 選填，沒填就是今天。開班人員與零用金沿用原本的開班紀錄。
 */
public record ReopenShiftRequest(LocalDate businessDate) {
}
