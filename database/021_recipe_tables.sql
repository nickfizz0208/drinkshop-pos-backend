-- 021：配方改成「材料清單」，並加入半成品
-- 僅由資料庫負責人確認後執行；不要忽略錯誤重跑。需要先執行 016–020。執行前請先備份 product、topping、inventory。
--
-- 改動重點：
--   1. 配方只記「這杯用了哪些材料」，不記份量；份量與做法寫在 product.recipe_steps（文字）。
--      中杯、大杯共用一份材料清單；紙杯不列入（每杯都會用）。
--   2. 新增半成品 prep_item（例如茶凍、紅茶湯），記錄它由哪些原料做成（prep_item_ingredient）。
--   3. product_recipe 每一列是「原料」或「半成品」其中一個。
--   4. 加料 topping 可以指向原料或半成品；不再記扣料量（拿掉 deduct_qty）。
--   5. 茶凍改成半成品：以「加料『茶凍』對應的原料」判斷——
--      那筆原料就叫「茶凍」（做好的成品，範例資料）→ 改成半成品後刪掉這筆原料；
--      是「茶凍粉」這類材料（共用庫）→ 保留原料，記成半成品「茶凍」的材料。
--      配方與加料裡用到那筆原料的地方，都改指向半成品「茶凍」。
--   6. 拿掉 product.recipe_json。點餐不再讀配方，每杯不再計算原料成本（order_item.material_cost 之後都是 NULL，舊訂單保留）。
--
-- 搬資料的規則：舊的 recipe_json 分中杯、大杯。兩種杯型都有的原料才列入（紙杯中大杯不同，自然排除）；
-- 只有一種杯型的飲品，該杯型的原料全部列入（可能含紙杯，請用最後的核對查詢檢查）。
-- 舊配方指到不存在的原料會略過，執行前可先用下面這段找出來：
--   SELECT p.product_id, p.name, j.inventory_id FROM product p,
--     JSON_TABLE(p.recipe_json, '$.*[*]' COLUMNS(inventory_id BIGINT PATH '$.inventoryId')) j
--   WHERE NOT EXISTS (SELECT 1 FROM inventory i WHERE i.inventory_id = j.inventory_id);

-- 1. 半成品
CREATE TABLE prep_item (
  prep_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(30) NOT NULL,
  steps TEXT NULL,
  CONSTRAINT uq_prep_item_name UNIQUE (name)
) ENGINE=InnoDB;

CREATE TABLE prep_item_ingredient (
  prep_id BIGINT NOT NULL,
  inventory_id BIGINT NOT NULL,
  PRIMARY KEY (prep_id, inventory_id),
  CONSTRAINT fk_prep_ingredient_prep FOREIGN KEY (prep_id) REFERENCES prep_item(prep_id),
  CONSTRAINT fk_prep_ingredient_inventory FOREIGN KEY (inventory_id) REFERENCES inventory(inventory_id)
) ENGINE=InnoDB;

-- 2. 飲品配方（材料清單）：原料、半成品剛好填一個
CREATE TABLE product_recipe (
  line_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  product_id BIGINT NOT NULL,
  inventory_id BIGINT NULL,
  prep_id BIGINT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  CONSTRAINT fk_product_recipe_product FOREIGN KEY (product_id) REFERENCES product(product_id),
  CONSTRAINT fk_product_recipe_inventory FOREIGN KEY (inventory_id) REFERENCES inventory(inventory_id),
  CONSTRAINT fk_product_recipe_prep FOREIGN KEY (prep_id) REFERENCES prep_item(prep_id),
  CONSTRAINT uq_product_recipe_inventory UNIQUE (product_id, inventory_id),
  CONSTRAINT uq_product_recipe_prep UNIQUE (product_id, prep_id),
  CONSTRAINT chk_product_recipe_one CHECK ((inventory_id IS NULL) <> (prep_id IS NULL))
) ENGINE=InnoDB;

-- 3. 茶凍改成半成品（有加料「茶凍」才建）。這一步可以重複執行
--    @jelly_inv：加料「茶凍」對應的原料；@jelly_prep：半成品「茶凍」。後面的步驟都會用到，請和後面一起執行
SET @jelly_inv := (SELECT inventory_id FROM topping WHERE name = '茶凍' LIMIT 1);
INSERT INTO prep_item (name, steps)
SELECT '茶凍', NULL FROM DUAL
WHERE @jelly_inv IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prep_item WHERE name = '茶凍');
SET @jelly_prep := (SELECT prep_id FROM prep_item WHERE name = '茶凍');
-- 那筆原料不是成品本身（例如茶凍粉）：記成茶凍的材料
INSERT INTO prep_item_ingredient (prep_id, inventory_id)
SELECT @jelly_prep, i.inventory_id FROM inventory i
WHERE i.inventory_id = @jelly_inv AND i.name <> '茶凍' AND @jelly_prep IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM prep_item_ingredient g WHERE g.prep_id = @jelly_prep AND g.inventory_id = i.inventory_id);

-- 4. 從 recipe_json 搬出材料清單（暫存表，最後刪除）
--    暫存表也要有主鍵：Aiven 開了 sql_require_primary_key，沒有主鍵的表會被擋（錯誤 3750）
CREATE TABLE recipe_migration_021 (
  product_id BIGINT NOT NULL,
  size VARCHAR(2) NOT NULL,
  ord INT NOT NULL,
  inventory_id BIGINT NULL,
  PRIMARY KEY (product_id, size, ord)
) ENGINE=InnoDB;

INSERT INTO recipe_migration_021 (product_id, size, ord, inventory_id)
SELECT p.product_id, s.size, j.ord, j.inventory_id
FROM product p
JOIN (SELECT 'M' AS size UNION ALL SELECT 'L') s
JOIN JSON_TABLE(JSON_EXTRACT(p.recipe_json, CONCAT('$.', s.size)), '$[*]'
       COLUMNS (ord FOR ORDINALITY, inventory_id BIGINT PATH '$.inventoryId')) j;

CREATE TABLE recipe_migration_021_sizes (
  product_id BIGINT NOT NULL PRIMARY KEY,
  size_count INT NOT NULL
) ENGINE=InnoDB;

INSERT INTO recipe_migration_021_sizes (product_id, size_count)
SELECT product_id, COUNT(DISTINCT size) FROM recipe_migration_021 GROUP BY product_id;

INSERT INTO product_recipe (product_id, inventory_id, prep_id, sort_order)
SELECT t.product_id,
       CASE WHEN t.inventory_id = @jelly_inv AND @jelly_prep IS NOT NULL THEN NULL ELSE t.inventory_id END,
       CASE WHEN t.inventory_id = @jelly_inv AND @jelly_prep IS NOT NULL THEN @jelly_prep END,
       MIN(t.ord)
FROM recipe_migration_021 t
JOIN inventory i ON i.inventory_id = t.inventory_id
JOIN recipe_migration_021_sizes c ON c.product_id = t.product_id
GROUP BY t.product_id, t.inventory_id, i.name, c.size_count
HAVING c.size_count = 1 OR COUNT(DISTINCT t.size) = 2;

DROP TABLE recipe_migration_021;
DROP TABLE recipe_migration_021_sizes;

-- 5. 加料：可指向原料或半成品；拿掉扣料量
--    舊的 CHECK (price >= 0 AND deduct_qty > 0) 是自動命名的，先依內容找出名稱再刪掉
SET @chk := (SELECT tc.CONSTRAINT_NAME
             FROM information_schema.TABLE_CONSTRAINTS tc
             JOIN information_schema.CHECK_CONSTRAINTS cc
               ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
             WHERE tc.TABLE_SCHEMA = DATABASE() AND tc.TABLE_NAME = 'topping' AND cc.CHECK_CLAUSE LIKE '%deduct_qty%'
             LIMIT 1);
SET @sql := IF(@chk IS NULL, 'DO 0', CONCAT('ALTER TABLE topping DROP CHECK `', @chk, '`'));
PREPARE drop_check FROM @sql;
EXECUTE drop_check;
DEALLOCATE PREPARE drop_check;

ALTER TABLE topping
  DROP COLUMN deduct_qty,
  MODIFY COLUMN inventory_id BIGINT NULL,
  ADD COLUMN prep_id BIGINT NULL,
  ADD CONSTRAINT fk_topping_prep FOREIGN KEY (prep_id) REFERENCES prep_item(prep_id),
  ADD CONSTRAINT chk_topping_price CHECK (price >= 0),
  ADD CONSTRAINT chk_topping_one CHECK ((inventory_id IS NULL) <> (prep_id IS NULL));

-- WHERE topping_id > 0：MySQL Workbench 的 safe update 模式（錯誤 1175）
UPDATE topping SET prep_id = @jelly_prep, inventory_id = NULL
WHERE name = '茶凍' AND @jelly_prep IS NOT NULL AND topping_id > 0;

-- 6. 原料本身就叫「茶凍」（成品）才拿掉：先從盤點類型移除；有盤點或報廢紀錄的話保留（避免歷史紀錄斷掉）
--    「茶凍粉」這類材料不會被刪
DELETE FROM inventory_count_type_item
WHERE inventory_id = @jelly_inv AND type_id > 0
  AND inventory_id IN (SELECT inventory_id FROM (SELECT inventory_id FROM inventory WHERE name = '茶凍') x);

DELETE FROM inventory
WHERE inventory_id = @jelly_inv AND name = '茶凍'
  AND NOT EXISTS (SELECT 1 FROM inventory_count_line l WHERE l.inventory_id = inventory.inventory_id)
  AND NOT EXISTS (SELECT 1 FROM raw_material_wastage w WHERE w.inventory_id = inventory.inventory_id);

-- 7. 拿掉舊的配方 JSON
ALTER TABLE product DROP COLUMN recipe_json;

-- 核對：
-- 每杯飲料的材料清單（確認沒有漏、沒有混進紙杯）
-- SELECT p.name AS 飲品, COALESCE(i.name, CONCAT('半成品：', x.name)) AS 材料
--   FROM product_recipe r JOIN product p ON p.product_id = r.product_id
--   LEFT JOIN inventory i ON i.inventory_id = r.inventory_id LEFT JOIN prep_item x ON x.prep_id = r.prep_id
--  ORDER BY p.product_id, r.sort_order;
-- 加料指向哪裡
-- SELECT t.name, i.name AS 原料, x.name AS 半成品 FROM topping t
--   LEFT JOIN inventory i ON i.inventory_id = t.inventory_id LEFT JOIN prep_item x ON x.prep_id = t.prep_id;
-- 半成品「茶凍」由哪些原料做成（共用庫應該是茶凍粉）
-- SELECT x.name, i.name FROM prep_item x LEFT JOIN prep_item_ingredient g ON g.prep_id = x.prep_id
--   LEFT JOIN inventory i ON i.inventory_id = g.inventory_id;
-- 原料「茶凍」（成品）還在的話，代表它有盤點或報廢紀錄，請和負責人討論是否保留
-- SELECT * FROM inventory WHERE name = '茶凍';
