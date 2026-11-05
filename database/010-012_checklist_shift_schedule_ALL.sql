-- =====================================================================
-- 檢查表、打卡、排班：一次建立 6 張新表（合併 010、011、012）
-- 已經各別執行過 010／011／012 的資料庫不需要再執行這支。
-- 編號說明：009 已被組員的 009_raw_material_wastage.sql 使用；這支跟 009 沒有相依，執行順序不影響。
-- 需求：MySQL 8.0.16 以上（CHECK 條件才會生效）
-- 可重複執行：表已存在會略過，不會清掉資料。
-- 執行前請確認資料庫名稱；雲端資料庫名稱不同時，請修改下一行。
-- =====================================================================
USE drinkshop_pos;
SET time_zone = '+08:00';

-- ---------- 檢查表 ----------
-- checklist_item = 店長設定的工作項目；checklist_record = 每次打勾的完成紀錄。
-- period：DAILY 存日期（2026-11-05），WEEKLY 存 ISO 週次（2026-W45）。
CREATE TABLE IF NOT EXISTS checklist_item (
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
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS checklist_record (
  record_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  item_id BIGINT NOT NULL,
  staff_id BIGINT NOT NULL,
  period VARCHAR(10) NOT NULL,
  done_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  FOREIGN KEY (item_id) REFERENCES checklist_item(item_id),
  FOREIGN KEY (staff_id) REFERENCES staff_account(staff_id),
  INDEX idx_checklist_record_period (item_id, period)
) ENGINE=InnoDB;

-- ---------- 打卡 ----------
-- 一個人一天可以打卡多次；clock_out_at 為 NULL 代表目前在班。
-- 修改、刪除限店長以上，updated_by／updated_at 記錄最後修改的店長。
CREATE TABLE IF NOT EXISTS shift_staff (
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
  INDEX idx_shift_staff_on_duty (business_date, staff_id, clock_out_at),
  CHECK (clock_out_at IS NULL OR clock_out_at >= clock_in_at)
) ENGINE=InnoDB;

-- ---------- 排班 ----------
-- shift_template = 固定班別；staff_schedule = 某天某人的班（時間從班別複製，也可手動填）；
-- schedule_week = 已發布的週（week_start 為週一）。
CREATE TABLE IF NOT EXISTS shift_template (
  template_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(20) NOT NULL,
  start_time TIME NOT NULL,
  end_time TIME NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  CHECK (end_time > start_time)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS staff_schedule (
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
  INDEX idx_staff_schedule_date (work_date, staff_id),
  CHECK (end_time > start_time)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS schedule_week (
  week_start DATE PRIMARY KEY,
  published_by BIGINT NOT NULL,
  published_at DATETIME(6) NOT NULL,
  modified_after_publish BOOLEAN NOT NULL DEFAULT FALSE,
  FOREIGN KEY (published_by) REFERENCES staff_account(staff_id)
) ENGINE=InnoDB;

-- ---------- 檢查：應該列出 6 張表 ----------
SELECT table_name FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('checklist_item','checklist_record','shift_staff','shift_template','staff_schedule','schedule_week');
