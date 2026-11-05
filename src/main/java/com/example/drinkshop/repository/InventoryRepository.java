package com.example.drinkshop.repository;

import com.example.drinkshop.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 原料庫存資料存取層
 */
@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i WHERE i.inventoryId = :id")
    Optional<Inventory> findLocked(@org.springframework.data.repository.query.Param("id") Long id);


    // 依名稱關鍵字模糊查詢原料
    List<Inventory> findByNameContaining(String name);

    // 依完整名稱精確查詢原料
    Optional<Inventory> findByName(String name);

    // 依盤點回報人員查詢原料清單
    List<Inventory> findByReportedBy(Long reportedBy);

    // 查詢當前帳面庫存低於安全庫存的原料清單（低庫存警示）
    @Query("SELECT i FROM Inventory i WHERE i.bookQty < i.safetyStock")
    List<Inventory> findLowStockInventories();
}
