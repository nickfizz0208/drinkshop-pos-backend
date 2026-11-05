package com.example.drinkshop.service;

import com.example.drinkshop.entity.Inventory;
import com.example.drinkshop.entity.Topping;
import com.example.drinkshop.exception.InsufficientStockException;
import com.example.drinkshop.exception.ResourceNotFoundException;
import com.example.drinkshop.exception.ToppingUnavailableException;
import com.example.drinkshop.repository.InventoryRepository;
import com.example.drinkshop.repository.ToppingRepository;
import org.springframework.stereotype.Service;
import java.time.Clock;
import com.example.drinkshop.exception.ApiException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 物料與配料管理服務（整合原料庫存即時異動與加料在售扣料邏輯）
 */
@Service
@Transactional
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final ToppingRepository toppingRepository;
    private final Clock clock;

    public InventoryService(InventoryRepository inventoryRepository, ToppingRepository toppingRepository, Clock clock) {
        this.inventoryRepository = inventoryRepository;
        this.toppingRepository = toppingRepository; this.clock = clock;
    }

    // ==========================================
    // 原料庫存管理 (Raw Material Inventory)
    // ==========================================

    /**
     * 依原料編號查詢庫存明細
     */
    @Transactional(readOnly = true)
    public Inventory getInventoryById(Long inventoryId) {
        return inventoryRepository.findById(inventoryId)
                .orElseThrow(() -> new ResourceNotFoundException("查無指定的原料庫存資料，ID: " + inventoryId));
    }

    /**
     * 查詢所有原料庫存清單
     */
    @Transactional(readOnly = true)
    public List<Inventory> getAllInventories() {
        return inventoryRepository.findAll();
    }

    /**
     * 查詢當前庫存低於安全水位的原料清單（低庫存警示）
     */
    @Transactional(readOnly = true)
    public List<Inventory> getLowStockInventories() {
        return inventoryRepository.findLowStockInventories();
    }

    /**
     * 手動校正/更新原料帳面庫存數量 (bookQty)
     */
    public Inventory updateStock(Long inventoryId, BigDecimal quantity) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) < 0) {
            throw ApiException.bad("庫存數量不能為空或小於 0");
        }
        Inventory inventory = inventoryRepository.findLocked(inventoryId)
            .orElseThrow(() -> ApiException.missing("查無指定原料"));
        inventory.setBookQty(quantity);
        return inventoryRepository.save(inventory);
    }

    /**
     * 原料庫存扣減（下單、製作出庫或報廢損耗使用）
     */
    public Inventory deductStock(Long inventoryId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.bad("扣減數量必須大於 0");
        }
        Inventory inventory = inventoryRepository.findLocked(inventoryId)
            .orElseThrow(() -> ApiException.missing("查無指定原料"));
        if (inventory.getBookQty().compareTo(amount) < 0) {
            throw ApiException.conflict("原料 [" + inventory.getName() + "] 庫存不足！現有: "
                    + inventory.getBookQty() + " " + inventory.getBaseUnit() + "，欲扣除: " + amount);
        }
        inventory.setBookQty(inventory.getBookQty().subtract(amount));
        return inventoryRepository.save(inventory);
    }

    /**
     * 原料每日定期盤點回報
     */
    public Inventory reportStock(Long inventoryId, BigDecimal reportedQty, Long staffId) {
        if (reportedQty == null || reportedQty.compareTo(BigDecimal.ZERO) < 0) {
            throw ApiException.bad("回報數量不能為空或小於 0");
        }
        Inventory inventory = inventoryRepository.findLocked(inventoryId)
            .orElseThrow(() -> ApiException.missing("查無指定原料"));
        inventory.setReportedQty(reportedQty);
        inventory.setReportedBy(staffId);
        inventory.setReportedAt(LocalDateTime.now(clock));
        return inventoryRepository.save(inventory);
    }

    // ==========================================
    // 加料配料管理 (Topping & Add-ons)
    // ==========================================

    /**
     * 依加料項目編號查詢
     */
    @Transactional(readOnly = true)
    public Topping getToppingById(Long toppingId) {
        return toppingRepository.findById(toppingId)
                .orElseThrow(() -> new ResourceNotFoundException("查無指定的加料項目，ID: " + toppingId));
    }

    /**
     * 查詢所有在售中（開放加料）的配料清單
     */
    @Transactional(readOnly = true)
    public List<Topping> getAvailableToppings() {
        return toppingRepository.findByOnSale(true);
    }

    /**
     * 更新加料項目的在售狀態（上架 / 停售）
     */
    public Topping updateToppingSaleStatus(Long toppingId, Boolean onSale) {
        Topping topping = getToppingById(toppingId);
        topping.setOnSale(onSale != null && onSale);
        return toppingRepository.save(topping);
    }

    /**
     * 檢驗加料項目是否在售且對應原料庫存是否充足
     */
    @Transactional(readOnly = true)
    public void validateToppingAvailable(Long toppingId, Integer cups) {
        Topping topping = getToppingById(toppingId);
        if (Boolean.FALSE.equals(topping.getOnSale())) {
            throw new ToppingUnavailableException("加料項目 [" + topping.getName() + "] 目前已停售");
        }

        // 計算所需的原料總量 = 單份扣料量 * 杯數
        int lineQty = (cups != null && cups > 0) ? cups : 1;
        BigDecimal requiredQty = topping.getDeductQty().multiply(BigDecimal.valueOf(lineQty));

        // 檢查對應之原料現有庫存
        Inventory inventory = getInventoryById(topping.getInventoryId());
        if (inventory.getBookQty().compareTo(requiredQty) < 0) {
            throw ApiException.conflict("加料 [" + topping.getName() + "] 所需原料 ["
                    + inventory.getName() + "] 庫存不足，無法加料！現有: "
                    + inventory.getBookQty() + "，需要: " + requiredQty);
        }
    }

    /**
     * 扣除加料所消耗的原料庫存（連動 deductStock）
     */
    public void deductToppingInventory(Long toppingId, Integer cups) {
        Topping topping = getToppingById(toppingId);
        int lineQty = (cups != null && cups > 0) ? cups : 1;
        BigDecimal totalDeduct = topping.getDeductQty().multiply(BigDecimal.valueOf(lineQty));

        deductStock(topping.getInventoryId(), totalDeduct);
    }
}

