package com.example.drinkshop.repository;

import com.example.drinkshop.entity.PublishedSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;

public interface PublishedScheduleRepository extends JpaRepository<PublishedSchedule, Long> {
    List<PublishedSchedule> findByWorkDateBetweenOrderByWorkDateAscStartTimeAscIdAsc(LocalDate from, LocalDate to);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM PublishedSchedule p WHERE p.workDate BETWEEN :from AND :to")
    int deleteWeek(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
