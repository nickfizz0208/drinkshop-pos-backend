-- 035：新增菜單飲品——經典特調（分類「特調」）3 杯、芝芝系列（分類「芝芝」）4 杯
-- 僅由資料庫負責人確認後執行；需要先執行 030。可以重複執行：同名的飲品已經存在就略過，不會改到它。
--
-- 價格照門市菜單：M → 中杯 price_m、L → 大杯 price_l、瓶 → price_bottle；菜單是「-」的容量填 NULL（不賣）。
-- 菜單只標「冷」的 hot_allowed = FALSE（養樂多綠茶、開心果椰水）；「冷/熱」「冰沙/熱」= TRUE。
-- 先只建飲品，不建配方材料（product_recipe），製作步驟 recipe_steps 先留空白。
-- 色系 color_tone 先不設定（NULL，前端依名稱自動判斷：名稱有「綠茶」→綠，其他→紅）。
--
-- 菜單上還有兩件事這支沒處理：
--   1. 梅子冰茶「甜度冰量固定」：要另外加規則（menu_rule），目前點餐仍可選甜度、冰量。
--   2. 芝芝系列的「芝芝」是飲品本身的奶蓋，不是加料；加料區的芝芝（+$20）等要另外加到 topping。

INSERT INTO product (name, category, price_m, price_l, price_bottle, hot_allowed, recipe_steps)
SELECT v.name, v.category, v.price_m, v.price_l, v.price_bottle, v.hot_allowed, ''
FROM (
            SELECT '梅子冰茶'     AS name, '特調' AS category, NULL AS price_m, 35   AS price_l, 50   AS price_bottle, TRUE  AS hot_allowed, 1 AS ord
  UNION ALL SELECT '梅子綠茶',     '特調', NULL, 45,   NULL, TRUE,  2
  UNION ALL SELECT '養樂多綠茶',   '特調', NULL, 50,   80,   FALSE, 3
  UNION ALL SELECT '芝芝金萱',     '芝芝', NULL, 50,   NULL, TRUE,  4
  UNION ALL SELECT '芝芝金萱雙Q',  '芝芝', NULL, 60,   NULL, TRUE,  5
  UNION ALL SELECT '開心果椰水',   '芝芝', 80,   NULL, NULL, FALSE, 6
  UNION ALL SELECT '開心果可可',   '芝芝', 80,   NULL, NULL, TRUE,  7
) v
WHERE NOT EXISTS (SELECT 1 FROM product p WHERE p.name = v.name)
ORDER BY v.ord;

-- 核對：
-- SELECT product_id, name, category, price_m AS 中杯, price_l AS 大杯, price_bottle AS 瓶, hot_allowed AS 可熱飲
--   FROM product WHERE category IN ('特調', '芝芝') ORDER BY category, product_id;
