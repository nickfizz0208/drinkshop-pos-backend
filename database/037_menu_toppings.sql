-- 037：新增菜單上的加料（添加口感）共 10 種
-- 僅由資料庫負責人確認後執行；需要先執行 029（原料分類）。可以重複執行：同名的加料、原料已經存在就略過。
--
--   +$10：波霸、椰果、雙Q果、波波、紅萱粉粿、綠茶凍、開心果脆脆
--   +$20：金絨芝芝、芝芝
--   +$35：開心果芝芝
--
-- 每種加料一定要對應一個原料或半成品（topping 的 chk_topping_one 限制），所以：
--   1. 已經有同名的半成品（prep_item）就用半成品；
--   2. 否則用同名的原料（inventory）；兩個都沒有就先建一個原料：單位「份」、帳面庫存 0、分類「底料」。
--      帳面庫存 0 不影響點餐（送單不檢查庫存），之後盤點時再填；要改成自己做的半成品，到配方頁調整。
--
-- 這支先只建加料選項。還沒處理（之後再說）：
--   - 紅萱粉粿、綠茶凍「限冷飲」的點餐規則
--   - 和現有加料（珍珠、茶凍）重複或要不要停用

-- 1. 沒有對應原料、半成品的，先建原料
INSERT INTO inventory (name, base_unit, book_qty, safety_stock, category)
SELECT v.name, '份', 0, 0, '底料'
FROM (
            SELECT '波霸' AS name, 1 AS ord
  UNION ALL SELECT '椰果',       2
  UNION ALL SELECT '雙Q果',      3
  UNION ALL SELECT '波波',       4
  UNION ALL SELECT '紅萱粉粿',   5
  UNION ALL SELECT '綠茶凍',     6
  UNION ALL SELECT '開心果脆脆', 7
  UNION ALL SELECT '金絨芝芝',   8
  UNION ALL SELECT '芝芝',       9
  UNION ALL SELECT '開心果芝芝', 10
) v
WHERE NOT EXISTS (SELECT 1 FROM inventory i WHERE i.name = v.name)
  AND NOT EXISTS (SELECT 1 FROM prep_item p WHERE p.name = v.name)
ORDER BY v.ord;

-- 2. 加料：有同名半成品就接半成品，否則接同名原料（兩欄只會填一個）
INSERT INTO topping (name, price, inventory_id, prep_id)
SELECT v.name, v.price,
       CASE WHEN p.prep_id IS NULL THEN i.inventory_id END,
       p.prep_id
FROM (
            SELECT '波霸' AS name, 10 AS price, 1 AS ord
  UNION ALL SELECT '椰果',       10, 2
  UNION ALL SELECT '雙Q果',      10, 3
  UNION ALL SELECT '波波',       10, 4
  UNION ALL SELECT '紅萱粉粿',   10, 5
  UNION ALL SELECT '綠茶凍',     10, 6
  UNION ALL SELECT '開心果脆脆', 10, 7
  UNION ALL SELECT '金絨芝芝',   20, 8
  UNION ALL SELECT '芝芝',       20, 9
  UNION ALL SELECT '開心果芝芝', 35, 10
) v
LEFT JOIN prep_item p ON p.name = v.name
LEFT JOIN inventory i ON i.name = v.name
WHERE NOT EXISTS (SELECT 1 FROM topping t WHERE t.name = v.name)
ORDER BY v.ord;

-- 核對：
-- SELECT t.topping_id, t.name, t.price, i.name AS 原料, p.name AS 半成品
--   FROM topping t LEFT JOIN inventory i ON i.inventory_id = t.inventory_id LEFT JOIN prep_item p ON p.prep_id = t.prep_id
--  ORDER BY t.price, t.topping_id;
