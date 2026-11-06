package com.example.drinkshop.repository;

import com.example.drinkshop.entity.ScheduleTag;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ScheduleTagRepository extends JpaRepository<ScheduleTag, Long> {
    List<ScheduleTag> findAllByOrderByIdAsc();
    List<ScheduleTag> findByActiveTrueOrderByIdAsc();
}
