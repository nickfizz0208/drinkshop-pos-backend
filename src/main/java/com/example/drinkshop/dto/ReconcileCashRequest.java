package com.example.drinkshop.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record ReconcileCashRequest(
    @NotNull @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal countedCash,
    @Size(max = 160) String diffReason
) {}
