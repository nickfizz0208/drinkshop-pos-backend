-- 050：備料紀錄（誰煮了什麼半成品、幾份）
-- 僅由資料庫負責人確認後執行；需要先執行 021（半成品 prep_item）。可以重複執行（表已經存在就略過）。
-- 這版後端需要 050（備料頁會讀寫 prep_batch），請先執行再部署。
--
-- 只做紀錄：不扣原料帳面、不改半成品數量。
-- made_by：煮的人（在職員工，不能是 POS 共用帳號）；recorded_by：登記的帳號（POS 機登記時是 POS 帳號，個人帳號登記時和 made_by 相同）。

CREATE TABLE IF NOT EXISTS prep_batch (
  batch_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  business_date DATE NOT NULL,
  prep_id BIGINT NOT NULL,
  qty DECIMAL(10,2) NOT NULL,
  made_by BIGINT NOT NULL,
  recorded_by BIGINT NOT NULL,
  made_at DATETIME(6) NOT NULL,
  note VARCHAR(200) NULL,
  CONSTRAINT fk_prep_batch_prep FOREIGN KEY (prep_id) REFERENCES prep_item(prep_id),
  CONSTRAINT fk_prep_batch_made_by FOREIGN KEY (made_by) REFERENCES staff_account(staff_id),
  CONSTRAINT fk_prep_batch_recorded_by FOREIGN KEY (recorded_by) REFERENCES staff_account(staff_id),
  CONSTRAINT chk_prep_batch_qty CHECK (qty > 0),
  INDEX idx_prep_batch_date (business_date, made_at)
) ENGINE=InnoDB;

-- 核對：
-- SELECT b.made_at, x.name AS 半成品, b.qty AS 份數, m.name AS 誰煮的, r.name AS 登記帳號, b.note
--   FROM prep_batch b JOIN prep_item x ON x.prep_id = b.prep_id
--   JOIN staff_account m ON m.staff_id = b.made_by JOIN staff_account r ON r.staff_id = b.recorded_by
--  ORDER BY b.made_at DESC;
