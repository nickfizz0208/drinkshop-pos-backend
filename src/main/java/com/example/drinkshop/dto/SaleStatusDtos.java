package com.example.drinkshop.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 停售管理 API 的請求與回應（028）。
 * onSale 是「這一項自己有沒有停售」；sellable 是「現在實際能不能賣」：
 * 自己販售中，而且用到的原料、半成品都沒有停售。原料恢復販售後，因它停售的品項會自動恢復。
 * show（045 的 show_in_sale）：FALSE＝停售管理頁不顯示這一項；只是不顯示，連動照算，所以還是會回傳。
 */
public final class SaleStatusDtos {
    private SaleStatusDtos() {}

    /** 原料。category 是分類（029，null＝未分類）；usedByProducts／usedByToppings：直接或透過半成品用到它的飲品、加料編號。 */
    public record InventoryStatus(long inventoryId, String name, String category, boolean onSale,
                                  List<Long> usedByProducts, List<Long> usedByToppings, boolean show) {}

    /**
     * 半成品。inventoryIds、subPrepIds：用到的全部原料、半成品（043）。
     * ingredients：每一項材料與它的 link（044）；link＝TRUE 的材料停售（半成品材料會再往下看），這個半成品也算停售。
     * usedByProducts／usedByToppings：直接或透過 link＝TRUE 的上層半成品用到它的飲品、加料編號。
     */
    public record PrepStatus(long prepId, String name, String category, boolean onSale, boolean available, List<Long> inventoryIds,
                             List<Long> subPrepIds, List<PrepIngredient> ingredients, List<Long> usedByProducts, List<Long> usedByToppings,
                             boolean show) {}

    /** 半成品的一項材料：inventoryId、subPrepId 只有一個有值；link＝停售連不連動（044）。 */
    public record PrepIngredient(Long inventoryId, Long subPrepId, boolean link) {}

    /** 飲品。stoppedBy：讓它不能賣的原料、半成品名稱（自己停售不列在這裡）。 */
    public record ProductStatus(long productId, String name, String category, boolean onSale, boolean sellable,
                                List<Long> inventoryIds, List<Long> prepIds, List<String> stoppedBy, boolean show) {}

    /** 加料：指向一項原料或一項半成品。 */
    public record ToppingStatus(long toppingId, String name, boolean onSale, boolean sellable,
                                Long inventoryId, Long prepId, List<String> stoppedBy, boolean show) {}

    public record SaleStatus(List<InventoryStatus> inventories, List<PrepStatus> preps,
                             List<ProductStatus> products, List<ToppingStatus> toppings) {}

    /** 原料或半成品的分類種類。 */
    public enum MaterialKind { INVENTORY, PREP }

    /** 改一項原料或半成品的分類（029）；category 空白＝未分類。 */
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
