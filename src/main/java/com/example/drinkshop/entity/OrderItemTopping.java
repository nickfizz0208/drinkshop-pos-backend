package com.example.drinkshop.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * 訂單品項加料關聯實體（對應 order_item_topping 資料表）
 */
@Entity
@Table(name = "order_item_topping", uniqueConstraints = {
    @UniqueConstraint(name = "uq_order_item_topping", columnNames = {"item_id", "topping_id"})
})
public class OrderItemTopping {

    // 訂單加料明細流水號（主鍵，自動遞增）
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_topping_id")
    private Long orderToppingId;

    // 所屬訂單品項明細編號
    @Column(name = "item_id", nullable = false)
    private Long itemId;

    // 加料項目編號
    @Column(name = "topping_id", nullable = false)
    private Long toppingId;

    // 下單當下之加料名稱（保留歷史紀錄）
    @Column(name = "topping_name", length = 30, nullable = false)
    private String toppingName;

    // 份數：同一種加料可以加兩份（039）
    @Column(name = "qty", nullable = false)
    private Integer qty = 1;

    // 下單當下之加料單價（一份的價格，保留歷史結帳價格）
    @Column(name = "price_at_order", precision = 10, scale = 2, nullable = false)
    private BigDecimal priceAtOrder;

    public OrderItemTopping() {
    }

    public Integer getQty() {
        return qty;
    }

    public void setQty(Integer qty) {
        this.qty = qty;
    }

    public OrderItemTopping(Long orderToppingId, Long itemId, Long toppingId, String toppingName, BigDecimal priceAtOrder) {
        this.orderToppingId = orderToppingId;
        this.itemId = itemId;
        this.toppingId = toppingId;
        this.toppingName = toppingName;
        this.priceAtOrder = priceAtOrder;
    }

    public Long getOrderToppingId() {
        return orderToppingId;
    }

    public void setOrderToppingId(Long orderToppingId) {
        this.orderToppingId = orderToppingId;
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public Long getToppingId() {
        return toppingId;
    }

    public void setToppingId(Long toppingId) {
        this.toppingId = toppingId;
    }

    public String getToppingName() {
        return toppingName;
    }

    public void setToppingName(String toppingName) {
        this.toppingName = toppingName;
    }

    public BigDecimal getPriceAtOrder() {
        return priceAtOrder;
    }

    public void setPriceAtOrder(BigDecimal priceAtOrder) {
        this.priceAtOrder = priceAtOrder;
    }
}
