-- 排班「已發布版本」：員工只看這張表。店長按發布時，把該週草稿（staff_schedule）整週複製過來覆蓋。
-- schedule_week.modified_after_publish 的意思改為「有尚未發布的修改」（欄位不變）。
-- 在 Workbench 執行一次即可；需先執行 010-012。
USE drinkshop_pos;
CREATE TABLE staff_schedule_published (
  published_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  work_date DATE NOT NULL,
  staff_id BIGINT NOT NULL,
  template_id BIGINT NULL,
  start_time TIME NOT NULL,
  end_time TIME NOT NULL,
  note VARCHAR(100) NULL,
  FOREIGN KEY (staff_id) REFERENCES staff_account(staff_id),
  FOREIGN KEY (template_id) REFERENCES shift_template(template_id),
  CHECK (end_time > start_time)
);
CREATE INDEX idx_schedule_published_date ON staff_schedule_published(work_date);
-- 已經發布過的週：把目前的排班當成已發布版本，避免員工看到空白。
INSERT INTO staff_schedule_published (work_date, staff_id, template_id, start_time, end_time, note)
SELECT s.work_date, s.staff_id, s.template_id, s.start_time, s.end_time, s.note FROM staff_schedule s
JOIN schedule_week w ON s.work_date BETWEEN w.week_start AND DATE_ADD(w.week_start, INTERVAL 6 DAY);
-- Workbench 安全更新模式會擋下沒有主鍵條件的 UPDATE，暫時關閉後再開回來。
SET SQL_SAFE_UPDATES = 0;
UPDATE schedule_week SET modified_after_publish = FALSE;
SET SQL_SAFE_UPDATES = 1;
