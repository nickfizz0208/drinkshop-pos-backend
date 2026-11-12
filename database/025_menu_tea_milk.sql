-- 025：新增菜單飲品——奶茶、鮮奶、茶類（含蕎麥綠寶石），共 16 杯
-- 需要先執行 024。可以重複執行：同名的飲品已經存在就略過，不會改到它。
--
-- 價格照門市菜單：L → 大杯 price_l、瓶 → price_bottle；這些飲品沒有中杯（price_m 為 NULL）。
-- 菜單標示只有「冷」的飲品 hot_allowed = FALSE（粉粿金萱、粉粿紅茶拿鐵）。
-- 先只建飲品，不建配方材料（product_recipe）：停售管理頁會標示「尚未建立配方，不會跟著原料停售」。
-- 製作步驟 recipe_steps 先留空白，之後再補。

INSERT INTO product (name, category, price_m, price_l, price_bottle, hot_allowed, recipe_steps)
SELECT v.name, v.category, NULL, v.price_l, v.price_bottle, v.hot_allowed, ''
FROM (
            SELECT '錫蘭奶茶'     AS name, '奶茶' AS category, 50 AS price_l, 75 AS price_bottle, TRUE  AS hot_allowed, 1  AS ord
  UNION ALL SELECT '鐵觀音奶茶',   '奶茶', 50, 75, TRUE,  2
  UNION ALL SELECT '波霸奶茶',     '奶茶', 50, 75, TRUE,  3
  UNION ALL SELECT '粉粿紅茶拿鐵', '鮮奶', 65, 95, FALSE, 4
  UNION ALL SELECT '紅茶拿鐵',     '鮮奶', 65, 95, TRUE,  5
  UNION ALL SELECT '鐵觀音拿鐵',   '鮮奶', 65, 95, TRUE,  6
  UNION ALL SELECT '波霸紅茶拿鐵', '鮮奶', 65, 95, TRUE,  7
  UNION ALL SELECT '高山金萱茶',   '茶類', 30, 45, TRUE,  8
  UNION ALL SELECT '粉粿金萱',     '茶類', 40, 55, FALSE, 9
  UNION ALL SELECT '金萱雙Q',      '茶類', 40, 55, TRUE,  10
  UNION ALL SELECT '紅萱烏龍',     '茶類', 35, 50, TRUE,  11
  UNION ALL SELECT '翡翠綠茶',     '茶類', 30, 45, TRUE,  12
  UNION ALL SELECT '錫蘭紅茶',     '茶類', 30, 45, TRUE,  13
  UNION ALL SELECT '文山青茶',     '茶類', 30, 45, TRUE,  14
  UNION ALL SELECT '古早味紅茶',   '茶類', 30, 45, TRUE,  15
  UNION ALL SELECT '蕎麥綠寶石',   '茶類', 40, 55, TRUE,  16
) v
WHERE NOT EXISTS (SELECT 1 FROM product p WHERE p.name = v.name)
ORDER BY v.ord;

-- 核對：
-- SELECT product_id, name, category, price_m AS 中杯, price_l AS 大杯, price_bottle AS 瓶, hot_allowed AS 可熱飲
--   FROM product WHERE category IN ('奶茶', '鮮奶', '茶類') ORDER BY category, product_id;
