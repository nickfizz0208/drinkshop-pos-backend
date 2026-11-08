package com.example.drinkshop.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 原料損耗保存當時的數量與成本，不隨日後原料成本修改。 */
public record RawMaterialWastageResponse(long wastageId, LocalDate businessDate, long inventoryId,
    BigDecimal quantity, BigDecimal unitCost, BigDecimal materialCost, String reason,
    long reportedBy, LocalDateTime reportedAt) {}
