package com.example.drinkshop.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 已發布的排班週（對應 schedule_week）；weekStart 為該週週一。 */
@Entity
@Table(name = "schedule_week")
public class ScheduleWeek {
    @Id
    @Column(name = "week_start")
    private LocalDate weekStart;
    @Column(name = "published_by", nullable = false)
    private Long publishedBy;
    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt;
    @Column(name = "modified_after_publish", nullable = false)
    private boolean modifiedAfterPublish;

    protected ScheduleWeek() {}
    public ScheduleWeek(LocalDate weekStart) { this.weekStart = weekStart; }

    public LocalDate getWeekStart() { return weekStart; }
    public Long getPublishedBy() { return publishedBy; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public boolean isModifiedAfterPublish() { return modifiedAfterPublish; }
    public void publish(Long by, LocalDateTime at) { publishedBy = by; publishedAt = at; modifiedAfterPublish = false; }
    public void markModified() { modifiedAfterPublish = true; }
}
