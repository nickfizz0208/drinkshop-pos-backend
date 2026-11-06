package com.example.drinkshop.entity;

import jakarta.persistence.*;

/** 排班標籤（對應 schedule_tag），例如開班、支援、收班、休息。不帶時間；停用不刪除，舊班表仍顯示原標籤。 */
@Entity
@Table(name = "schedule_tag")
public class ScheduleTag {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tag_id")
    private Long id;

    @Column(nullable = false, length = 20)
    private String name;

    /** 標籤說明，例如「這段班可以中間休息半小時」；可以空白。 */
    @Column(length = 100)
    private String description;

    @Column(nullable = false)
    private boolean active = true;

    protected ScheduleTag() {}

    public ScheduleTag(String name, String description) {
        this.name = name; this.description = description;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
