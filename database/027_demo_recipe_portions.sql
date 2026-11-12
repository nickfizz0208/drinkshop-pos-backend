-- 027：依 002_demo_data.sql 的杯型配方補上五款示範飲品的材料份數。
-- 先完成 026。
--
-- 2026-11-13 已套用到 Aiven 共用資料庫。
-- 五款示範飲品的 8 筆空白材料份數已補為 1 份，保留既有份數。
-- 門市 POS、店員與店長三種帳號皆已重測，配方顯示正常。
START TRANSACTION;

-- 與網站的配方編輯使用相同的商品列鎖。
SELECT product_id
FROM product
WHERE product_id IN (1, 2, 3, 4, 5)
ORDER BY product_id
FOR UPDATE;

UPDATE product_recipe
SET portions = 1.00
WHERE portions IS NULL
  AND prep_id IS NULL
  AND (product_id, inventory_id) IN (
    (1, 3),                         -- 經典紅茶
    (2, 4),                         -- 茉香綠茶
    (3, 3), (3, 5),                 -- 經典奶茶
    (4, 3), (4, 5), (4, 6),         -- 珍珠奶茶
    (5, 8)                          -- 芒果冰沙
  );

COMMIT;
