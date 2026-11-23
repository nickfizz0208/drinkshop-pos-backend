package com.example.drinkshop.controller;

import com.example.drinkshop.dto.PrepBatchDtos.*;
import com.example.drinkshop.entity.Staff;
import com.example.drinkshop.exception.ApiException;
import com.example.drinkshop.repository.PosRepository;
import com.example.drinkshop.service.PrepBatchService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

/**
 * 備料（050）：登記誰煮了什麼半成品、幾份。門市 POS 共用帳號（要選煮的人）與店員、店長等個人帳號（記成自己）都可以用；
 * 總部帳號不用（權限見 SecurityConfig）。
 */
@RestController
@RequestMapping("/api/prep-batches")
public class PrepBatchController {
    private final PrepBatchService batches;
    private final PosRepository pos;
    public PrepBatchController(PrepBatchService batches, PosRepository pos) { this.batches = batches; this.pos = pos; }

    /** GET /api/prep-batches/preps：可以登記的半成品。 */
    @GetMapping("/preps")
    List<PrepOption> preps() { return batches.preps(); }

    /** GET /api/prep-batches?date=YYYY-MM-DD：某一天的備料紀錄，不給＝今天。 */
    @GetMapping
    Day day(@RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return batches.day(date);
    }

    /** POST /api/prep-batches：登記一筆備料。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Row create(@Valid @RequestBody Create request, Principal principal) {
        Staff current = pos.staff(principal.getName()).filter(Staff::active)
            .orElseThrow(() -> ApiException.unauthorized("員工帳號已停用"));
        return batches.create(request, current);
    }
}
