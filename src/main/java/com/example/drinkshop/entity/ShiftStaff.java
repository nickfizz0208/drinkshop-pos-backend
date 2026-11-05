package com.example.drinkshop.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 上下班打卡紀錄（對應 shift_staff）：clockOutAt 為 null 代表目前在班。 */
@Entity
@Table(name = "shift_staff")
public class ShiftStaff {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "shift_staff_id")
    private Long id;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    @Column(name = "staff_id", nullable = false, updatable = false)
    private Long staffId;

    @Column(name = "clock_in_at", nullable = false)
    private LocalDateTime clockInAt;

    @Column(name = "clock_out_at")
    private LocalDateTime clockOutAt;

    // 最後修改這筆紀錄的店長（一般打卡不會填）
    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected ShiftStaff() {}

    public ShiftStaff(LocalDate businessDate, Long staffId, LocalDateTime clockInAt) {
        this.businessDate = businessDate; this.staffId = staffId; this.clockInAt = clockInAt;
    }

    public Long getId() { return id; }
    public LocalDate getBusinessDate() { return businessDate; }
    public Long getStaffId() { return staffId; }
    public LocalDateTime getClockInAt() { return clockInAt; }
    public void setClockInAt(LocalDateTime clockInAt) { this.clockInAt = clockInAt; }
    public LocalDateTime getClockOutAt() { return clockOutAt; }
    public void setClockOutAt(LocalDateTime clockOutAt) { this.clockOutAt = clockOutAt; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
