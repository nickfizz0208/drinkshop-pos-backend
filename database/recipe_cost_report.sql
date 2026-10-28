-- Read-only current recipe estimates. These are not historical order costs or net profit.
USE drinkshop_pos;
SET NAMES utf8mb4;

SELECT inventory_id,name,base_unit,unit_cost
FROM inventory ORDER BY inventory_id;

WITH sizes AS (SELECT 'M' AS size UNION ALL SELECT 'L'), components AS (
  SELECT p.product_id,p.name,s.size,
    p.base_price+IF(s.size='L',p.large_extra,0) AS selling_price,
    j.inventory_id,j.qty,i.name AS ingredient_name,i.base_unit,i.unit_cost
  FROM product p CROSS JOIN sizes s
  LEFT JOIN JSON_TABLE(JSON_EXTRACT(p.recipe_json,CONCAT('$.',s.size)),
    '$[*]' COLUMNS (
      inventory_id BIGINT PATH '$.inventoryId' ERROR ON ERROR,
      qty DECIMAL(12,2) PATH '$.qty' ERROR ON ERROR
    )) j ON TRUE
  LEFT JOIN inventory i ON i.inventory_id=j.inventory_id
)
SELECT product_id,name,size,selling_price,
  CASE WHEN COUNT(inventory_id)=0 OR
    SUM(CASE WHEN inventory_id IS NULL OR ingredient_name IS NULL OR unit_cost IS NULL OR qty IS NULL OR qty<=0 THEN 1 ELSE 0 END)>0
    THEN NULL ELSE ROUND(SUM(qty*unit_cost),2) END AS estimated_material_cost,
  GROUP_CONCAT(CONCAT(ingredient_name,' × ',qty,' ',base_unit,' = ',ROUND(qty*unit_cost,2),' 元')
    ORDER BY inventory_id SEPARATOR ' + ') AS breakdown
FROM components GROUP BY product_id,name,size,selling_price ORDER BY product_id,size DESC;

SELECT t.topping_id,t.name,t.price AS selling_price,t.deduct_qty,i.base_unit,
  ROUND(t.deduct_qty*i.unit_cost,2) AS estimated_extra_cost
FROM topping t LEFT JOIN inventory i ON i.inventory_id=t.inventory_id ORDER BY t.topping_id;
