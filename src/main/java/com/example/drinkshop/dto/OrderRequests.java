package com.example.drinkshop.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonAlias;

/** 前端送入的資料格式（DTO）；record 是欄位固定的資料載體，驗證註記由 @Valid 觸發。 */
public final class OrderRequests {
    private OrderRequests() {}
    public enum Source { DINE_IN, TAKE_OUT, PHONE_DELIVERY, UBER_EATS }
    // UBER_EATS 保留供舊資料相容；新收款由 Service 拒絕，應放在 Source 訂單來源。
    public enum Payment { CASH, CREDIT_CARD, LINE_PAY, UBER_EATS }
    /** 容量：中杯、大杯、瓶（024）。每杯飲品賣哪些容量看 product 的 price_m、price_l、price_bottle。 */
    public enum Size { M, L, BOTTLE }
    public enum Temperature { COLD, WARM, HOT }
    public enum Sugar { NORMAL, LESS, HALF, LIGHT, NONE }
    public enum Ice { NORMAL, LESS, LIGHT, NONE }
    public enum ItemStatus { PENDING, MAKING, DONE, PICKED_UP, CANCELLED, WASTED }

    // Item 代表「一種完整客製規格 × qty 杯」，toppingIds 只放額外加料的 ID。
    public record Item(@NotNull @Positive Long productId, @NotNull Size size,
        @NotNull Temperature temperature, @NotNull Sugar sugar, @NotNull Ice ice,
        @Min(1) @Max(100) int qty,
        @NotNull @jakarta.validation.constraints.Size(max=20) List<@NotNull @Positive Long> toppingIds,
        @jakarta.validation.constraints.Size(max=255) String note) {}
    // 不接收前端售價、總額或員工 ID：後端自行計價，操作人由登入身份取得。
    // requestId 用於安全重試；unpaid=true 表示先建單扣料，稍後才收款。
    public record Create(@NotNull UUID requestId, @NotNull Source source, Payment payment,
        @Pattern(regexp="\\d{5,20}") String memberPhone,
        @DecimalMin("0") @Digits(integer=8,fraction=2) BigDecimal receivedCash,
        @JsonAlias("distanceM") @DecimalMin("0") @Digits(integer=8,fraction=2) BigDecimal distance,
        @JsonAlias("durationSeconds") @Min(0) Integer duration,
        @jakarta.validation.constraints.Size(max=255) String addressNote,
        @jakarta.validation.constraints.Size(max=255) String note,
        @NotEmpty @jakarta.validation.constraints.Size(max=100) List<@NotNull @Valid Item> items,
        boolean unpaid) {
        public Create(UUID id,Source source,Payment payment,String phone,BigDecimal cash,BigDecimal distance,Integer duration,String address,String note,List<Item> items) {
            this(id,source,payment,phone,cash,distance,duration,address,note,items,false);
        }
    }
    // managerUsername／managerPin 是舊版相容欄位，目前取消不以 PIN 授權。
    // refundAmount 為人工實際退還金額；未傳時由 Service 算可退上限。
    public record Cancel(@NotBlank @jakarta.validation.constraints.Size(max=255) String reason,
        @jakarta.validation.constraints.Size(max=50) String managerUsername,
        @jakarta.validation.constraints.Size(max=6) String managerPin,
        @DecimalMin("0") @Digits(integer=8,fraction=2) BigDecimal refundAmount) {
        public Cancel(String reason,String manager,String pin) { this(reason,manager,pin,null); }
    }
    public record Collect(@NotNull Payment payment,
        @DecimalMin("0") @Digits(integer=8,fraction=2) BigDecimal receivedCash) {}
    public record ChangeStatus(@NotNull ItemStatus status) {}
}
