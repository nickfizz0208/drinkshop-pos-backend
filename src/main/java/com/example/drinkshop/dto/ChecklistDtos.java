package com.example.drinkshop.dto;

import com.example.drinkshop.entity.ChecklistItem;
import com.example.drinkshop.entity.ChecklistItem.Frequency;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class ChecklistDtos {
    private ChecklistDtos() {}

    /** 新增工作項目；targetCount 不填預設 1。 */
    public record CreateItem(
        @NotBlank @Size(max = 50) String name,
        @NotNull Frequency frequency,
        @Min(1) @Max(99) Integer targetCount) {}

    /** 修改工作項目：整筆送回；active = false 即停用。 */
    public record UpdateItem(
        @NotBlank @Size(max = 50) String name,
        @NotNull Frequency frequency,
        @NotNull @Min(1) @Max(99) Integer targetCount,
        @NotNull Boolean active) {}

    /** 打勾：做事的人由前端選擇（目前 POS 共用帳號登入，不用登入者）。 */
    public record CreateRecord(@NotNull Long itemId, @NotNull Long staffId) {}

    public record ItemResponse(Long itemId, String name, Frequency frequency, int targetCount, boolean active,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {
        public static ItemResponse from(ChecklistItem i) {
            return new ItemResponse(i.getId(), i.getName(), i.getFrequency(), i.getTargetCount(), i.isActive(),
                i.getCreatedAt(), i.getUpdatedAt());
        }
    }

    public record RecordResponse(Long recordId, Long itemId, Long staffId, String staffName,
                                 String period, LocalDateTime doneAt) {}

    /** 檢查表畫面的一列：這一期完成幾次、目標幾次、誰做的。 */
    public record BoardRow(Long itemId, String name, Frequency frequency, String period,
                           int targetCount, int doneCount, boolean completed, List<RecordResponse> records) {}
}
