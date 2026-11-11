-- 017：盤點紀錄拆成「表頭／明細」，並把 allow_decimal 預設改成不能填小數
-- 僅由資料庫負責人確認後執行；不要忽略錯誤重跑。執行前請先備份 inventory_count。
--
-- 016 的 inventory_count 是一項原料一列，同一次盤點的 request_id、盤點人、時間、備註每列都重複存。
-- 017 改成：
--   inventory_count       表頭，一次盤點一列（request_id、盤點人、時間、備註）
--   inventory_count_line  明細，每項原料一列（實點、當時帳面、差異、當時單位成本）
-- 舊表改名為 inventory_count_016 保留，核對筆數無誤後再手動刪除（見檔案最後）。
-- 限制都明確命名，避免和舊表自動產生的名稱衝突。

-- 1. 舊表改名保留
RENAME TABLE inventory_count TO inventory_count_016;

-- 2. 表頭
CREATE TABLE inventory_count (
  count_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id CHAR(36) NOT NULL,
  counted_by BIGINT NOT NULL,
  counted_at DATETIME(6) NOT NULL,
  note VARCHAR(255) NULL,
  CONSTRAINT uq_inventory_count_request_v2 UNIQUE (request_id),
  CONSTRAINT fk_inventory_count_staff FOREIGN KEY (counted_by) REFERENCES staff_account(staff_id),
  INDEX idx_inventory_count_at (counted_at)
) ENGINE=InnoDB;

-- 3. 明細：同一次盤點同一項原料只能一列（主鍵）
--    book_qty、unit_cost 是盤點當下的快照，原料主檔之後改了也不影響
CREATE TABLE inventory_count_line (
  count_id BIGINT NOT NULL,
  inventory_id BIGINT NOT NULL,
  counted_qty DECIMAL(12,2) NOT NULL,
  book_qty DECIMAL(12,2) NOT NULL,
  diff_qty DECIMAL(12,2) NOT NULL,
  unit_cost DECIMAL(10,2) NULL,
  PRIMARY KEY (count_id, inventory_id),
  CONSTRAINT fk_count_line_count FOREIGN KEY (count_id) REFERENCES inventory_count(count_id),
  CONSTRAINT fk_count_line_inventory FOREIGN KEY (inventory_id) REFERENCES inventory(inventory_id),
  CONSTRAINT chk_count_line_qty CHECK (counted_qty >= 0 AND book_qty >= 0),
  CONSTRAINT chk_count_line_diff CHECK (diff_qty = counted_qty - book_qty),
  CONSTRAINT chk_count_line_cost CHECK (unit_cost IS NULL OR unit_cost >= 0)
) ENGINE=InnoDB;

-- 4. 搬資料：依 request_id 合併成一次盤點（同一次的盤點人、時間、備註本來就相同）
INSERT INTO inventory_count (request_id, counted_by, counted_at, note)
SELECT request_id, MIN(counted_by), MIN(counted_at), MAX(note)
FROM inventory_count_016 GROUP BY request_id;

INSERT INTO inventory_count_line (count_id, inventory_id, counted_qty, book_qty, diff_qty, unit_cost)
SELECT h.count_id, o.inventory_id, o.counted_qty, o.book_qty, o.diff_qty, o.unit_cost
FROM inventory_count_016 o JOIN inventory_count h ON h.request_id = o.request_id;

-- 5. allow_decimal 預設改成不能填小數（之後新增的原料）
ALTER TABLE inventory ALTER COLUMN allow_decimal SET DEFAULT FALSE;

-- 6. 現有原料也改成不能填小數。需要小數的原料（例如奶料）之後再個別打開：
--      UPDATE inventory SET allow_decimal = TRUE WHERE name IN ('奶料');
--    如果已經有手動設定過、想保留目前的值，請略過這一行。
--    WHERE inventory_id > 0 是為了 MySQL Workbench 的 safe update 模式（沒有用主鍵當條件會被擋，錯誤 1175），效果等於全部原料。
UPDATE inventory SET allow_decimal = FALSE WHERE inventory_id > 0;

-- 7. 核對：兩個數字要相同，表頭筆數要等於舊表不同 request_id 的數量
-- SELECT (SELECT COUNT(*) FROM inventory_count_016) AS old_rows, (SELECT COUNT(*) FROM inventory_count_line) AS new_lines;
-- SELECT (SELECT COUNT(DISTINCT request_id) FROM inventory_count_016) AS old_counts, (SELECT COUNT(*) FROM inventory_count) AS new_counts;
-- 核對無誤後再刪除舊表：
-- DROP TABLE inventory_count_016;
