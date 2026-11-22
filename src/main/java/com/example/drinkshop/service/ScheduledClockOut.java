package com.example.drinkshop.service;

import com.example.drinkshop.repository.PosRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Service
@ConditionalOnProperty("pos.clock-out.apply-from")
public class ScheduledClockOut {
    private final PosRepository pos;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final LocalDateTime applyFrom;

    private record Punch(long id, long staffId, LocalDate date, LocalDateTime at) {}
    private record ScheduledHours(LocalDate workDate, LocalTime start, LocalTime end) {
        LocalDateTime startsAt() { return workDate.atTime(start); }
        LocalDateTime endsAt() { return workDate.plusDays(end.isBefore(start) ? 1 : 0).atTime(end); }
    }

    public ScheduledClockOut(PosRepository pos, JdbcTemplate jdbc, Clock clock,
                             @Value("${pos.clock-out.apply-from}") String applyFrom) {
        this.pos = pos;
        this.jdbc = jdbc;
        this.clock = clock;
        this.applyFrom = LocalDateTime.parse(applyFrom);
    }

    @Scheduled(fixedDelay = 60_000)
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void process() {
        var now = LocalDateTime.now(clock);
        var punches = jdbc.query("""
            SELECT shift_staff_id, staff_id, business_date, clock_in_at FROM shift_staff
            WHERE clock_out_at IS NULL AND clock_out_source IS NULL AND clock_in_at >= ?
            ORDER BY business_date, shift_staff_id
            """, (r, n) -> new Punch(r.getLong(1), r.getLong(2), r.getObject(3, LocalDate.class),
                r.getObject(4, LocalDateTime.class)), applyFrom);
        for (var punch : punches) {
            var schedule = matchingSchedule(punch);
            if (schedule != null && now.isBefore(schedule.endsAt().plusHours(2))) continue;
            if (schedule == null && !punch.date().isBefore(now.toLocalDate())) continue;
            pos.rows("SELECT status FROM daily_shift WHERE business_date=? FOR UPDATE", punch.date());
            pos.update("""
                UPDATE shift_staff SET clock_out_at=?, clock_out_source=?
                WHERE shift_staff_id=? AND clock_out_at IS NULL AND clock_out_source IS NULL
                """, schedule == null ? null : schedule.endsAt(),
                schedule == null ? "REVIEW" : "AUTO_SCHEDULE", punch.id());
        }
    }

    private ScheduledHours matchingSchedule(Punch punch) {
        var candidates = jdbc.query("""
            SELECT work_date, start_time, end_time FROM staff_schedule_published
            WHERE staff_id=? AND work_date BETWEEN ? AND ?
            """, (r, n) -> new ScheduledHours(r.getObject(1, LocalDate.class),
                r.getObject(2, LocalTime.class), r.getObject(3, LocalTime.class)),
            punch.staffId(), punch.date().minusDays(1), punch.date());
        var overlapping = candidates.stream()
            .filter(s -> !punch.at().isBefore(s.startsAt()) && punch.at().isBefore(s.endsAt())).toList();
        if (overlapping.size() == 1) return overlapping.getFirst();
        if (!overlapping.isEmpty()) return null;

        // 同日只有一個可對應的未開始班，允許員工提早到店打卡。
        var upcoming = candidates.stream()
            .filter(s -> s.workDate().equals(punch.date()) && punch.at().isBefore(s.startsAt())
                && s.endsAt().isAfter(s.startsAt())).toList();
        return upcoming.size() == 1 ? upcoming.getFirst() : null;
    }
}
