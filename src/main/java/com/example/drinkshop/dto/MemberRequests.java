package com.example.drinkshop.dto;

import com.example.drinkshop.entity.Member;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class MemberRequests {
    private MemberRequests() {}

    /** 與建立訂單的 memberPhone 規則相同，確保建得出來的會員都能拿來點餐。 */
    public static final String PHONE_PATTERN = "[0-9+() -]{5,20}";

    public record Create(
        @NotBlank @Pattern(regexp = PHONE_PATTERN) String phone,
        @NotBlank @Size(max = 50) String name,
        @Size(max = 255) String address,
        @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 3, fraction = 7) BigDecimal lat,
        @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 7) BigDecimal lng,
        @Size(max = 255) String note) {}

    /** 電話是主鍵，不能透過修改變更；前端把整筆資料送回來時，phone 與時間欄位會被忽略。 */
    @JsonIgnoreProperties({"phone", "createdAt", "updatedAt"})
    public record Update(
        @NotBlank @Size(max = 50) String name,
        @Size(max = 255) String address,
        @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 3, fraction = 7) BigDecimal lat,
        @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 7) BigDecimal lng,
        @Size(max = 255) String note) {}

    public record Response(String phone, String name, String address, BigDecimal lat, BigDecimal lng,
                           String note, LocalDateTime createdAt, LocalDateTime updatedAt) {
        public static Response from(Member m) {
            return new Response(m.getPhone(), m.getName(), m.getAddress(), m.getLat(), m.getLng(),
                m.getNote(), m.getCreatedAt(), m.getUpdatedAt());
        }
    }
}
