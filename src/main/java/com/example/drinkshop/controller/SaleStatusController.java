package com.example.drinkshop.controller;

import com.example.drinkshop.dto.SaleStatusDtos.*;
import com.example.drinkshop.service.SaleStatusService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 停售管理（022）。查看：登入即可；停售：門市 POS、店長、總部；改分類（023）：店長、總部（SecurityConfig 檢查）。
 * 原料、半成品停售時，用到它的飲品與加料會一起停售；恢復販售時一起恢復。
 */
@RestController
@RequestMapping("/api/sale-status")
public class SaleStatusController {
    private final SaleStatusService sale;
    public SaleStatusController(SaleStatusService sale) { this.sale = sale; }

    // GET /api/sale-status：全部原料、半成品、飲品、加料的停售狀態與彼此的關聯
    @GetMapping
    SaleStatus status() { return sale.status(); }

    // PUT /api/sale-status：{"inventories":[{"id":6,"onSale":false}],"products":[{"id":5,"onSale":true}]}
    @PutMapping
    SaleStatus update(@Valid @RequestBody Update request) { return sale.update(request); }

    // PUT /api/sale-status/categories：{"kind":"INVENTORY","id":6,"category":"底料"}，只限店長、總部
    @PutMapping("/categories")
    SaleStatus category(@Valid @RequestBody CategoryChange request) { return sale.updateCategory(request); }
}
