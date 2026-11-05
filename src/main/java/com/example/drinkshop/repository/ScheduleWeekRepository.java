package com.example.drinkshop.repository;

import com.example.drinkshop.entity.ScheduleWeek;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;

public interface ScheduleWeekRepository extends JpaRepository<ScheduleWeek, LocalDate> {}
