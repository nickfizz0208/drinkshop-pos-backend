-- 018：盤點類型
-- 僅由資料庫負責人確認後執行；不要忽略錯誤重跑。需要先執行 016、017。
--
-- 盤點時先選類型（例如大盤、小盤），類型決定：
--   input_mode  輸入方式：PACK_AND_LOOSE＝箱數＋零散；PACK_ONLY＝只填箱數（有包裝單位的原料記成 箱數 × 每箱數量）
--   all_items   TRUE＝全部原料（之後新增的原料自動包含）；FALSE＝只盤 inventory_count_type_item 列出的原料
-- 類型由店長、總部在盤點頁設定；停用（active＝FALSE）的類型不能再用來盤點，舊紀錄不受影響。
-- 每次盤點的表頭另存當時的類型名稱與輸入方式，之後類型被修改，舊紀錄仍看得出當初怎麼盤的。

CREATE TABLE inventory_count_type (
  type_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(20) NOT NULL,
  input_mode VARCHAR(20) NOT NULL,
  all_items BOOLEAN NOT NULL DEFAULT FALSE,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  updated_by BIGINT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT uq_count_type_name UNIQUE (name),
  CONSTRAINT fk_count_type_staff FOREIGN KEY (updated_by) REFERENCES staff_account(staff_id),
  CONSTRAINT chk_count_type_mode CHECK (input_mode IN ('PACK_AND_LOOSE', 'PACK_ONLY'))
) ENGINE=InnoDB;

CREATE TABLE inventory_count_type_item (
  type_id BIGINT NOT NULL,
  inventory_id BIGINT NOT NULL,
  PRIMARY KEY (type_id, inventory_id),
  CONSTRAINT fk_count_type_item_type FOREIGN KEY (type_id) REFERENCES inventory_count_type(type_id),
  CONSTRAINT fk_count_type_item_inventory FOREIGN KEY (inventory_id) REFERENCES inventory(inventory_id)
) ENGINE=InnoDB;

-- 預設只建「大盤」：全部原料、箱數＋零散。小盤等其他類型由店長自己建。
INSERT INTO inventory_count_type (name, input_mode, all_items, active, updated_at)
VALUES ('大盤', 'PACK_AND_LOOSE', TRUE, TRUE, NOW(6));

-- 盤點表頭記錄這次用的類型；017 之前的紀錄都是「箱數＋零散」，歸為大盤
ALTER TABLE inventory_count
  ADD COLUMN type_id BIGINT NULL,
  ADD COLUMN type_name VARCHAR(20) NULL,
  ADD COLUMN input_mode VARCHAR(20) NULL;

-- WHERE count_id > 0 是為了 MySQL Workbench 的 safe update 模式（錯誤 1175），效果等於全部紀錄
UPDATE inventory_count c JOIN inventory_count_type t ON t.name = '大盤'
SET c.type_id = t.type_id, c.type_name = t.name, c.input_mode = t.input_mode
WHERE c.count_id > 0;

ALTER TABLE inventory_count
  MODIFY COLUMN type_id BIGINT NOT NULL,
  MODIFY COLUMN type_name VARCHAR(20) NOT NULL,
  MODIFY COLUMN input_mode VARCHAR(20) NOT NULL,
  ADD CONSTRAINT fk_inventory_count_type FOREIGN KEY (type_id) REFERENCES inventory_count_type(type_id),
  ADD CONSTRAINT chk_inventory_count_mode CHECK (input_mode IN ('PACK_AND_LOOSE', 'PACK_ONLY'));

-- 核對：沒有任何一筆是空的
-- SELECT COUNT(*) FROM inventory_count WHERE type_id IS NULL;
