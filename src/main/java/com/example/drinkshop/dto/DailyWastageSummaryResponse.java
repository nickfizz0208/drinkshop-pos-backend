package com.example.drinkshop.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 每日報廢統計摘要回應物件（扁平化 DTO）
 */
public record DailyWastageSummaryResponse(
        // 統計日期
        LocalDate date,

        // 當日報廢總杯數
        Integer totalWastedCount,

        // 當日報廢原料與飲品總成本金額
        BigDecimal totalWastageCost,

        // 當日報廢品項明細清單
        List<OrderItemDto> wastedItems,
        boolean costComplete,
        boolean rawWastageEnabled,
        List<RawMaterialWastageResponse> rawMaterialWastages
) {}

