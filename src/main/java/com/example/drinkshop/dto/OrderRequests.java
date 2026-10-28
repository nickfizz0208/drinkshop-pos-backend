package com.example.drinkshop.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonAlias;

public final class OrderRequests {
    private OrderRequests() {}
    public enum Source { DINE_IN, TAKE_OUT, PHONE_DELIVERY, UBER_EATS }
    public enum Payment { CASH, CREDIT_CARD, LINE_PAY, UBER_EATS }
    public enum Size { M, L }
    public enum Temperature { COLD, WARM, HOT }
    public enum Sugar { NORMAL, LESS, HALF, LIGHT, NONE }
    public enum Ice { NORMAL, LESS, LIGHT, NONE }
    public enum ItemStatus { PENDING, MAKING, DONE, PICKED_UP, CANCELLED, WASTED }

    public record Item(@NotNull @Positive Long productId, @NotNull Size size,
        @NotNull Temperature temperature, @NotNull Sugar sugar, @NotNull Ice ice,
        @Min(1) @Max(100) int qty,
        @NotNull @jakarta.validation.constraints.Size(max=20) List<@NotNull @Positive Long> toppingIds,
        @jakarta.validation.constraints.Size(max=255) String note) {}
    // No client price/total/staffId: server owns pricing and identity.
    public record Create(@NotNull UUID requestId, @NotNull Source source, Payment payment,
        @Pattern(regexp="[0-9+() -]{5,20}") String memberPhone,
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
