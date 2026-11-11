package com.example.drinkshop.service;

import com.example.drinkshop.dto.*;
import com.example.drinkshop.entity.Inventory;
import com.example.drinkshop.entity.OrderItem;
import com.example.drinkshop.exception.ApiException;
import com.example.drinkshop.repository.OrderItemRepository;
import com.example.drinkshop.repository.PosRepository;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class WastageService {
    private final InventoryService inventory;
    private final OrderItemRepository items;
    private final OrderService orders;
    private final PosRepository repo;
    private final EntityManager em;
    private final Clock clock;
    private final boolean rawEnabled;

    public WastageService(InventoryService inventory, OrderItemRepository items, OrderService orders,
            PosRepository repo, EntityManager em, Clock clock,
            @Value("${pos.raw-wastage.enabled:false}") boolean rawEnabled) {
        this.inventory=inventory; this.items=items; this.orders=orders; this.repo=repo;
        this.em=em; this.clock=clock; this.rawEnabled=rawEnabled;
    }

    public Inventory recordRawMaterialWastage(Long id, BigDecimal quantity, String reason, Long staffId) {
        // 共用庫未升級 009 前不登記，避免畫面顯示成功卻沒有損耗紀錄。
        if (!rawEnabled) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
            "原料損耗紀錄尚未啟用，請先由資料庫負責人確認 009 升級");
        if (id==null || id<=0 || quantity==null || quantity.signum()<=0 || quantity.scale()>2)
            throw ApiException.bad("請填寫原料編號與大於零、最多兩位小數的數量");
        if (reason==null || reason.isBlank() || reason.trim().length()>255) throw ApiException.bad("請填寫 255 字內的損耗原因");
        LocalDate date=LocalDate.now(clock);
        var shift=repo.rows("SELECT status FROM daily_shift WHERE business_date=? FOR UPDATE",date);
        if (shift.isEmpty() || !"OPEN".equals(shift.getFirst().get("status")))
            throw ApiException.conflict("當日未開班或已日結，不能登記原料損耗");
        // 原料報廢不扣帳面庫存（帳面以盤點為準），只記錄數量、原因與當時成本
        Inventory updated=inventory.getInventoryById(id);
        BigDecimal unitCost=updated.getUnitCost();
        BigDecimal cost=unitCost==null?null:Money.checked(unitCost.multiply(quantity).setScale(2,RoundingMode.HALF_UP));
        repo.insert("wastage_id", """
            INSERT INTO raw_material_wastage(business_date,inventory_id,quantity,unit_cost,material_cost,reason,reported_by,reported_at)
            VALUES (?,?,?,?,?,?,?,?)
            """,date,id,quantity,unitCost,cost,reason.trim(),staffId,LocalDateTime.now(clock));
        return updated; // 報廢不是盤點，不改 book_qty，也不改 reported_qty/reported_by 等盤點欄位。
    }

    public OrderItem recordOrderItemWastage(Long itemId, String reason, BigDecimal refundAmount, String username) {
        // 所有狀態、退款、總單金額與庫存規則統一交給原有訂單交易，禁止直接改明細狀態。
        orders.recordWastage(itemId, new OrderRequests.Cancel(reason,null,null,
            refundAmount==null?Money.ZERO:refundAmount), username);
        OrderItem item=items.findById(itemId).orElseThrow(() -> ApiException.missing("找不到訂單明細"));
        em.refresh(item); // JDBC 更新後重新取得 JPA 實體，避免回傳舊狀態。
        return item;
    }

    @Transactional(readOnly = true)
    public DailyWastageSummaryResponse getDailyWastageSummary(LocalDate date) {
        var wasted=new ArrayList<OrderItemDto>();
        BigDecimal total=Money.ZERO;
        boolean complete=true;
        int cups=0;
        // 使用總單的營業日，不用明細插入時間，以免跨日或匯入的歷史資料被算錯天。
        for (var row:repo.rows("""
            SELECT i.* FROM order_item i JOIN pos_order o ON o.order_id=i.order_id
            WHERE o.business_date=? AND i.status='WASTED' ORDER BY i.item_id
            """,date)) {
            OrderItem item=items.findById(PosRepository.number(row,"item_id")).orElseThrow();
            cups+=item.getQty();
            // 飲品不計原料成本（配方只記材料、沒有份量，021），飲品報廢只算杯數；損耗金額只加總原料報廢。
            wasted.add(new OrderItemDto(item.getItemId(),item.getOrderId(),item.getProductId(),item.getProductName(),
                item.getSize(),item.getTemperature(),item.getSugar(),item.getIce(),item.getQty(),item.getUnitPrice(),
                item.getStatus(),item.getNote(),item.getCreatedAt()));
        }
        var raw=new ArrayList<RawMaterialWastageResponse>();
        if (rawEnabled) for (var row:repo.rows(
                "SELECT * FROM raw_material_wastage WHERE business_date=? ORDER BY wastage_id", date)) {
            BigDecimal cost=decimal(row,"material_cost");
            if (cost==null) complete=false; else total=total.add(cost);
            raw.add(new RawMaterialWastageResponse(PosRepository.number(row,"wastage_id"),date,
                PosRepository.number(row,"inventory_id"),decimal(row,"quantity"),decimal(row,"unit_cost"),cost,
                row.get("reason").toString(),PosRepository.number(row,"reported_by"),
                timestamp(row.get("reported_at"))));
        }
        // 損耗金額＝原料報廢成本合計；有原料沒建單位成本時為 NULL（costComplete=false），前端顯示「成本未建檔」。
        return new DailyWastageSummaryResponse(date,cups,complete?total:null,wasted,complete,rawEnabled,raw);
    }
    private static LocalDateTime timestamp(Object value) {
        // 不同 JDBC 驅動可能回傳 Timestamp 或 LocalDateTime。
        return value instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime)value;
    }
    private static BigDecimal decimal(Map<String,Object> row,String key) {
        return row.get(key)==null?null:new BigDecimal(row.get(key).toString());
    }
}
