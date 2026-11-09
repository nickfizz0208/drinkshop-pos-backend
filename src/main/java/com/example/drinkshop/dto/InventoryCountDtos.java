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
        @Digits(integer = 10, fraction = 2, message = "實點數量最多兩位小數")
        BigDecimal countedQty) {}

    /**
     * 送出一次盤點。requestId 由畫面產生，重送同一個 requestId 不會重複記錄。
     * 經手人一律是目前登入的帳號，不接受前端指定。
     */
    public record Submit(
        @NotNull UUID requestId,
        @NotEmpty(message = "請至少盤點一項原料") @Size(max = 200) List<@Valid @NotNull Line> lines,
        @Size(max = 255, message = "備註不能超過 255 字") String note) {}

    /** 一筆盤點紀錄；diffQty = countedQty - bookQty（正數盤盈、負數盤虧）。diffCost 在沒有單位成本時為 null。 */
    public record Row(long countId, String requestId, long inventoryId, String inventoryName, String baseUnit,
                      BigDecimal countedQty, BigDecimal bookQty, BigDecimal diffQty, BigDecimal unitCost,
                      BigDecimal diffCost, long countedBy, String countedByName, LocalDateTime countedAt, String note) {}

    /**
     * 盤點頁的原料清單。allowDecimal = false 時，實點數量只能是整數（大單位包裝數一律整數，由畫面處理）。
     * 資料庫還沒有 allow_decimal 欄位時一律為 true。
     */
    public record Item(long inventoryId, String name, String baseUnit, String packUnit, BigDecimal perPack,
                       BigDecimal bookQty, BigDecimal safetyStock, BigDecimal unitCost, boolean allowDecimal) {}

    /** 盤點紀錄查詢。enabled = false 表示 016 尚未升級、盤點功能還沒開放。 */
    public record History(boolean enabled, List<Row> rows) {}
}
