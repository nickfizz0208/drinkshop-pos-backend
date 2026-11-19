-- 036：飲品色系多一個「咖」（BROWN，咖啡色）
-- 僅由資料庫負責人確認後執行；需要先執行 032。可以重複執行（MODIFY 成同樣的定義不會有變化）。
-- 前端已經有咖色的顏色；執行前資料庫填 BROWN 會被拒絕。

ALTER TABLE product
  MODIFY COLUMN color_tone ENUM('RED','ORANGE','YELLOW','GREEN','PURPLE','WHITE','CREAM','BROWN') NULL;

-- 設定範例（記得 WHERE 要有條件；product_id > 0 是 Workbench safe update 模式要的）：
-- UPDATE product SET color_tone = 'BROWN' WHERE name = '開心果可可' AND product_id > 0;
