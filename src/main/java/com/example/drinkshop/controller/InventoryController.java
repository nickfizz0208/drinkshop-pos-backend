package com.example.drinkshop.controller;

import com.example.drinkshop.dto.StockQuantityRequest;
import com.example.drinkshop.entity.Inventory;
import com.example.drinkshop.entity.Topping;
import com.example.drinkshop.entity.Staff;
import com.example.drinkshop.exception.ApiException;
import com.example.drinkshop.repository.PosRepository;
import com.example.drinkshop.service.InventoryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * 原料庫存與配料管理控制器（提供原料庫存查詢、校正、盤點與加料配料狀態管理）
 */
@RestController
@RequestMapping("/api/inventories")
public class InventoryController {

    private final InventoryService inventoryService;
    private final PosRepository posRepository;

    public InventoryController(InventoryService inventoryService, PosRepository posRepository) {
        this.inventoryService = inventoryService;
        this.posRepository = posRepository;
    }

    // ==========================================
    // 原料庫存端點 (Raw Materials)
    // ==========================================

    /**
     * 查詢所有原料庫存清單
     * GET /api/inventories
     */
    @GetMapping
    public ResponseEntity<List<Inventory>> getAllInventories() {
        return ResponseEntity.ok(inventoryService.getAllInventories());
    }

    /**
     * 依原料 ID 取得單筆庫存資訊
     * GET /api/inventories/{inventoryId}
     */
    @GetMapping("/{inventoryId}")
    public ResponseEntity<Inventory> getInventoryById(@PathVariable("inventoryId") Long inventoryId) {
        return ResponseEntity.ok(inventoryService.getInventoryById(inventoryId));
    }

    /**
     * 查詢低於安全水位的原料清單（低庫存警示）
     * GET /api/inventories/low-stock
     */
    @GetMapping("/low-stock")
    public ResponseEntity<List<Inventory>> getLowStockInventories() {
        return ResponseEntity.ok(inventoryService.getLowStockInventories());
    }

    /**
     * 手動校正原料庫存帳面數量 (bookQty)
     * PUT /api/inventories/{inventoryId}/stock
     */
    @PutMapping("/{inventoryId}/stock")
    public ResponseEntity<Inventory> updateStock(
            @PathVariable("inventoryId") Long inventoryId,
            @Valid @RequestBody StockQuantityRequest request) {
        Inventory inventory = inventoryService.updateStock(inventoryId, request.quantity());
        return ResponseEntity.ok(inventory);
    }

    /**
     * 手動扣除原料庫存（出庫/測試）
     * POST /api/inventories/{inventoryId}/deduct
     */
    @PostMapping("/{inventoryId}/deduct")
    public ResponseEntity<Inventory> deductStock(
            @PathVariable("inventoryId") Long inventoryId,
            @Valid @RequestBody StockQuantityRequest request) {
        Inventory inventory = inventoryService.deductStock(inventoryId, request.quantity());
        return ResponseEntity.ok(inventory);
    }

    /**
     * 原料庫存定期盤點回報
     * POST /api/inventories/{inventoryId}/report
     */
    @PostMapping("/{inventoryId}/report")
    public ResponseEntity<Inventory> reportStock(
            @PathVariable("inventoryId") Long inventoryId,
            @Valid @RequestBody StockQuantityRequest request,
            Principal principal) {
        Long staffId = currentStaffId(principal);
        Inventory inventory = inventoryService.reportStock(inventoryId, request.quantity(), staffId);
        return ResponseEntity.ok(inventory);
    }

    // ==========================================
    // 加料配料端點 (Toppings)
    // ==========================================

    /**
     * 查詢所有在售開放加料清單
     * GET /api/inventories/toppings
     */
    @GetMapping("/toppings")
    public ResponseEntity<List<Topping>> getAvailableToppings() {
        return ResponseEntity.ok(inventoryService.getAvailableToppings());
    }

    /**
     * 依加料 ID 查詢加料資訊
     * GET /api/inventories/toppings/{toppingId}
     */
    @GetMapping("/toppings/{toppingId}")
    public ResponseEntity<Topping> getToppingById(@PathVariable("toppingId") Long toppingId) {
        return ResponseEntity.ok(inventoryService.getToppingById(toppingId));
    }

    /**
     * 更新加料項目的在售狀態（上架 true / 停售 false）
     * PUT /api/inventories/toppings/{toppingId}/status?onSale=true
     */
    @PutMapping("/toppings/{toppingId}/status")
    public ResponseEntity<Topping> updateToppingSaleStatus(
            @PathVariable("toppingId") Long toppingId,
            @RequestParam("onSale") Boolean onSale) {
        Topping topping = inventoryService.updateToppingSaleStatus(toppingId, onSale);
        return ResponseEntity.ok(topping);
    }

    // 經手人一律是目前登入的帳號，不接受前端指定，避免冒用別人的名義
    private Long currentStaffId(Principal principal) {
        return posRepository.staff(principal.getName())
                .filter(Staff::active)
                .map(Staff::id)
                .orElseThrow(() -> ApiException.unauthorized("員工帳號已停用"));
    }
}

