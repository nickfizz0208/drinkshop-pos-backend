package com.example.drinkshop.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 訂單品項明細實體（對應 order_item 資料表）
 */
@Entity
@Table(name = "order_item")
public class OrderItem {

    // 明細品項流水號（主鍵，自動遞增）
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id")
    private Long itemId;

    // 所屬訂單編號
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    // 商品編號
    @Column(name = "product_id", nullable = false)
    private Long productId;

    // 商品名稱（例如 熟成紅茶）
    @Column(name = "product_name", length = 50, nullable = false)
    private String productName;

    // 飲品容量大小（M: 中杯, L: 大杯）
    @Column(name = "size", length = 10, nullable = false)
    private String size;

    // 溫度冰量（038，原本的溫度、冰量合成一個）：HOT 熱, WARM 溫, ROOM 常溫, ICE_FREE 完全去冰,
    // NO_ICE 去冰, LIGHT 微冰, LESS 少冰, NORMAL 正常冰, EXTRA 多冰
    @Column(name = "temp_ice", length = 10, nullable = false)
    private String tempIce;

    // 甜度（038）：NONE 無糖, S0_5 0.5 分, S1 1 分, S3 3 分, S5 5 分, S7 7 分, S10 10 分, EXTRA 多糖
    @Column(name = "sugar", length = 10, nullable = false)
    private String sugar;

    // 購買杯數/數量
    @Column(name = "qty", nullable = false)
    private Integer qty;

    // 該品項單價（含容量加價與加料加價後的單杯金額）
    @Column(name = "unit_price", precision = 10, scale = 2, nullable = false)
    private BigDecimal unitPrice;

    // 品項製作狀態（PENDING: 等候製作, MAKING: 製作中, DONE: 製作完成, PICKED_UP: 已取餐, CANCELLED: 已取消, WASTED: 已報廢）
    @Column(name = "status", length = 20, nullable = false)
    private String status = "PENDING";

    // 品項備註（客戶特殊客製要求）
    @Column(name = "note")
    private String note;

    // 作廢/報廢原因說明
    @Column(name = "reason")
    private String reason;

    // 報廢佐證照片存放路徑
    @Column(name = "photo_path", length = 500)
    private String photoPath;

    // 審核核准主管員工編號
    @Column(name = "approved_by")
    private Long approvedBy;

    // 建立時間（資料庫自動生成）
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    // 更新時間（資料庫自動生成）
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    // 扣除之原料庫存明細（JSON 格式字串，記錄配方 BOM 扣料數量）
    @Column(name = "deducted_inventory_json", columnDefinition = "json")
    private String deductedInventoryJson;

    // 該品項原料總成本
    @Column(name = "material_cost", precision = 10, scale = 2)
    private BigDecimal materialCost;

    // 已退款金額
    @Column(name = "refund_amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal refundAmount = BigDecimal.ZERO;

    public OrderItem() {
    }

    public OrderItem(Long itemId, Long orderId, Long productId, String productName, String size,
                     String tempIce, String sugar, Integer qty, BigDecimal unitPrice,
                     String status, String note, String reason, String photoPath, Long approvedBy,
                     LocalDateTime createdAt, LocalDateTime updatedAt, String deductedInventoryJson,
                     BigDecimal materialCost, BigDecimal refundAmount) {
        this.itemId = itemId;
        this.orderId = orderId;
        this.productId = productId;
        this.productName = productName;
        this.size = size;
        this.tempIce = tempIce;
        this.sugar = sugar;
        this.qty = qty;
        this.unitPrice = unitPrice;
        this.status = status;
        this.note = note;
        this.reason = reason;
        this.photoPath = photoPath;
        this.approvedBy = approvedBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deductedInventoryJson = deductedInventoryJson;
        this.materialCost = materialCost;
        this.refundAmount = refundAmount;
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public String getTempIce() {
        return tempIce;
    }

    public void setTempIce(String tempIce) {
        this.tempIce = tempIce;
    }

    public String getSugar() {
        return sugar;
    }

    public void setSugar(String sugar) {
        this.sugar = sugar;
    }

    public Integer getQty() {
        return qty;
    }

    public void setQty(Integer qty) {
        this.qty = qty;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getPhotoPath() {
        return photoPath;
    }

    public void setPhotoPath(String photoPath) {
        this.photoPath = photoPath;
    }

    public Long getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(Long approvedBy) {
        this.approvedBy = approvedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getDeductedInventoryJson() {
        return deductedInventoryJson;
    }

    public void setDeductedInventoryJson(String deductedInventoryJson) {
        this.deductedInventoryJson = deductedInventoryJson;
    }

    public BigDecimal getMaterialCost() {
        return materialCost;
    }

    public void setMaterialCost(BigDecimal materialCost) {
        this.materialCost = materialCost;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public void setRefundAmount(BigDecimal refundAmount) {
        this.refundAmount = refundAmount;
    }
}
