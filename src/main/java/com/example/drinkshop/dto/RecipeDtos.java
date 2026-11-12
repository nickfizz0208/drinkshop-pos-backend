package com.example.drinkshop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

// 配方 API 的回應格式。只放畫面需要的欄位：不含售價、成本、供應商
public final class RecipeDtos {
    private RecipeDtos() {}

    // 配方狀態：OK＝有材料清單；MISSING＝尚未建立配方（沒有配方也能點餐，只是這裡看不到材料）
    public enum RecipeStatus { OK, MISSING }

    // 列表：一列一個飲品，不含原料用量
    public record RecipeSummary(long productId, String name, String category, RecipeStatus recipeStatus) {}

    // 材料種類：原料（盤點、進貨的東西，例如牛奶）或半成品（店內做的，例如茶凍、紅茶湯）
    public enum MaterialKind { INVENTORY, PREP }

    // 份數未設定時為 null；半成品附上來源原料。
    // 中杯、大杯共用同一份材料清單（021）
    public record RecipeMaterial(MaterialKind kind, long id, String name, List<String> madeFrom, BigDecimal portions) {}

    // 詳情：steps 是 null 代表沒有記錄製作步驟；standardRecipe 固定 true，提醒這是標準配方
    public record RecipeDetail(long productId, String name, String category, RecipeStatus recipeStatus,
                               boolean standardRecipe, List<RecipeMaterial> materials, String steps,
                               String watermark, String version) {}

    public record MaterialPortions(@NotNull MaterialKind kind, @NotNull @Positive Long id,
                                   @JsonProperty(required = true) @DecimalMin("0.01")
                                   @Digits(integer = 6, fraction = 2) BigDecimal portions) {}

    public record UpdateRecipe(@NotNull @Pattern(regexp = "[a-f0-9]{32}") String version,
                               @NotNull @Size(max = 4000) String steps,
                               @NotNull List<@NotNull @Valid MaterialPortions> materials) {}
}
