-- 023：原料、半成品加上分類（停售管理頁依分類選擇）
-- 僅由資料庫負責人確認後執行；需要先執行 022。這一版後端需要 023，請先執行 023 再部署。
--
-- 分類是文字，沒有固定清單；停售管理頁由店長、總部修改（PUT /api/sale-status/categories）。
-- 下面依名稱先分好一次，分不出來的留 NULL，頁面顯示「未分類」。執行後請用最後的核對查詢檢查。
-- 原料常用分類：包材、茶葉、底料、水果；半成品：茶湯、底料、鮮果。

ALTER TABLE inventory ADD COLUMN category VARCHAR(20) NULL;
ALTER TABLE prep_item ADD COLUMN category VARCHAR(20) NULL;

-- 原料：依序比對，先符合的為準（「茶凍粉」算底料不算茶葉；「芒果冰沙料」算底料不算水果）
-- WHERE inventory_id > 0：MySQL Workbench 的 safe update 模式（錯誤 1175）
UPDATE inventory SET category = CASE
    WHEN name LIKE '%杯%' OR name LIKE '%蓋%' OR name LIKE '%吸管%' OR name LIKE '%封膜%' OR name LIKE '%提袋%'
      OR name LIKE '%袋%' OR name LIKE '%紙%' THEN '包材'
    WHEN name LIKE '%粉%' OR name LIKE '%凍%' OR name LIKE '%珍珠%' OR name LIKE '%粉圓%' OR name LIKE '%奶%'
      OR name LIKE '%糖%' OR name LIKE '%漿%' OR name LIKE '%料%' OR name LIKE '%椰果%' OR name LIKE '%布丁%' THEN '底料'
    WHEN name LIKE '%茶%' THEN '茶葉'
    WHEN name LIKE '%果%' OR name LIKE '%芒果%' OR name LIKE '%檸檬%' OR name LIKE '%柳橙%' OR name LIKE '%葡萄%'
      OR name LIKE '%莓%' OR name LIKE '%桃%' OR name LIKE '%鳳梨%' OR name LIKE '%西瓜%' OR name LIKE '%金桔%' THEN '水果'
    ELSE NULL END
WHERE category IS NULL AND inventory_id > 0;

-- 半成品
UPDATE prep_item SET category = CASE
    WHEN name LIKE '%茶湯%' OR name LIKE '%茶' THEN '茶湯'
    WHEN name LIKE '%果%' OR name LIKE '%檸檬%' OR name LIKE '%柳橙%' OR name LIKE '%莓%' OR name LIKE '%芒果%' THEN '鮮果'
    WHEN name LIKE '%凍%' OR name LIKE '%珍珠%' OR name LIKE '%粉圓%' OR name LIKE '%布丁%' OR name LIKE '%奶%'
      OR name LIKE '%糖%' OR name LIKE '%漿%' OR name LIKE '%芋%' THEN '底料'
    ELSE NULL END
WHERE category IS NULL AND prep_id > 0;

-- 核對：每一項的分類（NULL＝頁面顯示「未分類」）
-- SELECT '原料' AS 種類, name, category FROM inventory
-- UNION ALL SELECT '半成品', name, category FROM prep_item ORDER BY 1, 3, 2;
-- 個別修正：UPDATE inventory SET category = '水果' WHERE name = '芒果';
