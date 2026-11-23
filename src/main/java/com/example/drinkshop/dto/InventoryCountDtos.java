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
                       boolean displayByPack, String category) {}

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

    /** 半成品所屬的原料配方資訊。 */
    public record PrepIngredientDto(
        long inventoryId,
        String name,
        BigDecimal qty,
        String unit
    ) {}

    /** 半成品單次新增製作量（煮茶／備料）的時間紀錄。 */
    public record PrepProductionLogDto(
        long logId,
        long prepId,
        String prepName,
        BigDecimal producedQty,
        String unit,
        List<PrepIngredientDto> usedIngredients,
        long staffId,
        String staffName,
        LocalDateTime createdAt,
        String note
    ) {}

    /** 剩餘半成品轉報廢的時間紀錄（與生原料出問題的 raw_material_wastage 分開）。 */
    public record PrepWastageLogDto(
        long logId,
        long prepId,
        String prepName,
        BigDecimal wastedQty,
        String unit,
        long staffId,
        String staffName,
        LocalDateTime createdAt,
        String note
    ) {}

    /**
     * 半成品備料盤點項目：
     * 剩餘半成品 (theoreticalQty) = 今日累計製作量 (currentQty) - 今日訂單消耗 (orderConsumed) - 今日半成品報廢 (wastedQty)
     */
    public record PrepCountItem(
        long prepId,
        String name,
        String category,
        String unit,
        BigDecimal currentQty,      // 今日累計製作量
        BigDecimal orderConsumed,   // 今日訂單消耗
        BigDecimal wastedQty,       // 今日已處理半成品報廢量
        BigDecimal theoreticalQty,  // 目前剩餘半成品量 = 今日累計製作量 - 今日訂單消耗 - 今日半成品報廢量
        List<PrepIngredientDto> ingredients,
        List<PrepProductionLogDto> productionLogs,
        List<PrepWastageLogDto> wastageLogs
    ) {}

    /** 新增單項半成品今日製作量。 */
    public record PrepProductionLineSubmit(
        @NotNull @Positive Long prepId,
        @NotNull(message = "請輸入今天的製作量")
        @DecimalMin(value = "0.01", message = "製作量必須大於 0")
        BigDecimal producedQty
    ) {}

    /** 送出今日半成品新增製作量（支援單項或多項、同品項一日多次新增）。 */
    public record PrepProductionSubmit(
        @NotEmpty(message = "請至少輸入一項半成品的今日製作量")
        List<@Valid @NotNull PrepProductionLineSubmit> lines,
        @Size(max = 255) String note
    ) {}

    /** 新增單項剩餘半成品轉報廢量。 */
    public record PrepWastageLineSubmit(
        @NotNull @Positive Long prepId,
        @NotNull(message = "請輸入半成品報廢量")
        @DecimalMin(value = "0.01", message = "報廢量必須大於 0")
        BigDecimal wastedQty
    ) {}

    /** 送出剩餘半成品報廢處理（與生原料報廢回報區隔）。 */
    public record PrepWastageSubmit(
        @NotEmpty(message = "請至少輸入一項要報廢的半成品數量")
        List<@Valid @NotNull PrepWastageLineSubmit> lines,
        @Size(max = 255) String note
    ) {}

    /** 提交半成品盤點的一項實點資料（向下相容）。 */
    public record PrepCountLineSubmit(
        @NotNull @Positive Long prepId,
        @NotNull @DecimalMin("0") BigDecimal currentQty,
        @NotNull @DecimalMin("0") BigDecimal countedQty
    ) {}

    /** 送出半成品盤點（向下相容）。 */
    public record PrepCountSubmit(
        @NotNull UUID requestId,
        @NotEmpty List<@Valid @NotNull PrepCountLineSubmit> lines,
        @Size(max = 255) String note
    ) {}

    /** 半成品盤點核對結果：差距 = 理論的量 - 實際員工盤點的量。 */
    public record PrepCountResult(
        long prepId,
        String name,
        String category,
        String unit,
        BigDecimal currentQty,
        BigDecimal orderConsumed,
        BigDecimal wastedQty,
        BigDecimal theoreticalQty,
        BigDecimal countedQty,
        BigDecimal diffQty
    ) {}
}
