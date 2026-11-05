package com.example.drinkshop.controller;

import com.example.drinkshop.dto.ShiftStaffDtos.*;
import com.example.drinkshop.service.ShiftStaffService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 上下班打卡 API
 * 打卡、查詢：所有人；修改、刪除：限店長或總部。
 */
@RestController
@RequestMapping("/api/shift-staff")
public class ShiftStaffController {
    private final ShiftStaffService service;
    public ShiftStaffController(ShiftStaffService service) { this.service = service; }

    /** POST /api/shift-staff/clock-in { staffId } */
    @PostMapping("/clock-in")
    @ResponseStatus(HttpStatus.CREATED)
    Response clockIn(@Valid @RequestBody Clock request) { return service.clockIn(request.staffId()); }

    /** POST /api/shift-staff/clock-out { staffId } */
    @PostMapping("/clock-out")
    Response clockOut(@Valid @RequestBody Clock request) { return service.clockOut(request.staffId()); }

    /** GET /api/shift-staff/today：今天開班狀態＋打卡紀錄（打卡頁用） */
    @GetMapping("/today")
    Today today(Principal principal) { return service.todayStatus(principal.getName()); }

    /** GET /api/shift-staff/mine?month=2026-11（不填為本月）：自己的打卡紀錄 */
    @GetMapping("/mine")
    List<Response> mine(@RequestParam(name = "month", required = false)
                        @DateTimeFormat(pattern = "yyyy-MM") java.time.YearMonth month, Principal principal) {
        return service.mine(month, principal.getName());
    }

    /** GET /api/shift-staff/on-duty：目前在班的人（checklist 選人用） */
    @GetMapping("/on-duty")
    List<Response> onDuty() { return service.onDuty(); }

    /** GET /api/shift-staff?date=YYYY-MM-DD（不填為今天）：店長看全部，其他人只看自己的 */
    @GetMapping
    List<Response> list(@RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                        Principal principal) {
        return service.list(date, principal.getName());
    }

    /** PUT /api/shift-staff/{id} { clockInAt, clockOutAt }：店長修改 */
    @PutMapping("/{id}")
    Response update(@PathVariable("id") long id, @Valid @RequestBody Update request, Principal principal) {
        return service.update(id, request, principal.getName());
    }

    /** DELETE /api/shift-staff/{id}：店長刪除打錯的紀錄 */
    @DeleteMapping("/{id}")
    Map<String, Object> delete(@PathVariable("id") long id, Principal principal) {
        service.delete(id, principal.getName());
        return Map.of("success", true);
    }
}
