-- 024：每杯飲品分別記三種容量的價格（中杯 M、大杯 L、瓶 BOTTLE）
-- 僅由資料庫負責人確認後執行；需要先執行 023。執行前請先備份 product。這一版後端需要 024，請先執行 024 再部署。
--
-- 改動重點：
--   1. product 新增 price_m、price_l、price_bottle；NULL＝這杯不賣這個容量，至少要有一種容量有價格。
--   2. 舊飲品照原本的價格搬過去：中杯 = base_price，大杯 = base_price + large_extra。
--   3. 拿掉 base_price、large_extra（後端、前端都改讀新欄位）。
--   4. order_item.size 多一個 BOTTLE（瓶）。舊訂單的 M、L 不變。

-- 1. 新欄位
ALTER TABLE product
  ADD COLUMN price_m DECIMAL(10,2) NULL AFTER category,
  ADD COLUMN price_l DECIMAL(10,2) NULL AFTER price_m,
  ADD COLUMN price_bottle DECIMAL(10,2) NULL AFTER price_l;

-- 2. 舊價格搬過去（WHERE product_id > 0：MySQL Workbench 的 safe update 模式，錯誤 1175）
UPDATE product SET price_m = base_price, price_l = base_price + large_extra WHERE product_id > 0;

-- 3. 舊的 CHECK (base_price >= 0 AND large_extra >= 0) 是自動命名的，先依內容找出名稱再刪掉，才能刪欄位
SET @chk := (SELECT tc.CONSTRAINT_NAME
             FROM information_schema.TABLE_CONSTRAINTS tc
             JOIN information_schema.CHECK_CONSTRAINTS cc
               ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
             WHERE tc.TABLE_SCHEMA = DATABASE() AND tc.TABLE_NAME = 'product' AND cc.CHECK_CLAUSE LIKE '%large_extra%'
             LIMIT 1);
SET @sql := IF(@chk IS NULL, 'DO 0', CONCAT('ALTER TABLE product DROP CHECK `', @chk, '`'));
PREPARE drop_check FROM @sql;
EXECUTE drop_check;
DEALLOCATE PREPARE drop_check;

ALTER TABLE product
  DROP COLUMN base_price,
  DROP COLUMN large_extra,
  ADD CONSTRAINT chk_product_price_range CHECK ((price_m IS NULL OR price_m >= 0)
    AND (price_l IS NULL OR price_l >= 0) AND (price_bottle IS NULL OR price_bottle >= 0)),
  ADD CONSTRAINT chk_product_has_price CHECK (price_m IS NOT NULL OR price_l IS NOT NULL OR price_bottle IS NOT NULL);

-- 4. 訂單明細可以是瓶裝
ALTER TABLE order_item MODIFY COLUMN size ENUM('M','L','BOTTLE') NOT NULL;

-- 核對：每杯飲品的容量價格（NULL＝不賣）
-- SELECT product_id, name, category, price_m AS 中杯, price_l AS 大杯, price_bottle AS 瓶 FROM product ORDER BY product_id;
