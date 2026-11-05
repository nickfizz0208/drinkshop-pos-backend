package com.example.drinkshop.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 檢查表工作項目（對應 checklist_item）：由店長以上新增、修改、停用，不直接刪除。 */
@Entity
@Table(name = "checklist_item")
public class ChecklistItem {
    public enum Frequency { DAILY, WEEKLY }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id")
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Frequency frequency;

    // 每一期需要完成的次數，預設 1
    @Column(name = "target_count", nullable = false)
    private int targetCount = 1;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected ChecklistItem() {}

    public ChecklistItem(String name, Frequency frequency, int targetCount, Long createdBy) {
        this.name = name; this.frequency = frequency; this.targetCount = targetCount; this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Frequency getFrequency() { return frequency; }
    public void setFrequency(Frequency frequency) { this.frequency = frequency; }
    public int getTargetCount() { return targetCount; }
    public void setTargetCount(int targetCount) { this.targetCount = targetCount; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Long getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
