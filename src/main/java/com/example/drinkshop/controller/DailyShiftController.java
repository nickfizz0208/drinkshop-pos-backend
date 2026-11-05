package com.example.drinkshop.controller;

import com.example.drinkshop.dto.CloseShiftRequest;
import com.example.drinkshop.dto.OpenShiftRequest;
import com.example.drinkshop.dto.ShiftClosingSummaryResponse;
import com.example.drinkshop.entity.DailyShift;
import com.example.drinkshop.repository.PosRepository;
import com.example.drinkshop.service.DailyShiftService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;

/**
 * 每日班次與日結控制器（處理開班、關班清算對帳與班次查詢）
 */
@RestController
@RequestMapping("/api/daily-shifts")
public class DailyShiftController {

    private final DailyShiftService dailyShiftService;
    private final PosRepository posRepository;
    private final java.time.Clock clock;

    public DailyShiftController(DailyShiftService dailyShiftService, PosRepository posRepository, java.time.Clock clock) {
        this.dailyShiftService = dailyShiftService;
        this.posRepository = posRepository; this.clock = clock;
    }

    /**
     * 營業日開班登錄
     * POST /api/daily-shifts/open
     */
    @PostMapping("/open")
    public ResponseEntity<DailyShift> openShift(
            @Valid @RequestBody OpenShiftRequest request,
            Principal principal) {
        LocalDate date = request.businessDate() != null ? request.businessDate() : LocalDate.now(clock);
        Long staffId = resolveStaffId(request.staffId(), principal);

        DailyShift shift = dailyShiftService.openShift(
                date,
                staffId,
                request.pettyCash(),
                request.rolesJson()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(shift);
    }

    /**
     * 營業日關班 / 日結清算
     * POST /api/daily-shifts/close
     */
    @PostMapping("/close")
    public ResponseEntity<ShiftClosingSummaryResponse> closeShift(
            @Valid @RequestBody CloseShiftRequest request,
            Principal principal) {
        LocalDate date = request.businessDate() != null ? request.businessDate() : LocalDate.now(clock);
        Long staffId = resolveStaffId(request.staffId(), principal);

        ShiftClosingSummaryResponse summary = dailyShiftService.closeShift(
                date,
                staffId,
                request.countedCash(),
                request.diffReason()
        );
        return ResponseEntity.ok(summary);
    }

    /**
     * 查詢指定營業日的班次資訊
     * GET /api/daily-shifts/{businessDate}
     */
    @GetMapping("/{businessDate}")
    public ResponseEntity<DailyShift> getShift(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        DailyShift shift = dailyShiftService.getShift(businessDate);
        return ResponseEntity.ok(shift);
    }

    /**
     * 輔助方法：由登入身份取得真正的操作人，忽略請求指定的員工編號
     */
    // 請求的 staffId 僅作舊版相容；實際操作人必須來自已登入帳戶。
    private Long resolveStaffId(Long ignoredStaffId, Principal principal) {
        if (principal == null) throw new com.example.drinkshop.exception.ApiException(
            org.springframework.http.HttpStatus.UNAUTHORIZED, "請先登入");
        return posRepository.staff(principal.getName()).filter(s -> s.active())
            .orElseThrow(() -> new com.example.drinkshop.exception.ApiException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "員工帳號已停用")).id();
    }
}
