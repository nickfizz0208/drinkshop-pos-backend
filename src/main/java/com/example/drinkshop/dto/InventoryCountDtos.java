package com.example.drinkshop.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 原料盤點 API 的請求與回應。數量一律是原料的基本單位（例如「份」），包裝換算在畫面完成。 */
public final class InventoryCountDtos {
    private InventoryCountDtos() {}

    /** 一項原料的實點數量。 */
    public record Line(
        @NotNull @Positive Long inventoryId,
        @NotNull(message = "請填寫實點數量")
        @DecimalMin(value = "0", message = "實點數量不能小於 0")
        @Digits(integer = 10, fraction = 2, message = "實點數量格式不正確")
        BigDecimal countedQty) {}

    /**
     * 送出一次盤點。requestId 由畫面產生，重送同一個 requestId 不會重複記錄。
     * 經手人一律是目前登入的帳號，不接受前端指定。
     */
    public record Submit(
        @NotNull UUID requestId,
        @NotNull(message = "請選擇盤點類型") @Positive Long typeId,
        @NotEmpty(message = "請至少盤點一項原料") @Size(max = 200) List<@Valid @NotNull Line> lines,
        @Size(max = 255, message = "備註不能超過 255 字") String note) {}

    /**
     * 一次盤點中的一項原料；countId 是那一次盤點的編號（同一次的每一項相同）。
     * diffQty = countedQty - bookQty（正數盤盈、負數盤虧）。diffCost 在沒有單位成本時為 null。
     */
    public record Row(long countId, String requestId, long typeId, String typeName, InputMode inputMode,
                      long inventoryId, String inventoryName, String baseUnit,
                      BigDecimal countedQty, BigDecimal bookQty, BigDecimal diffQty, BigDecimal unitCost,
                      BigDecimal diffCost, long countedBy, String countedByName, LocalDateTime countedAt, String note) {}

    /**
     * 盤點頁的原料清單。displayByPack = true 時畫面用「幾籃幾顆」顯示，零散數量要少於 perPack。allowDecimal = false 時，實點數量只能是整數（大單位包裝數一律整數，由畫面處理）。
     */
    public record Item(long inventoryId, String name, String baseUnit, String packUnit, BigDecimal perPack,
                       BigDecimal bookQty, BigDecimal safetyStock, BigDecimal unitCost,
                       boolean displayByPack) {}

    /** 盤點的輸入方式：箱數＋零散，或只填箱數（有包裝單位的原料記成 箱數 × 每箱數量）。 */
    public enum InputMode { PACK_AND_LOOSE, PACK_ONLY }

    /**
     * 盤點類型（例如大盤、小盤）。allItems = true 時包含全部原料（之後新增的也包含），itemIds 為空；
     * 否則只盤 itemIds 列出的原料。
     */
    public record CountType(long typeId, String name, InputMode inputMode, boolean allItems, boolean active,
                            List<Long> itemIds, LocalDateTime updatedAt, String updatedByName) {}

    /** 新增／修改盤點類型（只限店長、總部）。修改時 active = false 即停用。 */
    public record SaveType(
        @NotBlank(message = "請填寫類型名稱") @Size(max = 20, message = "類型名稱不能超過 20 字") String name,
        @NotNull(message = "請選擇輸入方式") InputMode inputMode,
        @NotNull Boolean allItems,
        @Size(max = 500) List<@NotNull @Positive Long> itemIds,
        Boolean active) {}

    /** 盤點紀錄查詢。enabled = false 表示 016 尚未升級、盤點功能還沒開放。 */
    public record History(boolean enabled, List<Row> rows) {}
}
