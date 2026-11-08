-- 僅由資料庫負責人確認後升級；本次未在 Aiven 執行。不要忽略錯誤重跑。
-- 原料損耗與訂單棄單分開：每次事件保存當時數量、成本、原因與操作者。
CREATE TABLE raw_material_wastage (
  wastage_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  business_date DATE NOT NULL,
  inventory_id BIGINT NOT NULL,
  quantity DECIMAL(12,2) NOT NULL,
  unit_cost DECIMAL(10,2) NULL,
  material_cost DECIMAL(10,2) NULL,
  reason VARCHAR(255) NOT NULL,
  reported_by BIGINT NOT NULL,
  reported_at DATETIME(6) NOT NULL,
  FOREIGN KEY (business_date) REFERENCES daily_shift(business_date),
  FOREIGN KEY (inventory_id) REFERENCES inventory(inventory_id),
  FOREIGN KEY (reported_by) REFERENCES staff_account(staff_id),
  CHECK (quantity > 0),
  CHECK ((unit_cost IS NULL AND material_cost IS NULL) OR
         (unit_cost >= 0 AND material_cost >= 0 AND unit_cost IS NOT NULL AND material_cost IS NOT NULL)),
  INDEX idx_raw_wastage_date (business_date)
);
