package com.example.drinkshop.service;

import com.example.drinkshop.dto.ScheduleDtos.*;
import com.example.drinkshop.entity.ScheduleWeek;
import com.example.drinkshop.entity.ShiftTemplate;
import com.example.drinkshop.entity.StaffSchedule;
import com.example.drinkshop.exception.ApiException;
import com.example.drinkshop.repository.ScheduleWeekRepository;
import com.example.drinkshop.repository.ShiftTemplateRepository;
import com.example.drinkshop.repository.StaffScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/**
 * 排班：班別與排班的新增、修改、刪除、發布、複製上週都限店長以上；員工只看得到已發布的週。
 * 同一人同一天時段重疊只提醒（warnings），不擋下。發布後修改直接生效，並標記 modifiedAfterPublish。
 */
@Service
@Transactional
public class ScheduleService {
    private static final Set<String> MANAGER_LEVELS = Set.of("MANAGER", "HQ");

    private final ShiftTemplateRepository templates;
    private final StaffScheduleRepository schedules;
    private final ScheduleWeekRepository weeks;
    private final ShiftStaffService staff;
    private final OrderService orders;
    private final Clock clock;

    public ScheduleService(ShiftTemplateRepository templates, StaffScheduleRepository schedules,
                           ScheduleWeekRepository weeks, ShiftStaffService staff, OrderService orders, Clock clock) {
        this.templates = templates; this.schedules = schedules; this.weeks = weeks;
        this.staff = staff; this.orders = orders; this.clock = clock;
    }

    /** 該日期所在週的週一。 */
    public static LocalDate weekStart(LocalDate date) { return date.with(DayOfWeek.MONDAY); }

    // ---------- 班別 ----------

    @Transactional(readOnly = true)
    public List<TemplateResponse> listTemplates(boolean includeInactive) {
        var list = includeInactive ? templates.findAllByOrderByStartTimeAscIdAsc()
                                   : templates.findByActiveTrueOrderByStartTimeAscIdAsc();
        return list.stream().map(TemplateResponse::from).toList();
    }

    public TemplateResponse createTemplate(CreateTemplate request, String username) {
        staff.requireManager(username);
        checkTimes(request.startTime(), request.endTime());
        return TemplateResponse.from(templates.saveAndFlush(
            new ShiftTemplate(request.name().trim(), request.startTime(), request.endTime())));
    }

    /** 修改班別時間不會影響已經排好的班。 */
    public TemplateResponse updateTemplate(long id, UpdateTemplate request, String username) {
        staff.requireManager(username);
        checkTimes(request.startTime(), request.endTime());
        var t = templates.findById(id).orElseThrow(() -> ApiException.missing("找不到這個班別"));
        t.setName(request.name().trim()); t.setStartTime(request.startTime());
        t.setEndTime(request.endTime()); t.setActive(request.active());
        return TemplateResponse.from(templates.saveAndFlush(t));
    }

    // ---------- 週檢視 ----------

    @Transactional(readOnly = true)
    public WeekResponse week(LocalDate date, String username) {
        boolean manager = MANAGER_LEVELS.contains(orders.actor(username).level());
        var start = weekStart(date == null ? LocalDate.now(clock) : date);
        var end = start.plusDays(6);
        var published = weeks.findById(start);
        List<StaffSchedule> rows = (manager || published.isPresent())
            ? schedules.findByWorkDateBetweenOrderByWorkDateAscStartTimeAscIdAsc(start, end) : List.of();
        var names = templateNames();
        var entries = rows.stream().map(s -> toResponse(s, names)).toList();
        var warnings = manager ? overlapWarnings(rows) : List.<String>of();
        return new WeekResponse(start, end, published.isPresent(),
            published.map(ScheduleWeek::getPublishedAt).orElse(null),
            published.map(ScheduleWeek::getPublishedBy).orElse(null),
            published.map(ScheduleWeek::isModifiedAfterPublish).orElse(false),
            manager, entries, warnings);
    }

    // ---------- 排班 ----------

    public SaveResult create(SaveEntry request, String username) {
        var manager = staff.requireManager(username);
        var t = resolveTimes(request);
        var entry = new StaffSchedule(request.workDate(), request.staffId(), request.templateId(),
            t[0], t[1], blankToNull(request.note()), manager.id(), LocalDateTime.now(clock));
        return saved(schedules.saveAndFlush(entry));
    }

    public SaveResult update(long id, SaveEntry request, String username) {
        staff.requireManager(username);
        var entry = find(id);
        var oldWeek = weekStart(entry.getWorkDate());
        var t = resolveTimes(request);
        entry.setWorkDate(request.workDate()); entry.setStaffId(request.staffId());
        entry.setTemplateId(request.templateId()); entry.setStartTime(t[0]); entry.setEndTime(t[1]);
        entry.setNote(blankToNull(request.note())); entry.setUpdatedAt(LocalDateTime.now(clock));
        markModified(oldWeek);   // 改到別週時，原本那一週也算有修改
        return saved(schedules.saveAndFlush(entry));
    }

    public void delete(long id, String username) {
        staff.requireManager(username);
        var entry = find(id);
        markModified(weekStart(entry.getWorkDate()));
        schedules.delete(entry);
    }

    // ---------- 發布、複製上週 ----------

    /** 發布（或重新發布）該週；重新發布會清除「發布後已修改」標記。 */
    public WeekResponse publish(LocalDate date, String username) {
        var manager = staff.requireManager(username);
        var start = weekStart(date == null ? LocalDate.now(clock) : date);
        var week = weeks.findById(start).orElseGet(() -> new ScheduleWeek(start));
        week.publish(manager.id(), LocalDateTime.now(clock));
        weeks.saveAndFlush(week);
        return week(start, username);
    }

    /** 把上週的班複製到本週（日期 +7 天）。本週已經有班時不允許，避免重複排班。複製後是草稿，需要另外發布。 */
    public WeekResponse copyPreviousWeek(LocalDate date, String username) {
        var manager = staff.requireManager(username);
        var start = weekStart(date == null ? LocalDate.now(clock) : date);
        if (schedules.existsByWorkDateBetween(start, start.plusDays(6)))
            throw ApiException.conflict("本週已經有排班，只有空白的週才能複製上週");
        var previous = schedules.findByWorkDateBetweenOrderByWorkDateAscStartTimeAscIdAsc(start.minusDays(7), start.minusDays(1));
        if (previous.isEmpty()) throw ApiException.conflict("上週沒有排班可以複製");
        var now = LocalDateTime.now(clock);
        for (var s : previous) {
            schedules.save(new StaffSchedule(s.getWorkDate().plusDays(7), s.getStaffId(), s.getTemplateId(),
                s.getStartTime(), s.getEndTime(), s.getNote(), manager.id(), now));
        }
        schedules.flush();
        markModified(start);
        return week(start, username);
    }

    // ---------- 共用 ----------

    private SaveResult saved(StaffSchedule entry) {
        markModified(weekStart(entry.getWorkDate()));
        var sameDay = schedules.findByWorkDateAndStaffId(entry.getWorkDate(), entry.getStaffId());
        return new SaveResult(toResponse(entry, templateNames()), overlapWarnings(sameDay));
    }

    /** 已發布的週有任何修改，就標記「發布後已修改」。 */
    private void markModified(LocalDate weekStart) {
        weeks.findById(weekStart).ifPresent(w -> { w.markModified(); weeks.save(w); });
    }

    /** 回傳 [startTime, endTime]；有班別時未填的時間取班別時間。 */
    private LocalTime[] resolveTimes(SaveEntry request) {
        staff.requireActiveStaff(request.staffId());
        LocalTime start = request.startTime(), end = request.endTime();
        if (request.templateId() != null) {
            var t = templates.findById(request.templateId()).orElseThrow(() -> ApiException.missing("找不到這個班別"));
            if (start == null) start = t.getStartTime();
            if (end == null) end = t.getEndTime();
        }
        if (start == null || end == null) throw ApiException.bad("請選擇班別，或填寫上班與下班時間");
        checkTimes(start, end);
        return new LocalTime[]{start, end};
    }

    private void checkTimes(LocalTime start, LocalTime end) {
        if (!end.isAfter(start)) throw ApiException.bad("下班時間必須晚於上班時間");
    }

    /** 同一人同一天時段重疊的提醒。 */
    private List<String> overlapWarnings(List<StaffSchedule> rows) {
        var warnings = new ArrayList<String>();
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                var a = rows.get(i); var b = rows.get(j);
                if (a.getStaffId().equals(b.getStaffId()) && a.getWorkDate().equals(b.getWorkDate())
                        && a.getStartTime().isBefore(b.getEndTime()) && b.getStartTime().isBefore(a.getEndTime())) {
                    warnings.add(String.format("%s %s 的班時段重疊（%s–%s、%s–%s）",
                        a.getWorkDate(), staff.staffName(a.getStaffId()),
                        a.getStartTime(), a.getEndTime(), b.getStartTime(), b.getEndTime()));
                }
            }
        }
        return warnings;
    }

    private Map<Long, String> templateNames() {
        var map = new HashMap<Long, String>();
        templates.findAll().forEach(t -> map.put(t.getId(), t.getName()));
        return map;
    }

    private StaffSchedule find(long id) {
        return schedules.findById(id).orElseThrow(() -> ApiException.missing("找不到這筆排班"));
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private EntryResponse toResponse(StaffSchedule s, Map<Long, String> names) {
        return new EntryResponse(s.getId(), s.getWorkDate(), s.getStaffId(), staff.staffName(s.getStaffId()),
            s.getTemplateId(), s.getTemplateId() == null ? null : names.get(s.getTemplateId()),
            s.getStartTime(), s.getEndTime(), s.getNote(), s.getUpdatedAt());
    }
}
