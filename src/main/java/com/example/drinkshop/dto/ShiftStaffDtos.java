package com.example.drinkshop.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public final class ShiftStaffDtos {
    private ShiftStaffDtos() {}

    /** 上班／下班打卡：選名字即可。 */
    public record Clock(@NotNull Long staffId) {}

    /** 店長修改打卡時間；clockOutAt 為 null 代表改回「還在班」。 */
    public record Update(@NotNull LocalDateTime clockInAt, LocalDateTime clockOutAt) {}

    /**
     * 打卡頁用：今天的營業日、開班狀態（NONE 未開班／OPEN／CLOSED）與紀錄。
     * canViewAll = true（店長、總部）時 records 是全部；否則只有在班中的人和自己的紀錄。
     */
    public record Today(LocalDate businessDate, String shiftStatus, java.util.List<Response> records, boolean canViewAll) {}

    /**
     * scheduledStartTime / scheduledEndTime：店長排班時間（若有排班）
     * closeType：SELF 自行打卡、AUTO_SCHEDULE 依排班補登、REVIEW 待店長確認、
     * AUTO_SHIFT_CLOSE 隨門市關班、AUTO_MIDNIGHT 舊版午夜補登、MANAGER 店長修改。
     */
    public record Response(Long shiftStaffId, LocalDate businessDate, Long staffId, String staffName,
                           LocalDateTime clockInAt, LocalDateTime clockOutAt, boolean onDuty,
                           Long updatedBy, LocalDateTime updatedAt,
                           LocalTime scheduledStartTime, LocalTime scheduledEndTime,
                           String closeType) {}
}
