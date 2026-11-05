package com.example.drinkshop.dto;

import com.example.drinkshop.entity.ShiftTemplate;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public final class ScheduleDtos {
    private ScheduleDtos() {}

    // ---------- 班別 ----------
    public record CreateTemplate(@NotBlank @Size(max = 20) String name,
                                 @NotNull LocalTime startTime, @NotNull LocalTime endTime) {}
    public record UpdateTemplate(@NotBlank @Size(max = 20) String name,
                                 @NotNull LocalTime startTime, @NotNull LocalTime endTime, @NotNull Boolean active) {}
    public record TemplateResponse(Long templateId, String name, LocalTime startTime, LocalTime endTime, boolean active) {
        public static TemplateResponse from(ShiftTemplate t) {
            return new TemplateResponse(t.getId(), t.getName(), t.getStartTime(), t.getEndTime(), t.isActive());
        }
    }

    // ---------- 排班 ----------
    /** 有 templateId 時，未填的 startTime／endTime 取班別時間；沒有 templateId 時兩個時間必填。 */
    public record SaveEntry(@NotNull LocalDate workDate, @NotNull Long staffId, Long templateId,
                            LocalTime startTime, LocalTime endTime, @Size(max = 100) String note) {}
    public record EntryResponse(Long scheduleId, LocalDate workDate, Long staffId, String staffName,
                                Long templateId, String templateName, LocalTime startTime, LocalTime endTime,
                                String note, LocalDateTime updatedAt) {}
    /** 新增／修改的結果；warnings 只是提醒（例如同一人時段重疊），不會擋下。 */
    public record SaveResult(EntryResponse entry, List<String> warnings) {}

    // ---------- 週檢視 ----------
    /**
     * 店長拿到草稿；一般員工拿到已發布版本（published = false 時為空）。
     * modifiedAfterPublish = true：已發布的週有尚未發布的修改（只有店長會看到 true）。
     */
    public record WeekResponse(LocalDate weekStart, LocalDate weekEnd, boolean published, LocalDateTime publishedAt,
                               Long publishedBy, boolean modifiedAfterPublish, boolean canEdit,
                               List<EntryResponse> entries, List<String> warnings) {}
}
