package com.example.drinkshop.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 關班日結算摘要回應物件（扁平化 DTO）
 */
public record ShiftClosingSummaryResponse(
        // 營業日期
        LocalDate businessDate,

        // 班次狀態（CLOSED: 已結班關班）
        String status,

        // 開班零用金 / 備用金
        BigDecimal pettyCash,

        // 當日飲品銷售總營收金額
        BigDecimal totalRevenue,

        // 當日報廢總損失成本
        BigDecimal totalWastageCost,

        // 系統理論應有現金金額（零用金 + 現金實收 - 現金退款）
        BigDecimal expectedCash,

        // 關班實際清點之現金金額
        BigDecimal countedCash,

        // 現金短溢差異金額（清點現金 - 應有現金，正數為溢收，負數為短少）
        BigDecimal diffAmount,

        // 當日總銷售杯數
        Integer totalCups,

        // 現金短溢原因說明
        String diffReason,
        boolean costComplete,
        boolean rawWastageEnabled
) {}

