package com.example.drinkshop.dto;

import java.math.BigDecimal;
import java.util.List;

// 配方 API 的回應格式。只放畫面需要的欄位：不含售價、成本、供應商
public final class RecipeDtos {
    private RecipeDtos() {}

    // 配方狀態：OK＝有配方；MISSING＝尚未建立配方；INVALID＝資料格式有誤（請負責的人修資料）
    public enum RecipeStatus { OK, MISSING, INVALID }

    // 列表：一列一個飲品，不含原料用量
    public record RecipeSummary(long productId, String name, String category, RecipeStatus recipeStatus) {}

    // 一行原料：紅茶包 1 包。unit 是 null 代表資料庫沒有記錄單位（不猜）
    public record RecipeLine(long inventoryId, String item, BigDecimal qty, String unit) {}

    // 一個杯型的配方：code 是資料裡的杯型代碼（M、L），label 是中文名稱
    public record RecipeSize(String code, String label, List<RecipeLine> lines) {}

    // 詳情：steps 是 null 代表沒有記錄製作步驟；standardRecipe 固定 true，提醒這是標準配方
    public record RecipeDetail(long productId, String name, String category, RecipeStatus recipeStatus,
                               boolean standardRecipe, List<RecipeSize> sizes, String steps, String watermark) {}
}
