-- 051：半成品報廢紀錄，並把舊的「半成品備料製作登記」資料搬到備料紀錄
-- 僅由資料庫負責人確認後執行；需要先執行 050（prep_batch）。可以重複執行。
-- 這版後端需要 051（備料頁會讀寫 prep_waste），請先執行再部署。
--
-- prep_waste：剩下的半成品倒掉（例如茶湯放太久）。只做紀錄，不扣原料（原料在登記備料時已經算成消耗）。
--   wasted_by：處理的人（在職員工）；recorded_by：登記的帳號（POS 機登記時是 POS 帳號）；note：原因。
--
-- 舊資料：組員版本的後端會在啟動時自己建 prep_production_log（製作）、prep_wastage_log（報廢）。
-- 如果共用庫裡有這兩張表，這支會把資料分別搬進 prep_batch、prep_waste，然後把舊表改名成 *_old 保留（不刪除）；
-- 核對無誤後可以手動 DROP。舊資料只記了一個 staff_id，搬過來時「誰煮的」和「登記帳號」都用它。

CREATE TABLE IF NOT EXISTS prep_waste (
  waste_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  business_date DATE NOT NULL,
  prep_id BIGINT NOT NULL,
  qty DECIMAL(10,2) NOT NULL,
  wasted_by BIGINT NOT NULL,
  recorded_by BIGINT NOT NULL,
  wasted_at DATETIME(6) NOT NULL,
  note VARCHAR(200) NOT NULL,
  CONSTRAINT fk_prep_waste_prep FOREIGN KEY (prep_id) REFERENCES prep_item(prep_id),
  CONSTRAINT fk_prep_waste_wasted_by FOREIGN KEY (wasted_by) REFERENCES staff_account(staff_id),
  CONSTRAINT fk_prep_waste_recorded_by FOREIGN KEY (recorded_by) REFERENCES staff_account(staff_id),
  CONSTRAINT chk_prep_waste_qty CHECK (qty > 0),
  INDEX idx_prep_waste_date (business_date, wasted_at)
) ENGINE=InnoDB;

DROP PROCEDURE IF EXISTS move_old_prep_logs;
DELIMITER $$
CREATE PROCEDURE move_old_prep_logs()
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prep_production_log') THEN
    INSERT INTO prep_batch (business_date, prep_id, qty, made_by, recorded_by, made_at, note)
    SELECT business_date, prep_id, produced_qty, staff_id, staff_id, created_at, LEFT(note, 200)
      FROM prep_production_log WHERE produced_qty > 0;
    RENAME TABLE prep_production_log TO prep_production_log_old;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prep_wastage_log') THEN
    INSERT INTO prep_waste (business_date, prep_id, qty, wasted_by, recorded_by, wasted_at, note)
    SELECT business_date, prep_id, wasted_qty, staff_id, staff_id, created_at, COALESCE(NULLIF(LEFT(note, 200), ''), '（舊資料沒有填原因）')
      FROM prep_wastage_log WHERE wasted_qty > 0;
    RENAME TABLE prep_wastage_log TO prep_wastage_log_old;
  END IF;
END$$
DELIMITER ;
CALL move_old_prep_logs();
DROP PROCEDURE move_old_prep_logs;

-- 核對：
-- SELECT COUNT(*) FROM prep_batch;  SELECT COUNT(*) FROM prep_waste;
-- SHOW TABLES LIKE 'prep_%_old';
