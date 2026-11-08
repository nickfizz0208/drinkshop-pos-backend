package com.example.drinkshop.controller;

import com.example.drinkshop.dto.DailyWastageSummaryResponse;
import com.example.drinkshop.dto.RawMaterialWastageRequest;
import com.example.drinkshop.entity.Inventory;
import com.example.drinkshop.entity.OrderItem;
import com.example.drinkshop.entity.Staff;
import com.example.drinkshop.exception.ApiException;
import com.example.drinkshop.repository.PosRepository;
import com.example.drinkshop.service.WastageService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 報廢損耗管理控制器（處理原料報廢登記、單杯飲品作廢與當日報廢損耗統計）
 */
@RestController
@RequestMapping("/api/wastages")
public class WastageController {

    private final WastageService wastageService;
    private final PosRepository posRepository;

    public WastageController(WastageService wastageService, PosRepository posRepository) {
        this.wastageService = wastageService;
        this.posRepository = posRepository;
    }

    /**
     * 原料損耗報廢登記
     * POST /api/wastages/raw-material
     */
    @PostMapping("/raw-material")
    public ResponseEntity<Inventory> recordRawMaterialWastage(
            @Valid @RequestBody RawMaterialWastageRequest request,
            Principal principal) {
        if (request.inventoryId() == null) {
            throw new IllegalArgumentException("原料庫存編號 (inventoryId) 不能為空");
        }
        Long staffId = currentStaffId(principal);

        Inventory inventory = wastageService.recordRawMaterialWastage(
                request.inventoryId(),
                request.wastageQty(),
                request.reason(),
                staffId
        );
        return ResponseEntity.ok(inventory);
    }

    /**
     * 飲品單品項報廢作廢
     * POST /api/wastages/order-item/{itemId}
     */
    @PostMapping("/order-item/{itemId}")
    public ResponseEntity<OrderItem> recordOrderItemWastage(
            @PathVariable("itemId") Long itemId,
            @RequestBody Map<String, String> payload,
            Principal principal) {
        String reason = payload != null ? payload.getOrDefault("reason", "損耗報廢") : "損耗報廢";
        Long staffId = currentStaffId(principal);

        OrderItem item = wastageService.recordOrderItemWastage(itemId, reason, staffId);
        return ResponseEntity.ok(item);
    }

    /**
     * 當日報廢總額統計摘要
     * GET /api/wastages/daily-summary?date=YYYY-MM-DD
     */
    @GetMapping("/daily-summary")
    public ResponseEntity<DailyWastageSummaryResponse> getDailyWastageSummary(
            @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate targetDate = date != null ? date : LocalDate.now();
        DailyWastageSummaryResponse summary = wastageService.getDailyWastageSummary(targetDate);
        return ResponseEntity.ok(summary);
    }

    // 經手人一律是目前登入的帳號，不接受前端指定，避免冒用別人的名義
    private Long currentStaffId(Principal principal) {
        return posRepository.staff(principal.getName())
                .filter(Staff::active)
                .map(Staff::id)
                .orElseThrow(() -> ApiException.unauthorized("員工帳號已停用"));
    }
}

