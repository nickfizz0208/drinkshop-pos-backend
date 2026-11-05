package com.example.drinkshop.controller;

import com.example.drinkshop.dto.DailyWastageSummaryResponse;
import com.example.drinkshop.dto.RawMaterialWastageRequest;
import com.example.drinkshop.entity.Inventory;
import com.example.drinkshop.entity.OrderItem;
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
    private final java.time.Clock clock;

    public WastageController(WastageService wastageService, PosRepository posRepository, java.time.Clock clock) {
        this.wastageService = wastageService;
        this.posRepository = posRepository; this.clock = clock;
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
            throw com.example.drinkshop.exception.ApiException.bad("原料庫存編號不能為空");
        }
        Long staffId = resolveStaffId(request.staffId(), principal);

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
            @PathVariable Long itemId,
            @Valid @RequestBody com.example.drinkshop.dto.OrderRequests.Cancel request,
            Principal principal) {
        resolveStaffId(null, principal);

        OrderItem item = wastageService.recordOrderItemWastage(itemId, request.reason(), request.refundAmount(), principal.getName());
        return ResponseEntity.ok(item);
    }

    /**
     * 當日報廢總額統計摘要
     * GET /api/wastages/daily-summary?date=YYYY-MM-DD
     */
    @GetMapping("/daily-summary")
    public ResponseEntity<DailyWastageSummaryResponse> getDailyWastageSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate targetDate = date != null ? date : LocalDate.now(clock);
        DailyWastageSummaryResponse summary = wastageService.getDailyWastageSummary(targetDate);
        return ResponseEntity.ok(summary);
    }

    // 請求的 staffId 僅作舊版相容；實際操作人必須來自已登入帳戶。
    private Long resolveStaffId(Long ignoredStaffId, Principal principal) {
        if (principal == null) throw new com.example.drinkshop.exception.ApiException(
            org.springframework.http.HttpStatus.UNAUTHORIZED, "請先登入");
        return posRepository.staff(principal.getName()).filter(s -> s.active())
            .orElseThrow(() -> new com.example.drinkshop.exception.ApiException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "員工帳號已停用")).id();
    }
}
