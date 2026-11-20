-- 046：奶茶、鮮奶的組合飲品與首份免費加料
-- 僅由資料庫負責人確認後執行；需要先執行 039（加料份數）。可以重複執行（欄位已經存在就略過）。
-- 這版後端需要 046（菜單會讀 main、free，送單會寫 free_qty），請先執行再部署。
--
--   topping.free：TRUE＝可以組成組合飲品的加料（只放 10 元的料，例如波霸、椰果）。
--   product.main：TRUE＝可以當組合底的飲品（例如奶茶、紅茶拿鐵）。
--   點餐頁的「奶茶」「鮮奶」分類會自動在每杯 main 飲品後面排出「{加料}{飲料}」，例如「波霸紅茶拿鐵」；
--   點了就是那杯 main 飲品先加好一份那個加料，不會新增飲品資料。
--   分類是奶茶、鮮奶的飲品，第一份 free 加料不用錢（不管是不是從組合點的）；第二份以後照原價。
--   order_item_topping.free_qty：這筆加料有幾份是免費的（0 或 1）。這杯加料收的錢＝price_at_order ×（qty − free_qty）。

DROP PROCEDURE IF EXISTS add_column_if_missing;
DELIMITER $$
CREATE PROCEDURE add_column_if_missing(IN t VARCHAR(64), IN c VARCHAR(64), IN ddl VARCHAR(255))
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = t AND COLUMN_NAME = c) THEN
    SET @sql := CONCAT('ALTER TABLE ', t, ' ADD COLUMN ', ddl);
    PREPARE s FROM @sql;
    EXECUTE s;
    DEALLOCATE PREPARE s;
  END IF;
END$$
DELIMITER ;

CALL add_column_if_missing('topping', 'free', 'free BOOLEAN NOT NULL DEFAULT FALSE AFTER max_qty');
CALL add_column_if_missing('product', 'main', 'main BOOLEAN NOT NULL DEFAULT FALSE AFTER hot_allowed');
CALL add_column_if_missing('order_item_topping', 'free_qty', 'free_qty INT NOT NULL DEFAULT 0 AFTER qty');
DROP PROCEDURE add_column_if_missing;

-- 設定範例（名稱請照實際情況改）：
-- UPDATE topping SET free = TRUE WHERE price = 10 AND name IN ('波霸', '椰果', '雙Q果') AND topping_id > 0;
-- UPDATE product SET main = TRUE WHERE name IN ('奶茶', '紅茶拿鐵', '鐵觀音拿鐵') AND product_id > 0;

-- 核對：
-- SELECT topping_id, name, price, free FROM topping WHERE free = TRUE;
-- SELECT product_id, name, category, main FROM product WHERE main = TRUE;
