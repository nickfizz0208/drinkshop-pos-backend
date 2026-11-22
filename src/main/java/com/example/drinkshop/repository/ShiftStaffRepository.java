package com.example.drinkshop.repository;

import com.example.drinkshop.entity.ShiftStaff;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ShiftStaffRepository extends JpaRepository<ShiftStaff, Long> {
    List<ShiftStaff> findByBusinessDateOrderByClockInAtAsc(LocalDate businessDate);
    List<ShiftStaff> findByBusinessDateAndClockOutAtIsNullAndClockOutSourceIsNull(LocalDate businessDate);
    List<ShiftStaff> findByBusinessDateAndClockOutAtIsNullAndClockOutSourceIsNullOrderByClockInAtAsc(LocalDate businessDate);
    Optional<ShiftStaff> findFirstByStaffIdAndBusinessDateGreaterThanEqualAndClockOutAtIsNullAndClockOutSourceIsNullOrderByClockInAtDesc(Long staffId, LocalDate since);
    List<ShiftStaff> findByStaffIdAndBusinessDateBetweenOrderByBusinessDateDescClockInAtDescIdDesc(Long staffId, LocalDate from, LocalDate to);
    List<ShiftStaff> findByBusinessDateBetweenOrderByBusinessDateDescClockInAtDescIdDesc(LocalDate from, LocalDate to);
    boolean existsByBusinessDateAndStaffIdAndClockOutAtIsNullAndClockOutSourceIsNull(LocalDate businessDate, Long staffId);
}
