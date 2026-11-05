package com.example.drinkshop.repository;

import com.example.drinkshop.entity.StaffSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface StaffScheduleRepository extends JpaRepository<StaffSchedule, Long> {
    List<StaffSchedule> findByWorkDateBetweenOrderByWorkDateAscStartTimeAscIdAsc(LocalDate from, LocalDate to);
    List<StaffSchedule> findByWorkDateAndStaffId(LocalDate workDate, Long staffId);
    boolean existsByWorkDateBetween(LocalDate from, LocalDate to);
}
