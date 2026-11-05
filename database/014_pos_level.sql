-- 新增 POS 職級：門市 POS 機的共用帳號（不是真人）。權限只比 STAFF 高。
-- POS 帳號在檢查表可以替「在班的人」打勾；個人帳號只能記自己。員工清單（/api/staff）不會列出 POS 帳號。
-- 只是在 ENUM 中間插入新值，既有資料不受影響。在 Workbench 執行一次即可。
USE drinkshop_pos;
ALTER TABLE staff_account
  MODIFY level ENUM('TRAINEE','STAFF','POS','MANAGER','HQ') NOT NULL;
ALTER TABLE product
  MODIFY min_view_level ENUM('TRAINEE','STAFF','POS','MANAGER','HQ') NOT NULL DEFAULT 'TRAINEE';
