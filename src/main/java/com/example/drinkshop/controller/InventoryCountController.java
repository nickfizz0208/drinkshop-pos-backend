package com.example.drinkshop.controller;

import com.example.drinkshop.dto.InventoryCountDtos.*;
import com.example.drinkshop.entity.Staff;
import com.example.drinkshop.exception.ApiException;
import com.example.drinkshop.repository.PosRepository;
import com.example.drinkshop.service.InventoryCountService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

/**
 * 原料盤點 API（只限個人帳號，POS 共用帳號不行；SecurityConfig 也會擋）。
 * 盤點只記錄實點數量與差異，不修改帳面庫存。
 */
@RestController
@RequestMapping("/api/inventory-counts")
public class InventoryCountController {
    private final InventoryCountService counts;
    private final PosRepository posRepository;

    public InventoryCountController(InventoryCountService counts, PosRepository posRepository) {
        this.counts = counts; this.posRepository = posRepository;
    }

    /** POST /api/inventory-counts：送出一次盤點（多項原料），經手人是目前登入的員工。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    List<Row> submit(@Valid @RequestBody Submit request, Principal principal) {
        return counts.submit(request, currentStaffId(principal));
    }

    /** GET /api/inventory-counts/items：盤點用的原料清單（含帳面數量、是否允許小數）。 */
    @GetMapping("/items")
    List<Item> items() { return counts.items(); }

    /** GET /api/inventory-counts?from=YYYY-MM-DD&to=YYYY-MM-DD：盤點紀錄，不填為最近 7 天，最多 31 天。 */
    @GetMapping
    History history(
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return counts.history(from, to);
    }

    // 經手人一律是目前登入的帳號，不接受前端指定，避免冒用別人的名義
    private long currentStaffId(Principal principal) {
        return posRepository.staff(principal.getName())
                .filter(Staff::active)
                .map(Staff::id)
                .orElseThrow(() -> ApiException.unauthorized("員工帳號已停用"));
    }
}
