package com.example.drinkshop.controller;

import com.example.drinkshop.dto.OrderRequests.*;
import com.example.drinkshop.repository.PosRepository;
import com.example.drinkshop.service.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.web.multipart.MultipartFile;
import java.security.Principal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api")
public class PosController {
    private final PosRepository repo;
    private final OrderService orders;
    private final RuleService rules;
    private final PhotoService photos;
    private final Clock clock;
    private final ApplicationAvailability availability;
    public PosController(PosRepository repo,OrderService orders,RuleService rules,PhotoService photos,Clock clock,ApplicationAvailability availability) {
        this.repo=repo; this.orders=orders; this.rules=rules; this.photos=photos; this.clock=clock; this.availability=availability;
    }
    @GetMapping("/health")
    ResponseEntity<?> health() {
        if(availability.getReadinessState()!=ReadinessState.ACCEPTING_TRAFFIC)
            return ResponseEntity.status(503).body(Map.of("status","STARTING"));
        repo.one("SELECT 1 AS ok");
        return ResponseEntity.ok(Map.of("status","UP","application","drinkshop-pos-backend"));
    }
    @GetMapping("/auth/me")
    Map<String,Object> me(Principal principal) {
        var staff=orders.actor(principal.getName());
        return Map.of("staffId",staff.id(),"username",staff.username(),"name",staff.name(),"level",staff.level());
    }
    @GetMapping("/menu")
    Map<String,Object> menu() {
        // Menu must never expose employee-only recipe contents or account hashes.
        return Map.of("products",repo.rows("SELECT product_id,name,category,base_price,large_extra,hot_allowed FROM product WHERE on_sale=TRUE ORDER BY product_id"),
            "toppings",repo.rows("SELECT topping_id,name,price FROM topping WHERE on_sale=TRUE ORDER BY topping_id"));
    }
    @GetMapping("/rules")
    List<Map<String,Object>> rules() {
        return repo.rules().stream().map(row -> {
            var result=new LinkedHashMap<>(row);
            result.put("condition_json",rules.parse(row.get("condition_json")));
            result.put("action_json",rules.parse(row.get("action_json")));
            return (Map<String,Object>)result;
        }).toList();
    }
    @GetMapping("/orders")
    List<Map<String,Object>> list(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date) {
        return repo.rows("SELECT order_id,order_no,business_date,source,payment,payment_status,paid_amount,refunded_amount,total,status,created_at FROM pos_order WHERE business_date=? ORDER BY order_id DESC",
            date==null?LocalDate.now(clock):date);
    }
    @GetMapping("/orders/{id}")
    Map<String,Object> detail(@PathVariable long id) { return repo.detail(id); }
    @PostMapping("/orders")
    Map<String,Object> create(@Valid @RequestBody Create request,Principal principal) { return orders.create(request,principal.getName()); }
    @PostMapping("/orders/{id}/collect")
    Map<String,Object> collect(@PathVariable long id,@Valid @RequestBody Collect request,Principal principal) {
        return orders.collect(id,request,principal.getName());
    }
    @PostMapping("/orders/{id}/items/{itemId}/status")
    Map<String,Object> status(@PathVariable long id,@PathVariable long itemId,@Valid @RequestBody ChangeStatus request,Principal principal) {
        return orders.changeStatus(id,itemId,request.status(),principal.getName());
    }
    @PostMapping("/orders/{id}/status")
    Map<String,Object> statusAll(@PathVariable long id,@Valid @RequestBody ChangeStatus request,Principal principal) {
        return orders.changeAllStatuses(id,request.status(),principal.getName());
    }
    @PostMapping("/orders/{id}/cancel")
    Map<String,Object> cancelOrder(@PathVariable long id,@Valid @RequestBody Cancel request,Principal principal) {
        return orders.cancel(id,null,request,principal.getName());
    }
    @PostMapping("/orders/{id}/items/{itemId}/cancel")
    Map<String,Object> cancelItem(@PathVariable long id,@PathVariable long itemId,@Valid @RequestBody Cancel request,Principal principal) {
        return orders.cancel(id,itemId,request,principal.getName());
    }
    @PostMapping(value="/orders/{id}/items/{itemId}/photo",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String,Object> upload(@PathVariable long id,@PathVariable long itemId,@RequestPart("file") MultipartFile file,Principal principal) {
        return photos.upload(id,itemId,file,principal.getName());
    }
    @GetMapping("/orders/{id}/items/{itemId}/photo")
    ResponseEntity<Resource> photo(@PathVariable long id,@PathVariable long itemId) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("Cache-Control","no-store")
            .header("X-Content-Type-Options","nosniff").body(photos.read(id,itemId));
    }
}
