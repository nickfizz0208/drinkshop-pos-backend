package com.example.drinkshop.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 停售管理 API 的請求與回應（022）。
 * onSale 是「這一項自己有沒有停售」；sellable 是「現在實際能不能賣」：
 * 自己販售中，而且用到的原料、半成品都沒有停售。原料恢復販售後，因它停售的品項會自動恢復。
 */
public final class SaleStatusDtos {
    private SaleStatusDtos() {}

    /** 原料。category 是分類（023，null＝未分類）；usedByProducts／usedByToppings：直接或透過半成品用到它的飲品、加料編號。 */
    public record InventoryStatus(long inventoryId, String name, String category, boolean onSale,
                                  List<Long> usedByProducts, List<Long> usedByToppings) {}

    /** 半成品。inventoryIds：它由哪些原料做成；其中一項停售，這個半成品也算停售。 */
    public record PrepStatus(long prepId, String name, String category, boolean onSale, boolean available, List<Long> inventoryIds,
                             List<Long> usedByProducts, List<Long> usedByToppings) {}

    /** 飲品。stoppedBy：讓它不能賣的原料、半成品名稱（自己停售不列在這裡）。 */
    public record ProductStatus(long productId, String name, String category, boolean onSale, boolean sellable,
                                List<Long> inventoryIds, List<Long> prepIds, List<String> stoppedBy) {}

    /** 加料：指向一項原料或一項半成品。 */
    public record ToppingStatus(long toppingId, String name, boolean onSale, boolean sellable,
                                Long inventoryId, Long prepId, List<String> stoppedBy) {}

    public record SaleStatus(List<InventoryStatus> inventories, List<PrepStatus> preps,
                             List<ProductStatus> products, List<ToppingStatus> toppings) {}

    /** 原料或半成品的分類種類。 */
    public enum MaterialKind { INVENTORY, PREP }

    /** 改一項原料或半成品的分類（023）；category 空白＝未分類。 */
    public record CategoryChange(@NotNull MaterialKind kind, @NotNull @Positive Long id,
                                 @Size(max = 20, message = "分類最多 20 個字") String category) {}

    /** 一項變更：onSale = false 停售、true 恢復販售。 */
    public record Change(@NotNull @Positive Long id, @NotNull Boolean onSale) {}

    /** 一次儲存多項變更；沒有變更的種類可以不給。 */
    public record Update(
        @Size(max = 500) List<@Valid @NotNull Change> inventories,
        @Size(max = 500) List<@Valid @NotNull Change> preps,
        @Size(max = 500) List<@Valid @NotNull Change> products,
        @Size(max = 500) List<@Valid @NotNull Change> toppings) {}
}
