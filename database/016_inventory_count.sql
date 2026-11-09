-- 016：原料盤點紀錄
-- 僅由資料庫負責人確認後升級；不要自行對 Aiven 執行，也不要忽略錯誤重跑。
-- 每次盤點每項原料一列：保存當時的帳面數量、實點數量、差異與單位成本（之後成本改了也不影響舊紀錄）。
-- 盤點只記錄，不修改 inventory.book_qty；inventory.reported_qty／reported_by／reported_at 仍會更新成最新一次。
-- request_id 由畫面產生；網路逾時重送同一筆不會重複記錄。
-- 另在 inventory 加 allow_decimal：盤點時「小單位（基本單位）」可不可以填小數；大單位（包裝數）一律整數。
-- 預設 TRUE（可以填小數），現有原料行為不變；只能填整數的原料再個別改，例如：
--   UPDATE inventory SET allow_decimal = FALSE WHERE name IN ('中杯紙杯', '大杯紙杯');
CREATE TABLE inventory_count (
  count_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id CHAR(36) NOT NULL,
  inventory_id BIGINT NOT NULL,
  counted_qty DECIMAL(12,2) NOT NULL,
  book_qty DECIMAL(12,2) NOT NULL,
  diff_qty DECIMAL(12,2) NOT NULL,
  unit_cost DECIMAL(10,2) NULL,
  counted_by BIGINT NOT NULL,
  counted_at DATETIME(6) NOT NULL,
  note VARCHAR(255) NULL,
  FOREIGN KEY (inventory_id) REFERENCES inventory(inventory_id),
  FOREIGN KEY (counted_by) REFERENCES staff_account(staff_id),
  CONSTRAINT uq_inventory_count_request UNIQUE (request_id, inventory_id),
  CHECK (counted_qty >= 0 AND book_qty >= 0),
  CHECK (diff_qty = counted_qty - book_qty),
  CHECK (unit_cost IS NULL OR unit_cost >= 0),
  INDEX idx_inventory_count_time (counted_at)
) ENGINE=InnoDB;

ALTER TABLE inventory ADD COLUMN allow_decimal BOOLEAN NOT NULL DEFAULT TRUE;
