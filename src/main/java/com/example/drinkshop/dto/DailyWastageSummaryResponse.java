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

        // 當日原料報廢成本合計（飲品不計成本，021）；有原料沒建單位成本時為 null
        BigDecimal totalWastageCost,

        // 當日報廢品項明細清單
        List<OrderItemDto> wastedItems,
        boolean costComplete,
        boolean rawWastageEnabled,
        List<RawMaterialWastageResponse> rawMaterialWastages
) {}

