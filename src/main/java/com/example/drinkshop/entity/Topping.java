package com.example.drinkshop.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * 加料配料實體（對應 topping 資料表）
 */
@Entity
@Table(name = "topping")
public class Topping {

    // 加料配料編號（主鍵，自動遞增）
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "topping_id")
    private Long toppingId;

    // 加料名稱（例如 珍珠、椰果、波霸，唯一值）
    @Column(name = "name", length = 30, nullable = false, unique = true)
    private String name;

    // 顧客加料加購售價
    @Column(name = "price", precision = 10, scale = 2, nullable = false)
    private BigDecimal price;

    // 加料對應的原料（例如珍珠）；和 prepId 剛好填一個（021）
    @Column(name = "inventory_id")
    private Long inventoryId;

    // 加料對應的半成品（例如茶凍）；和 inventoryId 剛好填一個。加料不再記扣料量（021）
    @Column(name = "prep_id")
    private Long prepId;

    // 是否在售開放加料（true: 開放中, false: 停售/缺料隱藏）
    @Column(name = "on_sale", nullable = false)
    private Boolean onSale = true;

    public Topping() {
    }

    public Topping(Long toppingId, String name, BigDecimal price, Long inventoryId, Long prepId, Boolean onSale) {
        this.toppingId = toppingId;
        this.name = name;
        this.price = price;
        this.inventoryId = inventoryId;
        this.prepId = prepId;
        this.onSale = onSale;
    }

    public Long getToppingId() {
        return toppingId;
    }

    public void setToppingId(Long toppingId) {
        this.toppingId = toppingId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Long getInventoryId() {
        return inventoryId;
    }

    public void setInventoryId(Long inventoryId) {
        this.inventoryId = inventoryId;
    }

    public Long getPrepId() {
        return prepId;
    }

    public void setPrepId(Long prepId) {
        this.prepId = prepId;
    }

    public Boolean getOnSale() {
        return onSale;
    }

    public void setOnSale(Boolean onSale) {
        this.onSale = onSale;
    }
}
