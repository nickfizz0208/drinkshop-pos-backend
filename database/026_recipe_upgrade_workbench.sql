-- 021–022：配方材料表、半成品與材料份數，Workbench 單檔升級入口。
-- 前置版本：009、016–020。請先備份、暫停後端，並在同一個連線執行整份檔案。
-- 需要對 drinkshop_pos 有 CREATE/ALTER ROUTINE、EXECUTE、CREATE TEMPORARY TABLES 及遷移所需的表結構和資料權限。
-- 已完成 021 的資料庫只補 022；已完成 022 的資料庫不改資料。
-- MySQL DDL 會隱式提交。任何錯誤都應停止，核對已執行的語句後再處理。
USE drinkshop_pos;

DELIMITER $$
CREATE PROCEDURE migrate_recipe_021_022()
SQL SECURITY INVOKER
BEGIN
  DECLARE recipe_tables INT DEFAULT 0;
  DECLARE old_json INT DEFAULT 0;
  DECLARE archived_json INT DEFAULT 0;
  DECLARE topping_prep INT DEFAULT 0;
  DECLARE old_deduct INT DEFAULT 0;
  DECLARE topping_inventory_nullable INT DEFAULT 0;
  DECLARE portions_columns INT DEFAULT 0;
  DECLARE valid_portions_columns INT DEFAULT 0;
  DECLARE portions_checks INT DEFAULT 0;
  DECLARE valid_portions_checks INT DEFAULT 0;
  DECLARE legacy_topping_check VARCHAR(64);
  DECLARE jelly_in_archive BOOLEAN DEFAULT FALSE;
  DECLARE run_021 BOOLEAN DEFAULT FALSE;
  DECLARE saved_safe_updates INT;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    SET SESSION SQL_SAFE_UPDATES = saved_safe_updates;
    RESIGNAL;
  END;

  SET saved_safe_updates = @@SESSION.SQL_SAFE_UPDATES;

  SELECT COUNT(*) INTO recipe_tables
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'
    AND TABLE_NAME IN ('prep_item', 'prep_item_ingredient', 'product_recipe');

  SELECT COUNT(CASE WHEN TABLE_NAME = 'product' AND COLUMN_NAME = 'recipe_json' THEN 1 END),
         COUNT(CASE WHEN TABLE_NAME = 'product' AND COLUMN_NAME = 'recipe_json_legacy' THEN 1 END),
         COUNT(CASE WHEN TABLE_NAME = 'topping' AND COLUMN_NAME = 'prep_id' THEN 1 END),
         COUNT(CASE WHEN TABLE_NAME = 'topping' AND COLUMN_NAME = 'deduct_qty' THEN 1 END),
         COUNT(CASE WHEN TABLE_NAME = 'topping' AND COLUMN_NAME = 'inventory_id' AND IS_NULLABLE = 'YES' THEN 1 END),
         COUNT(CASE WHEN TABLE_NAME = 'product_recipe' AND COLUMN_NAME = 'portions' THEN 1 END),
         COUNT(CASE WHEN TABLE_NAME = 'product_recipe' AND COLUMN_NAME = 'portions'
                     AND COLUMN_TYPE = 'decimal(8,2)' AND IS_NULLABLE = 'YES' AND COLUMN_DEFAULT IS NULL THEN 1 END)
  INTO old_json, archived_json, topping_prep, old_deduct, topping_inventory_nullable,
       portions_columns, valid_portions_columns
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('product', 'topping', 'product_recipe');

  SELECT COUNT(*),
         COUNT(CASE WHEN tc.ENFORCED = 'YES'
                     AND REGEXP_REPLACE(cc.CHECK_CLAUSE, '[[:space:]`()]+', '') = 'portions>0' THEN 1 END)
  INTO portions_checks, valid_portions_checks
  FROM information_schema.TABLE_CONSTRAINTS tc
  JOIN information_schema.CHECK_CONSTRAINTS cc
    ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
  WHERE tc.TABLE_SCHEMA = DATABASE() AND tc.TABLE_NAME = 'product_recipe'
    AND tc.CONSTRAINT_NAME = 'chk_product_recipe_portions';

  IF recipe_tables = 0 AND old_json = 1 AND archived_json = 0
     AND topping_prep = 0 AND old_deduct = 1 AND topping_inventory_nullable = 0 THEN
    SET run_021 = TRUE;
  ELSEIF NOT (recipe_tables = 3 AND old_json = 0 AND topping_prep = 1
              AND old_deduct = 0 AND topping_inventory_nullable = 1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Unrecognized or partially applied 021 schema; no tables changed.';
  END IF;

  IF NOT run_021 AND (
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND (TABLE_NAME, COLUMN_NAME) IN (
       ('prep_item', 'prep_id'), ('prep_item', 'name'), ('prep_item', 'steps'),
       ('prep_item_ingredient', 'prep_id'), ('prep_item_ingredient', 'inventory_id'),
       ('product_recipe', 'line_id'), ('product_recipe', 'product_id'), ('product_recipe', 'inventory_id'),
       ('product_recipe', 'prep_id'), ('product_recipe', 'sort_order'))) <> 10
    OR (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
        WHERE TABLE_SCHEMA = DATABASE() AND REFERENCED_TABLE_SCHEMA = DATABASE()
          AND (TABLE_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME) IN (
            ('prep_item_ingredient', 'prep_id', 'prep_item', 'prep_id'),
            ('prep_item_ingredient', 'inventory_id', 'inventory', 'inventory_id'),
            ('product_recipe', 'product_id', 'product', 'product_id'),
            ('product_recipe', 'inventory_id', 'inventory', 'inventory_id'),
            ('product_recipe', 'prep_id', 'prep_item', 'prep_id'),
            ('topping', 'prep_id', 'prep_item', 'prep_id'),
            ('topping', 'inventory_id', 'inventory', 'inventory_id'))) <> 7
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Incomplete 021 recipe columns or foreign keys; no tables changed.';
  END IF;

  IF NOT ((portions_columns = 0 AND portions_checks = 0)
          OR (valid_portions_columns = 1 AND valid_portions_checks = 1)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Unrecognized or partially applied 022 schema; no tables changed.';
  END IF;

  IF run_021 THEN
    IF (SELECT COUNT(*) FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'
          AND TABLE_NAME IN ('inventory', 'inventory_count_line', 'inventory_count_type_item', 'raw_material_wastage')) <> 4
       OR NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'inventory' AND COLUMN_NAME = 'display_by_pack')
       OR EXISTS (SELECT 1 FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'inventory' AND COLUMN_NAME = 'allow_decimal') THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Complete prerequisites 009 and 016-020 before running 021.';
    END IF;

    SELECT CASE WHEN COUNT(*) = 1 THEN MAX(tc.CONSTRAINT_NAME) END INTO legacy_topping_check
    FROM information_schema.TABLE_CONSTRAINTS tc
    JOIN information_schema.CHECK_CONSTRAINTS cc
      ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
    WHERE tc.TABLE_SCHEMA = DATABASE() AND tc.TABLE_NAME = 'topping' AND tc.ENFORCED = 'YES'
      AND REGEXP_REPLACE(LOWER(cc.CHECK_CLAUSE), '[[:space:]`()]+', '') = 'price>=0anddeduct_qty>0';
    IF legacy_topping_check IS NULL THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Pre-021 topping check is missing or changed; no tables changed.';
    END IF;

    SET SESSION SQL_SAFE_UPDATES = 0;

    -- 先展開舊資料。有些配方在 JSON 欄位內又包成一個字串，需先解包。
    CREATE TEMPORARY TABLE recipe_migration_021 (
      product_id BIGINT NOT NULL,
      size_index INT NOT NULL,
      ord INT NOT NULL,
      inventory_id BIGINT NULL,
      PRIMARY KEY (product_id, size_index, ord)
    ) ENGINE=InnoDB;
    INSERT INTO recipe_migration_021 (product_id, size_index, ord, inventory_id)
    SELECT p.product_id, s.size_index, j.ord, j.inventory_id
    FROM (
      SELECT product_id, CASE WHEN JSON_TYPE(recipe_json) = 'STRING'
        THEN CAST(JSON_UNQUOTE(recipe_json) AS JSON) ELSE recipe_json END AS recipe
      FROM product
    ) p
    JOIN JSON_TABLE(JSON_KEYS(p.recipe), '$[*]'
           COLUMNS (size_index FOR ORDINALITY, size_name VARCHAR(255) PATH '$')) s
    JOIN JSON_TABLE(JSON_EXTRACT(p.recipe, CONCAT('$.', JSON_QUOTE(s.size_name))), '$[*]'
           COLUMNS (ord FOR ORDINALITY, inventory_id BIGINT PATH '$.inventoryId')) j;
    CREATE TEMPORARY TABLE recipe_migration_021_sizes (
      product_id BIGINT NOT NULL PRIMARY KEY,
      size_count INT NOT NULL
    ) ENGINE=InnoDB;
    INSERT INTO recipe_migration_021_sizes (product_id, size_count)
    SELECT product_id, COUNT(DISTINCT size_index) FROM recipe_migration_021 GROUP BY product_id;
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
    -- 4. 從舊配方搬出材料清單
    INSERT INTO product_recipe (product_id, inventory_id, prep_id, sort_order)
    SELECT t.product_id,
           CASE WHEN t.inventory_id = @jelly_inv AND @jelly_prep IS NOT NULL THEN NULL ELSE t.inventory_id END,
           CASE WHEN t.inventory_id = @jelly_inv AND @jelly_prep IS NOT NULL THEN @jelly_prep END,
           MIN(t.ord)
    FROM recipe_migration_021 t
    JOIN inventory i ON i.inventory_id = t.inventory_id
    JOIN recipe_migration_021_sizes c ON c.product_id = t.product_id
    GROUP BY t.product_id, t.inventory_id, i.name, c.size_count
    HAVING COUNT(DISTINCT t.size_index) = c.size_count;
    DROP TEMPORARY TABLE recipe_migration_021;
    DROP TEMPORARY TABLE recipe_migration_021_sizes;
    -- 5. 加料：可指向原料或半成品；拿掉扣料量
    --    舊的 CHECK (price >= 0 AND deduct_qty > 0) 是自動命名的，先依內容找出名稱再刪掉
    SET @sql := CONCAT('ALTER TABLE topping DROP CHECK `', REPLACE(legacy_topping_check, '`', '``'), '`');
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
    -- 6. 只有沒有其他用途或歷史紀錄的成品茶凍才能移除。
    IF EXISTS (SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'inventory_count_016') THEN
      SELECT EXISTS(SELECT 1 FROM inventory_count_016 WHERE inventory_id = @jelly_inv) INTO jelly_in_archive;
    END IF;
    IF NOT jelly_in_archive
       AND EXISTS (SELECT 1 FROM inventory WHERE inventory_id = @jelly_inv AND name = '茶凍')
       AND NOT EXISTS (SELECT 1 FROM topping WHERE inventory_id = @jelly_inv)
       AND NOT EXISTS (SELECT 1 FROM inventory_count_line WHERE inventory_id = @jelly_inv)
       AND NOT EXISTS (SELECT 1 FROM raw_material_wastage WHERE inventory_id = @jelly_inv) THEN
      DELETE FROM inventory_count_type_item WHERE inventory_id = @jelly_inv;
      DELETE FROM inventory WHERE inventory_id = @jelly_inv;
    END IF;
    -- 7. 封存舊配方，保留材料份量與遷移前的內容供核對
    ALTER TABLE product CHANGE COLUMN recipe_json recipe_json_legacy JSON NULL;

  END IF;

  IF portions_columns = 0 THEN
    ALTER TABLE product_recipe
      ADD COLUMN portions DECIMAL(8,2) NULL,
      ADD CONSTRAINT chk_product_recipe_portions CHECK (portions > 0);
  END IF;

  SET SESSION SQL_SAFE_UPDATES = saved_safe_updates;
  SELECT CASE
    WHEN run_021 THEN 'Applied 021 and 022'
    WHEN portions_columns = 0 THEN 'Applied 022; 021 already complete'
    ELSE '021 and 022 already complete'
  END AS migration_result;
END$$
DELIMITER ;

CALL migrate_recipe_021_022();
DROP PROCEDURE migrate_recipe_021_022;
