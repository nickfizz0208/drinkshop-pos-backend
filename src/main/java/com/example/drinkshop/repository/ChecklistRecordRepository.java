package com.example.drinkshop.repository;

import com.example.drinkshop.entity.ChecklistRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ChecklistRecordRepository extends JpaRepository<ChecklistRecord, Long> {
    List<ChecklistRecord> findByItemIdAndPeriodOrderByDoneAtAsc(Long itemId, String period);
}
