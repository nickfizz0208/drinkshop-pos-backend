-- 033：設定茶類飲品的色系（032 的 color_tone）
-- 僅由資料庫負責人確認後執行；需要先執行 032。可以重複執行。只改分類是「茶類」的飲品，其他分類維持 NULL（前端自動判斷）。
--
-- 規則（依名稱，由上往下，先符合的先用）：
--   名稱有「金萱」         → YELLOW 黃   例：高山金萱茶、粉粿金萱、金萱雙Q
--   名稱有「青茶」「清茶」「蕎麥」 → ORANGE 橙  例：文山青茶、蕎麥綠寶石
--   名稱有「綠茶」         → GREEN 綠    例：翡翠綠茶、茉香綠茶
--   其他茶類              → RED 紅      例：錫蘭紅茶、古早味紅茶、紅萱烏龍（「紅萱」不是金萱）
-- 「蕎麥綠寶石」名稱有「綠」但沒有「綠茶」，依規則是橙。

UPDATE product
   SET color_tone = CASE
         WHEN name LIKE '%金萱%' THEN 'YELLOW'
         WHEN name LIKE '%青茶%' OR name LIKE '%清茶%' OR name LIKE '%蕎麥%' THEN 'ORANGE'
         WHEN name LIKE '%綠茶%' THEN 'GREEN'
         ELSE 'RED'
       END
 WHERE category = '茶類' AND product_id > 0;   -- product_id > 0：MySQL Workbench safe update 模式（錯誤 1175）

-- 核對：
-- SELECT product_id, name, color_tone FROM product WHERE category = '茶類' ORDER BY color_tone, product_id;
