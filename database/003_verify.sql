USE drinkshop_pos;
SELECT TABLE_NAME, ENGINE FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'drinkshop_pos' ORDER BY TABLE_NAME;

-- All four rows must show amounts_match = 1. Topping prices are NOT added again.
SELECT o.order_no, o.total,
  COALESCE(SUM(CASE WHEN i.status NOT IN ('CANCELLED','WASTED') THEN i.qty*i.unit_price ELSE 0 END),0) + o.delivery_fee AS calculated_total,
  o.total = COALESCE(SUM(CASE WHEN i.status NOT IN ('CANCELLED','WASTED') THEN i.qty*i.unit_price ELSE 0 END),0) + o.delivery_fee AS amounts_match
FROM pos_order o LEFT JOIN order_item i ON i.order_id=o.order_id
GROUP BY o.order_id ORDER BY o.order_id;

SELECT inventory_id,name,book_qty,safety_stock,book_qty < safety_stock AS low_stock
FROM inventory ORDER BY inventory_id;

-- Expected: four retained cups, one cancelled, one wasted; retained sales = 185.
SELECT status,SUM(qty) AS cups FROM order_item GROUP BY status ORDER BY status;
SELECT payment,SUM(total) AS retained_sales FROM pos_order GROUP BY payment;

-- Nonzero result means an invalid recipe reference. Expected zero rows.
SELECT p.product_id,j.inventory_id FROM product p
JOIN JSON_TABLE(p.recipe_json,'$.*[*]' COLUMNS(inventory_id BIGINT PATH '$.inventoryId')) AS j
LEFT JOIN inventory i ON i.inventory_id=j.inventory_id
WHERE i.inventory_id IS NULL;
