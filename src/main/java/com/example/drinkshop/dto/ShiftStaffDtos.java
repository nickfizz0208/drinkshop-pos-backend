package com.example.drinkshop.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalDateTime;

public final class ShiftStaffDtos {
    private ShiftStaffDtos() {}

    /** 上班／下班打卡：選名字即可。 */
    public record Clock(@NotNull Long staffId) {}

    /** 店長修改打卡時間；clockOutAt 為 null 代表改回「還在班」。 */
    public record Update(@NotNull LocalDateTime clockInAt, LocalDateTime clockOutAt) {}

    public record Response(Long shiftStaffId, LocalDate businessDate, Long staffId, String staffName,
                           LocalDateTime clockInAt, LocalDateTime clockOutAt, boolean onDuty,
                           Long updatedBy, LocalDateTime updatedAt) {}
}
