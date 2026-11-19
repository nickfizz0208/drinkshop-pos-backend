-- 032：飲品色系（點餐頁飲品卡背景、杯子插圖的配色）
-- 僅由資料庫負責人確認後執行；需要先執行 031。新欄位允許空白，執行後畫面不會變；新版後端會讀這個欄位，部署前要先執行。
--
-- 依顏色區分，只存「哪一個色系」，不存色碼：實際顏色由前端統一管理（drink-card、drink-cup 的 scss），
-- 一個色系包含卡片背景兩色、杯身、飲料共 4 個彼此搭配的顏色，所以不讓每杯自己挑色碼。
--
--   RED    紅（紅茶色）    ORANGE 橙    YELLOW 黃    GREEN 綠
--   PURPLE 紫              WHITE  白    CREAM  奶
--   NULL   未設定：前端照名稱、分類自動判斷（冰沙→YELLOW、奶茶→CREAM、名稱有「綠茶」→GREEN、其他→RED）
--
-- 目前只有資料欄位，還沒有修改畫面；要設定請直接用 SQL（見最下面範例）。
-- 之後要新增色系：前端先加一組顏色，再 ALTER 這個 ENUM 加值（前端不認得的值會當作未設定）。

ALTER TABLE product
  ADD COLUMN color_tone ENUM('RED','ORANGE','YELLOW','GREEN','PURPLE','WHITE','CREAM') NULL AFTER hot_allowed;

-- 如果之前已經執行過舊版 032（TEA、GREEN、MILK、FRUIT），上面那段會因欄位已存在而失敗，改執行這段：
-- ALTER TABLE product MODIFY COLUMN color_tone
--   ENUM('TEA','MILK','FRUIT','RED','ORANGE','YELLOW','GREEN','PURPLE','WHITE','CREAM') NULL;
-- UPDATE product SET color_tone = CASE color_tone WHEN 'TEA' THEN 'RED' WHEN 'MILK' THEN 'CREAM' WHEN 'FRUIT' THEN 'YELLOW' ELSE color_tone END
--  WHERE product_id > 0 AND color_tone IN ('TEA','MILK','FRUIT');
-- ALTER TABLE product MODIFY COLUMN color_tone ENUM('RED','ORANGE','YELLOW','GREEN','PURPLE','WHITE','CREAM') NULL;

-- 設定範例：
-- UPDATE product SET color_tone = 'CREAM' WHERE name IN ('紅茶拿鐵', '鐵觀音拿鐵');
-- UPDATE product SET color_tone = NULL WHERE name = '紅茶拿鐵';   -- 改回自動判斷
--
-- 核對：
-- SELECT product_id, name, category, color_tone FROM product ORDER BY category, product_id;
