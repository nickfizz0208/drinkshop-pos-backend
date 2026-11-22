-- 038：溫度、冰量合成「溫度冰量」一個欄位，甜度改成幾分糖
-- 僅由資料庫負責人確認後執行；執行前請先備份 order_item。
-- ⚠ 這版後端需要 038；舊版後端在 038 之後無法送單（它還在寫 temperature、ice 欄位）。請 038 與新版前後端一起上線。
--
-- order_item.temp_ice（溫度冰量）：
--   HOT 熱、WARM 溫、ROOM 常溫、ICE_FREE 完全去冰、NO_ICE 去冰、LIGHT 微冰、LESS 少冰、NORMAL 正常冰、EXTRA 多冰
--   舊資料換算：熱飲→HOT、溫飲→WARM；冷飲依冰量：去冰→NO_ICE、微冰→LIGHT、少冰→LESS、正常冰→NORMAL
-- order_item.sugar（甜度）：
--   NONE 無糖、S0_5 0.5 分、S1 1 分、S3 3 分、S5 5 分、S7 7 分、S10 10 分、EXTRA 多糖
--   舊資料換算：正常糖→S10、少糖→S7、半糖→S5、微糖→S3、無糖→NONE
--
-- conflict_rule 不用改：舊規則（temperatureIn、固定 temperature／ice／舊甜度）後端與前端都會換算後比對。
-- 新規則可以直接寫 {"tempIceIn": ["HOT","WARM"]}、{"set": {"tempIce": "NORMAL", "sugar": "S10"}}。

-- 1. 新欄位，先允許空白，從舊欄位換算
ALTER TABLE order_item
  ADD COLUMN temp_ice ENUM('HOT','WARM','ROOM','ICE_FREE','NO_ICE','LIGHT','LESS','NORMAL','EXTRA') NULL AFTER size;

-- WHERE item_id > 0：MySQL Workbench 的 safe update 模式（錯誤 1175）
UPDATE order_item SET temp_ice = CASE
    WHEN temperature = 'HOT'  THEN 'HOT'
    WHEN temperature = 'WARM' THEN 'WARM'
    WHEN ice = 'NONE'  THEN 'NO_ICE'
    WHEN ice = 'LIGHT' THEN 'LIGHT'
    WHEN ice = 'LESS'  THEN 'LESS'
    ELSE 'NORMAL'
  END
WHERE item_id > 0;

-- 基礎建表的 CHECK 依賴舊欄位，MySQL 會拒絕直接刪除。
SET @old_temp_ice_check := (
  SELECT tc.CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS tc
  JOIN information_schema.CHECK_CONSTRAINTS cc
    ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
  WHERE tc.TABLE_SCHEMA=DATABASE() AND tc.TABLE_NAME='order_item'
    AND tc.CONSTRAINT_TYPE='CHECK' AND LOWER(cc.CHECK_CLAUSE) LIKE '%temperature%'
    AND LOWER(cc.CHECK_CLAUSE) LIKE '%ice%'
);
SET @drop_temp_ice_check := IF(@old_temp_ice_check IS NULL, 'DO 0',
  CONCAT('ALTER TABLE order_item DROP CHECK `', REPLACE(@old_temp_ice_check, '`', '``'), '`'));
PREPARE drop_temp_ice_check FROM @drop_temp_ice_check;
EXECUTE drop_temp_ice_check;
DEALLOCATE PREPARE drop_temp_ice_check;

-- 2. 換好後改成必填，拿掉舊的溫度、冰量
ALTER TABLE order_item
  MODIFY COLUMN temp_ice ENUM('HOT','WARM','ROOM','ICE_FREE','NO_ICE','LIGHT','LESS','NORMAL','EXTRA') NOT NULL,
  DROP COLUMN temperature,
  DROP COLUMN ice;

-- 3. 甜度：先允許新舊值並存，換算後只留新值
ALTER TABLE order_item
  MODIFY COLUMN sugar ENUM('NORMAL','LESS','HALF','LIGHT','NONE','S0_5','S1','S3','S5','S7','S10','EXTRA') NOT NULL;

UPDATE order_item SET sugar = CASE sugar
    WHEN 'NORMAL' THEN 'S10'
    WHEN 'LESS'   THEN 'S7'
    WHEN 'HALF'   THEN 'S5'
    WHEN 'LIGHT'  THEN 'S3'
    ELSE sugar
  END
WHERE item_id > 0;

ALTER TABLE order_item
  MODIFY COLUMN sugar ENUM('NONE','S0_5','S1','S3','S5','S7','S10','EXTRA') NOT NULL;

-- 核對：
-- SELECT temp_ice, sugar, COUNT(*) FROM order_item GROUP BY temp_ice, sugar ORDER BY temp_ice, sugar;
