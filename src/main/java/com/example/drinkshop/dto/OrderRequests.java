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
    /** 容量：中杯、大杯、瓶（030）。每杯飲品賣哪些容量看 product 的 price_m、price_l、price_bottle。 */
    public enum Size { M, L, BOTTLE }
    /**
     * 溫度冰量（038，原本的溫度、冰量合成一個）：熱、溫、常溫、完全去冰、去冰、微冰、少冰、正常冰、多冰。
     * 資料庫 conflict_rule 裡的舊規則還是用溫度（COLD/WARM/HOT）、冰量（NORMAL/LESS/LIGHT/NONE）寫的，
     * 用 legacyTemperature()、legacyIce() 換算後比對（見 RuleService）。
     */
    public enum TempIce {
        HOT, WARM, ROOM, ICE_FREE, NO_ICE, LIGHT, LESS, NORMAL, EXTRA;
        /** 熱飲、溫飲（飲品不能做熱飲時不能選）。 */
        public boolean hotOrWarm() { return this==HOT || this==WARM; }
        /** 換成舊的溫度：熱、溫照舊，其他（含常溫）都算冷飲 COLD。 */
        public String legacyTemperature() { return hotOrWarm() ? name() : "COLD"; }
        /** 換成舊的冰量：微冰、少冰、正常冰照舊；熱、溫、常溫、完全去冰、去冰都是不加冰 NONE；多冰沒有舊值，回傳 EXTRA。 */
        public String legacyIce() {
            return switch(this) { case LIGHT, LESS, NORMAL, EXTRA -> name(); default -> "NONE"; };
        }
    }
    /** 甜度（038）：無糖、0.5 分、1 分、3 分、5 分、7 分、10 分、多糖。 */
    public enum Sugar {
        NONE, S0_5, S1, S3, S5, S7, S10, EXTRA;
        /** 規則裡寫的甜度：新值直接用；舊值換算（正常→10 分、少→7 分、半→5 分、微→3 分）。不認得的值回傳 null。 */
        public static Sugar fromRule(String value) {
            return switch(value) {
                case "NORMAL" -> S10; case "LESS" -> S7; case "HALF" -> S5; case "LIGHT" -> S3;
                default -> { try { yield Sugar.valueOf(value); } catch (IllegalArgumentException ex) { yield null; } }
            };
        }
    }
    public enum ItemStatus { PENDING, MAKING, DONE, PICKED_UP, CANCELLED, WASTED }
    /** 一杯飲料的加料最多幾份（全部加料的份數加起來，040）。每種加料自己的上限在 topping.max_qty。 */
    public static final int MAX_TOPPINGS_PER_CUP = 5;
    /** 這些分類的飲品，第一份 free 加料（topping.free，046）不用錢；前端 models/pos.ts 的 FREE_TOPPING_CATEGORIES 也一樣。 */
    public static final java.util.Set<String> FREE_TOPPING_CATEGORIES = java.util.Set.of("奶茶", "鮮奶");

    // Item 代表「一種完整客製規格 × qty 杯」，toppingIds 只放額外加料的 ID。
    // 同一種加料可以加好幾份（039）：ID 在清單裡出現兩次，例如 [1,1,3]＝珍珠兩份、椰果一份。
    // 上限（040）：每種加料看 topping.max_qty，一杯全部加起來最多 MAX_TOPPINGS_PER_CUP 份。
    // optionIds（041）：不用錢的客製選項（加酸、少料、加梅子…），用法跟 toppingIds 一樣；沒傳就是沒有。
    public record Item(@NotNull @Positive Long productId, @NotNull Size size,
        @NotNull TempIce tempIce, @NotNull Sugar sugar,
        @Min(1) @Max(100) int qty,
        @NotNull @jakarta.validation.constraints.Size(max=20) List<@NotNull @Positive Long> toppingIds,
        @jakarta.validation.constraints.Size(max=255) String note,
        @jakarta.validation.constraints.Size(max=20) List<@NotNull @Positive Long> optionIds) {
        public Item {
            if (optionIds==null) optionIds=List.of();
        }
        /** 沒有客製選項的品項（舊的寫法）。 */
        public Item(Long productId,Size size,TempIce tempIce,Sugar sugar,int qty,List<Long> toppingIds,String note) {
            this(productId,size,tempIce,sugar,qty,toppingIds,note,List.of());
        }
    }
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
