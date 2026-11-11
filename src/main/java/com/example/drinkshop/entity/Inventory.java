package com.example.drinkshop.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 原料庫存實體（對應 inventory 資料表）
 */
@Entity
@Table(name = "inventory")
public class Inventory {

    // 原料庫存編號（主鍵，自動遞增）
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "inventory_id")
    private Long inventoryId;

    // 原料名稱（唯一）
    @Column(name = "name", length = 50, nullable = false, unique = true)
    private String name;

    // 基本計量單位（例如 g、ml、個）
    @Column(name = "base_unit", length = 10, nullable = false)
    private String baseUnit;

    // 包裝單位（例如 包、罐、箱）
    @Column(name = "pack_unit", length = 10)
    private String packUnit;

    // 每包裝單位所含基本單位數量
    @Column(name = "per_pack", precision = 12, scale = 2)
    private BigDecimal perPack;

    // 系統帳面現有庫存量
    @Column(name = "book_qty", precision = 12, scale = 2, nullable = false)
    private BigDecimal bookQty = BigDecimal.ZERO;

    // 安全庫存警示水位
    @Column(name = "safety_stock", precision = 12, scale = 2, nullable = false)
    private BigDecimal safetyStock = BigDecimal.ZERO;

    // 盤點回報實際數量
    @Column(name = "reported_qty", precision = 12, scale = 2)
    private BigDecimal reportedQty;

    // 最後盤點回報人員員工編號
    @Column(name = "reported_by")
    private Long reportedBy;

    // 最後盤點回報時間
    @Column(name = "reported_at")
    private LocalDateTime reportedAt;

    // 原料進貨單位成本
    @Column(name = "unit_cost", precision = 10, scale = 2)
    private BigDecimal unitCost;

    // 畫面用「大單位＋小單位」顯示（例如 5 籃 3 顆）；資料庫照樣存基本單位數量（019）。只讀，不由這個實體寫入
    @Column(name = "display_by_pack", insertable = false, updatable = false)
    private Boolean displayByPack = false;

    public boolean isDisplayByPack() { return Boolean.TRUE.equals(displayByPack); }

    public Inventory() {
    }

    public Inventory(Long inventoryId, String name, String baseUnit, String packUnit, BigDecimal perPack,
                     BigDecimal bookQty, BigDecimal safetyStock, BigDecimal reportedQty, Long reportedBy,
                     LocalDateTime reportedAt, BigDecimal unitCost) {
        this.inventoryId = inventoryId;
        this.name = name;
        this.baseUnit = baseUnit;
        this.packUnit = packUnit;
        this.perPack = perPack;
        this.bookQty = bookQty;
        this.safetyStock = safetyStock;
        this.reportedQty = reportedQty;
        this.reportedBy = reportedBy;
        this.reportedAt = reportedAt;
        this.unitCost = unitCost;
    }

    public Long getInventoryId() {
        return inventoryId;
    }

    public void setInventoryId(Long inventoryId) {
        this.inventoryId = inventoryId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBaseUnit() {
        return baseUnit;
    }

    public void setBaseUnit(String baseUnit) {
        this.baseUnit = baseUnit;
    }

    public String getPackUnit() {
        return packUnit;
    }

    public void setPackUnit(String packUnit) {
        this.packUnit = packUnit;
    }

    public BigDecimal getPerPack() {
        return perPack;
    }

    public void setPerPack(BigDecimal perPack) {
        this.perPack = perPack;
    }

    public BigDecimal getBookQty() {
        return bookQty;
    }

    public void setBookQty(BigDecimal bookQty) {
        this.bookQty = bookQty;
    }

    public BigDecimal getSafetyStock() {
        return safetyStock;
    }

    public void setSafetyStock(BigDecimal safetyStock) {
        this.safetyStock = safetyStock;
    }

    public BigDecimal getReportedQty() {
        return reportedQty;
    }

    public void setReportedQty(BigDecimal reportedQty) {
        this.reportedQty = reportedQty;
    }

    public Long getReportedBy() {
        return reportedBy;
    }

    public void setReportedBy(Long reportedBy) {
        this.reportedBy = reportedBy;
    }

    public LocalDateTime getReportedAt() {
        return reportedAt;
    }

    public void setReportedAt(LocalDateTime reportedAt) {
        this.reportedAt = reportedAt;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public void setUnitCost(BigDecimal unitCost) {
        this.unitCost = unitCost;
    }
}
