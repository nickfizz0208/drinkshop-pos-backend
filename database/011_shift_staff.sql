-- 員工上下班打卡。一個人一天可以打卡多次，每次上班到下班是一筆；clock_out_at 為 NULL 代表目前在班。
-- 打卡選名字即可（不需 PIN）；修改、刪除紀錄限店長以上，updated_by／updated_at 記錄最後修改的店長。
-- 在 Workbench 執行一次即可；不可重複執行。
CREATE TABLE shift_staff (
  shift_staff_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  business_date DATE NOT NULL,
  staff_id BIGINT NOT NULL,
  clock_in_at DATETIME(6) NOT NULL,
  clock_out_at DATETIME(6) NULL,
  updated_by BIGINT NULL,
  updated_at DATETIME(6) NULL,
  FOREIGN KEY (business_date) REFERENCES daily_shift(business_date),
  FOREIGN KEY (staff_id) REFERENCES staff_account(staff_id),
  FOREIGN KEY (updated_by) REFERENCES staff_account(staff_id),
  CHECK (clock_out_at IS NULL OR clock_out_at >= clock_in_at)
);
CREATE INDEX idx_shift_staff_on_duty ON shift_staff(business_date, staff_id, clock_out_at);
