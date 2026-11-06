-- 排班標籤：取代「班別」。標籤不帶時間，每段班的時間各自填；一段班可以貼多個標籤。
-- 預設四個：開班、支援、收班、休息（這段班可以中間休息半小時）。店長可以在排班頁新增、改名、停用（不刪除）。
-- staff_schedule／staff_schedule_published 的 tag_ids 存標籤編號，以逗號分隔，例如 "1,4"；發布時跟著複製。
-- 舊的 shift_template 與 template_id 保留不動（舊資料不受影響），前端已不再使用。
-- 在 Workbench 執行一次即可；不可重複執行。
USE drinkshop_pos;

CREATE TABLE schedule_tag (
  tag_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(20) NOT NULL,
  description VARCHAR(100) NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE
);

ALTER TABLE staff_schedule ADD COLUMN tag_ids VARCHAR(200) NULL;
ALTER TABLE staff_schedule_published ADD COLUMN tag_ids VARCHAR(200) NULL;

INSERT INTO schedule_tag (name, description) VALUES
  ('開班', NULL),
  ('支援', NULL),
  ('收班', NULL),
  ('休息', '這段班可以中間休息半小時');

SELECT * FROM schedule_tag;
