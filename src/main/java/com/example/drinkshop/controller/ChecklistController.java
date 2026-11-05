package com.example.drinkshop.controller;

import com.example.drinkshop.dto.ChecklistDtos.*;
import com.example.drinkshop.service.ChecklistService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 檢查表 API
 * 工作項目：GET 所有人可看；POST／PUT 限店長或總部（停用請用 PUT active=false）。
 * 打勾紀錄：GET 檢查表畫面、POST 打勾、DELETE 取消打勾。
 */
@RestController
@RequestMapping("/api/checklist")
public class ChecklistController {
    private final ChecklistService checklist;
    public ChecklistController(ChecklistService checklist) { this.checklist = checklist; }

    /** GET /api/checklist/items?includeInactive=true */
    @GetMapping("/items")
    List<ItemResponse> items(@RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive) {
        return checklist.listItems(includeInactive);
    }

    /** POST /api/checklist/items */
    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    ItemResponse createItem(@Valid @RequestBody CreateItem request, Principal principal) {
        return checklist.createItem(request, principal.getName());
    }

    /** PUT /api/checklist/items/{itemId} */
    @PutMapping("/items/{itemId}")
    ItemResponse updateItem(@PathVariable("itemId") long itemId, @Valid @RequestBody UpdateItem request, Principal principal) {
        return checklist.updateItem(itemId, request, principal.getName());
    }

    /** GET /api/checklist?date=YYYY-MM-DD（不填為今天）：每個項目在該期完成幾次、誰做的。 */
    @GetMapping
    List<BoardRow> board(@RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return checklist.board(date);
    }

    /** POST /api/checklist/records { itemId, staffId? }：POS 帳號要帶 staffId，個人帳號記成自己 */
    @PostMapping("/records")
    @ResponseStatus(HttpStatus.CREATED)
    RecordResponse check(@Valid @RequestBody CreateRecord request, Principal principal) {
        return checklist.check(request, principal.getName());
    }

    /** DELETE /api/checklist/records/{recordId} */
    @DeleteMapping("/records/{recordId}")
    Map<String, Object> uncheck(@PathVariable("recordId") long recordId, Principal principal) {
        checklist.uncheck(recordId, principal.getName());
        return Map.of("success", true);
    }
}
