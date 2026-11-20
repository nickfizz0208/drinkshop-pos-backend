-- 044：半成品的每一項材料可以決定「停售連不連動」
-- 僅由資料庫負責人確認後執行；需要先執行 043。可以重複執行（欄位已經存在就略過）。
-- 這版後端需要 044（停售會讀 link），請先執行再部署。
--
-- prep_item_ingredient.link：
--   TRUE（預設）：這項材料停售時，這個半成品跟著不能賣（用到它的飲品、加料，以及 link＝TRUE 用到它的上層半成品也一樣）。
--   FALSE：這項材料停售不影響這個半成品。例如綠茶凍用綠茶（茶湯）做，但綠茶凍是事先做好的，綠茶賣完綠茶凍照賣。
-- 只影響停售；消耗報表不管 link，照樣把用量算到原料。
-- 連動最多往上追 3 層（和消耗報表展開的上限相同）。

SET @has_link := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prep_item_ingredient' AND COLUMN_NAME = 'link');
SET @sql := IF(@has_link = 0,
  'ALTER TABLE prep_item_ingredient ADD COLUMN link BOOLEAN NOT NULL DEFAULT TRUE AFTER qty',
  'SELECT ''link 已存在，略過''');
PREPARE add_link FROM @sql;
EXECUTE add_link;
DEALLOCATE PREPARE add_link;

-- 例：綠茶凍裡的綠茶不連動（名稱請照實際情況改）
-- UPDATE prep_item_ingredient g
--   JOIN prep_item j ON j.prep_id = g.prep_id
--   JOIN prep_item t ON t.prep_id = g.sub_prep_id
--    SET g.link = FALSE
--  WHERE j.name = '綠茶凍' AND t.name = '綠茶' AND g.line_id > 0;

-- 核對：
-- SELECT x.name AS 半成品, COALESCE(i.name, s.name) AS 材料, g.qty, g.link
--   FROM prep_item_ingredient g JOIN prep_item x ON x.prep_id = g.prep_id
--   LEFT JOIN inventory i ON i.inventory_id = g.inventory_id LEFT JOIN prep_item s ON s.prep_id = g.sub_prep_id
--  ORDER BY g.prep_id, g.line_id;
