package com.example.drinkshop.controller;

import com.example.drinkshop.dto.CloseShiftRequest;
import com.example.drinkshop.dto.OpenShiftRequest;
import com.example.drinkshop.dto.ReopenShiftRequest;
import com.example.drinkshop.dto.ShiftClosingSummaryResponse;
import com.example.drinkshop.entity.DailyShift;
import com.example.drinkshop.exception.ApiException;
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

    public DailyShiftController(DailyShiftService dailyShiftService, PosRepository posRepository) {
        this.dailyShiftService = dailyShiftService;
        this.posRepository = posRepository;
    }

    /**
     * 營業日開班登錄
     * POST /api/daily-shifts/open
     */
    @PostMapping("/open")
    public ResponseEntity<DailyShift> openShift(
            @Valid @RequestBody OpenShiftRequest request,
            Principal principal) {
        LocalDate date = request.businessDate() != null ? request.businessDate() : LocalDate.now();
        // 開班一定要選「誰開的」：開班 API 只限 POS 共用帳號呼叫，而 POS 帳號不是人，
        // 所以開班人員必須由畫面選擇，而且要是在職的員工本人
        Long staffId = requirePerson(request.staffId(), "開班");

        DailyShift shift;
        try {
            shift = dailyShiftService.openShift(date, staffId, request.pettyCash(), request.rolesJson());
        } catch (IllegalStateException alreadyOpened) {
            throw ApiException.conflict("今天已經開班，不可重複開班");
        }
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
        LocalDate date = request.businessDate() != null ? request.businessDate() : LocalDate.now();
        // 關班和開班一樣，要選「誰關的」，必須是在職員工本人
        Long staffId = requirePerson(request.staffId(), "關班");

        ShiftClosingSummaryResponse summary;
        try {
            summary = dailyShiftService.closeShift(date, staffId, request.countedCash(), request.diffReason());
        } catch (com.example.drinkshop.exception.ResourceNotFoundException notOpened) {
            throw ApiException.conflict("今天還沒開班，不能關班");
        } catch (IllegalStateException alreadyClosed) {
            throw ApiException.conflict("今天已經關班，不可重複關班");
        }
        return ResponseEntity.ok(summary);
    }

    /**
     * 重新開班：取消關班、接續營業（開班人員與零用金沿用原本的）
     * POST /api/daily-shifts/reopen
     */
    @PostMapping("/reopen")
    public ResponseEntity<DailyShift> reopenShift(@RequestBody(required = false) ReopenShiftRequest request) {
        LocalDate date = request != null && request.businessDate() != null ? request.businessDate() : LocalDate.now();
        try {
            return ResponseEntity.ok(dailyShiftService.reopenShift(date));
        } catch (IllegalStateException notClosed) {
            boolean exists = posRepository.rows("SELECT 1 FROM daily_shift WHERE business_date=?", date).size() > 0;
            throw ApiException.conflict(exists ? "今天還在營業中，不用重新開班" : "今天還沒開班，請直接開班");
        }
    }

    /**
     * 查詢指定營業日的班次資訊
     * GET /api/daily-shifts/{businessDate}
     */
    @GetMapping("/{businessDate}")
    public ResponseEntity<DailyShift> getShift(
            @PathVariable("businessDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        DailyShift shift = dailyShiftService.getShift(businessDate);
        return ResponseEntity.ok(shift);
    }

    // 開班、關班人員：一定要選，而且要是在職員工本人（POS 共用帳號不是人）
    private Long requirePerson(Long staffId, String action) {
        if (staffId == null) throw ApiException.bad("請選擇" + action + "人員");
        var person = posRepository.rows("SELECT level, active FROM staff_account WHERE staff_id=?", staffId);
        if (person.isEmpty() || !isTrue(person.get(0).get("active")) || "POS".equals(person.get(0).get("level")))
            throw ApiException.bad(action + "人員必須是在職員工");
        return staffId;
    }

    // MySQL 的 BOOLEAN 可能讀成 Boolean 或數字 1
    private static boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof Number n && n.intValue() == 1);
    }

    /**
     * 輔助方法：優先採用請求中的 staffId，若無則自目前登入的 Principal 解析員工編號，預設為 1L
     */
    private Long resolveStaffId(Long requestedStaffId, Principal principal) {
        if (requestedStaffId != null && requestedStaffId > 0) {
            return requestedStaffId;
        }
        if (principal != null) {
            return posRepository.staff(principal.getName())
                    .map(s -> s.id())
                    .orElse(1L);
        }
        return 1L;
    }
}

