package com.example.drinkshop.entity;

import jakarta.persistence.*;
import java.time.LocalTime;

/** 固定班別（對應 shift_template），例如早班 09:30–15:00。停用不刪除。 */
@Entity
@Table(name = "shift_template")
public class ShiftTemplate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "template_id")
    private Long id;
    @Column(nullable = false, length = 20)
    private String name;
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;
    @Column(nullable = false)
    private boolean active = true;

    protected ShiftTemplate() {}
    public ShiftTemplate(String name, LocalTime startTime, LocalTime endTime) {
        this.name = name; this.startTime = startTime; this.endTime = endTime;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }
    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
