package com.example.drinkshop.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 某天某位員工的班（對應 staff_schedule）。templateId 只是記錄來源，時間各自保存。 */
@Entity
@Table(name = "staff_schedule")
public class StaffSchedule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "schedule_id")
    private Long id;
    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;
    @Column(name = "staff_id", nullable = false)
    private Long staffId;
    @Column(name = "template_id")
    private Long templateId;
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;
    @Column(length = 100)
    private String note;
    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected StaffSchedule() {}
    public StaffSchedule(LocalDate workDate, Long staffId, Long templateId, LocalTime startTime, LocalTime endTime,
                         String note, Long createdBy, LocalDateTime updatedAt) {
        this.workDate = workDate; this.staffId = staffId; this.templateId = templateId;
        this.startTime = startTime; this.endTime = endTime; this.note = note;
        this.createdBy = createdBy; this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public LocalDate getWorkDate() { return workDate; }
    public void setWorkDate(LocalDate workDate) { this.workDate = workDate; }
    public Long getStaffId() { return staffId; }
    public void setStaffId(Long staffId) { this.staffId = staffId; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }
    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Long getCreatedBy() { return createdBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
