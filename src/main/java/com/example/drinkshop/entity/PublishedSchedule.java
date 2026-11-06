package com.example.drinkshop.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalTime;

/** 已發布的排班（對應 staff_schedule_published）：員工看到的版本，只在店長按發布時整週覆蓋。 */
@Entity
@Table(name = "staff_schedule_published")
public class PublishedSchedule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "published_id")
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

    @Column(name = "tag_ids", length = 200)
    private String tagIds;

    protected PublishedSchedule() {}
    /** 從草稿複製一份。 */
    public PublishedSchedule(StaffSchedule draft) {
        this.workDate = draft.getWorkDate(); this.staffId = draft.getStaffId(); this.templateId = draft.getTemplateId();
        this.startTime = draft.getStartTime(); this.endTime = draft.getEndTime(); this.note = draft.getNote();
        this.tagIds = draft.getTagIds();
    }

    public Long getId() { return id; }
    public LocalDate getWorkDate() { return workDate; }
    public Long getStaffId() { return staffId; }
    public Long getTemplateId() { return templateId; }
    public LocalTime getStartTime() { return startTime; }
    public LocalTime getEndTime() { return endTime; }
    public String getNote() { return note; }
    public String getTagIds() { return tagIds; }
}
