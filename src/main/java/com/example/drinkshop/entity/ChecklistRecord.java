package com.example.drinkshop.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 檢查表完成紀錄（對應 checklist_record）：每打一次勾新增一筆，取消打勾就刪除該筆。 */
@Entity
@Table(name = "checklist_record")
public class ChecklistRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "record_id")
    private Long id;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "staff_id", nullable = false)
    private Long staffId;

    // DAILY：2026-11-05；WEEKLY：2026-W45
    @Column(nullable = false, length = 10)
    private String period;

    @Column(name = "done_at", nullable = false)
    private LocalDateTime doneAt;

    protected ChecklistRecord() {}

    public ChecklistRecord(Long itemId, Long staffId, String period, LocalDateTime doneAt) {
        this.itemId = itemId; this.staffId = staffId; this.period = period; this.doneAt = doneAt;
    }

    public Long getId() { return id; }
    public Long getItemId() { return itemId; }
    public Long getStaffId() { return staffId; }
    public String getPeriod() { return period; }
    public LocalDateTime getDoneAt() { return doneAt; }
}
