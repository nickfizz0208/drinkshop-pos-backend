-- 清潔／例行工作檢查表。checklist_item = 店長設定的工作項目；checklist_record = 每次打勾的完成紀錄。
-- period：DAILY 存日期（2026-11-05），WEEKLY 存 ISO 週次（2026-W45），用來判斷「這一期」完成幾次。
-- 在 Workbench 執行一次即可；不可重複執行。
CREATE TABLE checklist_item (
  item_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(50) NOT NULL,
  frequency ENUM('DAILY','WEEKLY') NOT NULL,
  target_count INT NOT NULL DEFAULT 1,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_by BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  FOREIGN KEY (created_by) REFERENCES staff_account(staff_id),
  CHECK (target_count >= 1)
);

CREATE TABLE checklist_record (
  record_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  item_id BIGINT NOT NULL,
  staff_id BIGINT NOT NULL,
  period VARCHAR(10) NOT NULL,
  done_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  FOREIGN KEY (item_id) REFERENCES checklist_item(item_id),
  FOREIGN KEY (staff_id) REFERENCES staff_account(staff_id),
  INDEX idx_checklist_record_period (item_id, period)
);
