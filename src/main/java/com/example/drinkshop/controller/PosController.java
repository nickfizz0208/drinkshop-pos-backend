package com.example.drinkshop.controller;

import com.example.drinkshop.dto.OrderRequests.*;
import com.example.drinkshop.repository.PosRepository;
import com.example.drinkshop.service.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
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

/** API 入口：接收前端 JSON，交給 Service 處理，再把結果轉成 JSON 回傳。 */
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
    // 健康檢查同時確認服務已就緒、資料庫能查詢；不會新增或修改資料。
    @GetMapping("/health")
    @SecurityRequirements
    ResponseEntity<?> health() {
        if(availability.getReadinessState()!=ReadinessState.ACCEPTING_TRAFFIC)
            return ResponseEntity.status(503).body(Map.of("status","STARTING"));
        repo.one("SELECT 1 AS ok");
        return ResponseEntity.ok(Map.of("status","UP","application","drinkshop-pos-backend"));
    }
    // token 驗證成功後，Principal 就是目前登入帳號；這裡回傳可顯示的員工資料。
    @GetMapping("/auth/me")
    Map<String,Object> me(Principal principal) {
        var staff=orders.actor(principal.getName());
        return Map.of("staffId",staff.id(),"username",staff.username(),"name",staff.name(),"level",staff.level());
    }
    @GetMapping("/menu")
    Map<String,Object> menu() {
        // 點餐菜單只列必要欄位，不能把扣料配方或帳號密碼雜湊傳給前端。
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
    // 依營業日查全部訂單；待處理／待取餐／當日紀錄的顯示篩選由前端負責。
    @GetMapping("/orders")
    List<Map<String,Object>> list(@RequestParam(name = "date", required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date) {
        return repo.rows("SELECT order_id,order_no,business_date,source,payment,payment_status,paid_amount,refunded_amount,total,status,created_at FROM pos_order WHERE business_date=? ORDER BY order_id DESC",
            date==null?LocalDate.now(clock):date);
    }
    @GetMapping("/orders/{id}")
    Map<String,Object> detail(@PathVariable("id") long id) { return repo.detail(id); }
    // @Valid 先檢查 DTO 的必填與數量格式；價格、庫存與規則仍由 Service 再驗證。
    @PostMapping("/orders")
    Map<String,Object> create(@Valid @RequestBody Create request,Principal principal) { return orders.create(request,principal.getName()); }
    @PostMapping("/orders/{id}/collect")
    Map<String,Object> collect(@PathVariable("id") long id,@Valid @RequestBody Collect request,Principal principal) {
        return orders.collect(id,request,principal.getName());
    }
    @PostMapping("/orders/{id}/items/{itemId}/status")
    Map<String,Object> status(@PathVariable("id") long id,@PathVariable("itemId") long itemId,@Valid @RequestBody ChangeStatus request,Principal principal) {
        return orders.changeStatus(id,itemId,request.status(),principal.getName());
    }
    @PostMapping("/orders/{id}/status")
    Map<String,Object> statusAll(@PathVariable("id") long id,@Valid @RequestBody ChangeStatus request,Principal principal) {
        return orders.changeAllStatuses(id,request.status(),principal.getName());
    }
    // 整單和單筆明細取消共用同一套業務流程，差別只在是否傳入 itemId。
    @PostMapping("/orders/{id}/cancel")
    Map<String,Object> cancelOrder(@PathVariable("id") long id,@Valid @RequestBody Cancel request,Principal principal) {
        return orders.cancel(id,null,request,principal.getName());
    }
    @PostMapping("/orders/{id}/items/{itemId}/cancel")
    Map<String,Object> cancelItem(@PathVariable("id") long id,@PathVariable("itemId") long itemId,@Valid @RequestBody Cancel request,Principal principal) {
        return orders.cancel(id,itemId,request,principal.getName());
    }
    // 圖片用 multipart 的 file 欄位上傳；讀圖也需登入，不能公開本機儲存路徑。
    @PostMapping(value="/orders/{id}/items/{itemId}/photo",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String,Object> upload(@PathVariable("id") long id,@PathVariable("itemId") long itemId,@RequestPart("file") MultipartFile file,Principal principal) {
        return photos.upload(id,itemId,file,principal.getName());
    }
    @GetMapping("/orders/{id}/items/{itemId}/photo")
    ResponseEntity<Resource> photo(@PathVariable("id") long id,@PathVariable("itemId") long itemId) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("Cache-Control","no-store")
            .header("X-Content-Type-Options","nosniff").body(photos.read(id,itemId));
    }
}
