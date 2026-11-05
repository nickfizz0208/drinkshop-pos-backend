package com.example.drinkshop.repository;

import com.example.drinkshop.entity.ShiftStaff;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ShiftStaffRepository extends JpaRepository<ShiftStaff, Long> {
    List<ShiftStaff> findByBusinessDateOrderByClockInAtAsc(LocalDate businessDate);
    List<ShiftStaff> findByBusinessDateAndClockOutAtIsNullOrderByClockInAtAsc(LocalDate businessDate);
    Optional<ShiftStaff> findFirstByBusinessDateAndStaffIdAndClockOutAtIsNull(LocalDate businessDate, Long staffId);
    boolean existsByBusinessDateAndStaffIdAndClockOutAtIsNull(LocalDate businessDate, Long staffId);
}
