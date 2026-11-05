package com.example.drinkshop.repository;

import com.example.drinkshop.entity.ChecklistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ChecklistItemRepository extends JpaRepository<ChecklistItem, Long> {
    List<ChecklistItem> findAllByOrderByFrequencyAscIdAsc();
    List<ChecklistItem> findByActiveTrueOrderByFrequencyAscIdAsc();
}
