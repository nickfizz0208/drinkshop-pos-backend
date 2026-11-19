package com.example.drinkshop.repository;

import com.example.drinkshop.entity.DailyShift;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 每日班次 / 日結資料存取層
 */
@Repository
public interface DailyShiftRepository extends JpaRepository<DailyShift, LocalDate> {

    // 依營業狀態查詢班次（例如 OPEN、CLOSED）
    List<DailyShift> findByStatus(String status);

    // 查詢在特定日期之前且符合狀態的班次（用於自動處理逾期未關班）
    List<DailyShift> findByBusinessDateBeforeAndStatus(LocalDate businessDate, String status);

    // 依營業日期區間查詢班次紀錄
    List<DailyShift> findByBusinessDateBetween(LocalDate startDate, LocalDate endDate);

    // 依開班人員查詢班次紀錄
    List<DailyShift> findByOpenedBy(Long openedBy);

    // 依關班人員查詢班次紀錄
    List<DailyShift> findByClosedBy(Long closedBy);

    // 查詢最近一筆營業日班次
    Optional<DailyShift> findTopByOrderByBusinessDateDesc();
}
