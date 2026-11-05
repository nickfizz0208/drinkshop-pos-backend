package com.example.drinkshop.repository;

import com.example.drinkshop.entity.ShiftTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ShiftTemplateRepository extends JpaRepository<ShiftTemplate, Long> {
    List<ShiftTemplate> findAllByOrderByStartTimeAscIdAsc();
    List<ShiftTemplate> findByActiveTrueOrderByStartTimeAscIdAsc();
}
