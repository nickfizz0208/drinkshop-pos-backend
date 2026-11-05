-- 排班。shift_template = 固定班別；staff_schedule = 某天某人的班（時間從班別複製，也可手動填，不跟班別連動）；
-- schedule_week = 已發布的週（week_start 為週一）。沒有發布紀錄的週，一般員工看不到；發布後修改會標記 modified_after_publish。
-- 在 Workbench 執行一次即可；不可重複執行。
CREATE TABLE shift_template (
  template_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(20) NOT NULL,
  start_time TIME NOT NULL,
  end_time TIME NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  CHECK (end_time > start_time)
);

CREATE TABLE staff_schedule (
  schedule_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  work_date DATE NOT NULL,
  staff_id BIGINT NOT NULL,
  template_id BIGINT NULL,
  start_time TIME NOT NULL,
  end_time TIME NOT NULL,
  note VARCHAR(100) NULL,
  created_by BIGINT NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  FOREIGN KEY (staff_id) REFERENCES staff_account(staff_id),
  FOREIGN KEY (template_id) REFERENCES shift_template(template_id),
  FOREIGN KEY (created_by) REFERENCES staff_account(staff_id),
  CHECK (end_time > start_time)
);

CREATE TABLE schedule_week (
  week_start DATE PRIMARY KEY,
  published_by BIGINT NOT NULL,
  published_at DATETIME(6) NOT NULL,
  modified_after_publish BOOLEAN NOT NULL DEFAULT FALSE,
  FOREIGN KEY (published_by) REFERENCES staff_account(staff_id)
);
CREATE INDEX idx_staff_schedule_date ON staff_schedule(work_date, staff_id);
