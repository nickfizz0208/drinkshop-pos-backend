-- 045：停售管理頁要不要顯示（飲品、半成品、原料、加料）
-- 僅由資料庫負責人確認後執行；需要先執行 028（停售）。可以重複執行（欄位已經存在就略過）。
-- 這版後端需要 045（停售管理會讀 show_in_sale），請先執行再部署。
--
-- show_in_sale：TRUE（預設）＝出現在停售管理頁；FALSE＝不出現（例如紙杯、吸管這種不會停售的包材）。
-- 只是不顯示：停售連動照常（隱藏的原料停售時，用到它的飲品一樣不能賣），點餐菜單也不受影響。
-- 欄位不叫 show：SHOW 是 MySQL 保留字，每次寫 SQL 都要加反引號。

DROP PROCEDURE IF EXISTS add_show_in_sale;
DELIMITER $$
CREATE PROCEDURE add_show_in_sale(IN t VARCHAR(64))
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = t AND COLUMN_NAME = 'show_in_sale') THEN
    SET @sql := CONCAT('ALTER TABLE ', t, ' ADD COLUMN show_in_sale BOOLEAN NOT NULL DEFAULT TRUE AFTER on_sale');
    PREPARE s FROM @sql;
    EXECUTE s;
    DEALLOCATE PREPARE s;
  END IF;
END$$
DELIMITER ;

CALL add_show_in_sale('product');
CALL add_show_in_sale('prep_item');
CALL add_show_in_sale('inventory');
CALL add_show_in_sale('topping');
DROP PROCEDURE add_show_in_sale;

-- 例：紙杯、吸管不出現在停售管理頁
-- UPDATE inventory SET show_in_sale = FALSE WHERE category = '包材' AND inventory_id > 0;

-- 核對：
-- SELECT 'product' AS 表, COUNT(*) AS 隱藏 FROM product WHERE show_in_sale = FALSE
-- UNION ALL SELECT 'prep_item', COUNT(*) FROM prep_item WHERE show_in_sale = FALSE
-- UNION ALL SELECT 'inventory', COUNT(*) FROM inventory WHERE show_in_sale = FALSE
-- UNION ALL SELECT 'topping', COUNT(*) FROM topping WHERE show_in_sale = FALSE;
