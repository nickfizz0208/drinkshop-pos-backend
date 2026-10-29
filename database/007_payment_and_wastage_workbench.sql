-- 僅供新建本機庫或經團隊同意的舊版庫升級；共用 Aiven 已升級，不要執行。
-- 先備份、停止所有後端，需要 CREATE/ALTER ROUTINE 權限。
-- MySQL DDL 不能整批回滾，中途失敗須檢查／恢復備份，不可忽略錯誤。
USE drinkshop_pos;
-- 所有業務變更放在同一次 CALL；客戶端忽略錯誤也不會繼續執行外部 UPDATE。
DROP PROCEDURE IF EXISTS pos_upgrade_007_once;
DELIMITER $$
CREATE PROCEDURE pos_upgrade_007_once()
upgrade_007: BEGIN
  DECLARE new_columns INT DEFAULT 0;
  DECLARE new_checks INT DEFAULT 0;
  DECLARE prerequisites INT DEFAULT 0;
  DECLARE old_checks INT DEFAULT 0;
  DECLARE old_cash_check VARCHAR(64);
  DECLARE previous_safe_updates INT DEFAULT @@SESSION.SQL_SAFE_UPDATES;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    SET SESSION SQL_SAFE_UPDATES = previous_safe_updates;
    RESIGNAL;
  END;

  IF DATABASE() <> 'drinkshop_pos' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Wrong database: stopped before business changes.';
  END IF;
  SELECT COUNT(*) INTO new_columns FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND
    ((TABLE_NAME = 'pos_order' AND COLUMN_NAME IN ('payment_status','paid_amount','refunded_amount'))
     OR (TABLE_NAME = 'order_item' AND COLUMN_NAME IN ('material_cost','refund_amount')));
  SELECT COUNT(*) INTO new_checks FROM information_schema.TABLE_CONSTRAINTS
  WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_TYPE = 'CHECK' AND
    ((TABLE_NAME = 'pos_order' AND CONSTRAINT_NAME = 'chk_order_collection')
     OR (TABLE_NAME = 'order_item' AND CONSTRAINT_NAME = 'chk_item_cost_refund'));
  IF new_columns = 5 AND new_checks = 2 THEN
    SELECT '007 already applied: skipped; no business data changed.' AS result;
    LEAVE upgrade_007;
  END IF;
  IF new_columns <> 0 OR new_checks <> 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Partial 007 schema: stopped before changes; inspect backup and schema.';
  END IF;
  SELECT COUNT(*) INTO prerequisites FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND
    ((TABLE_NAME = 'inventory' AND COLUMN_NAME = 'unit_cost')
     OR (TABLE_NAME = 'order_item' AND COLUMN_NAME = 'deducted_inventory_json')
     OR (TABLE_NAME = 'pos_order' AND COLUMN_NAME = 'request_hash')
     OR (TABLE_NAME = 'order_item_topping' AND COLUMN_NAME = 'order_topping_id'));
  IF prerequisites <> 4 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Prerequisite migrations 004/005/006 missing: stopped before changes.';
  END IF;
  SELECT COUNT(*), MIN(c.CONSTRAINT_NAME) INTO old_checks, old_cash_check
  FROM information_schema.CHECK_CONSTRAINTS c
  JOIN information_schema.TABLE_CONSTRAINTS t
    ON t.CONSTRAINT_SCHEMA = c.CONSTRAINT_SCHEMA AND t.CONSTRAINT_NAME = c.CONSTRAINT_NAME
  WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_NAME = 'pos_order'
    AND c.CHECK_CLAUSE LIKE '%received_cash%';
  IF old_checks <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Unexpected original cash constraint: stopped before changes.';
  END IF;
  SET @pos_007_drop_sql = CONCAT('ALTER TABLE pos_order DROP CHECK `', REPLACE(old_cash_check,'`','``'), '`');
  PREPARE pos_007_stmt FROM @pos_007_drop_sql;
  EXECUTE pos_007_stmt;
  DEALLOCATE PREPARE pos_007_stmt;
  SET @pos_007_drop_sql = NULL;

ALTER TABLE pos_order
  MODIFY payment ENUM('CASH','CREDIT_CARD','LINE_PAY','UBER_EATS') NULL,
  ADD payment_status ENUM('UNPAID','PAID') NOT NULL DEFAULT 'PAID',
  ADD paid_amount DECIMAL(10,2) NOT NULL DEFAULT 0,
  ADD refunded_amount DECIMAL(10,2) NOT NULL DEFAULT 0;
ALTER TABLE order_item
  ADD material_cost DECIMAL(10,2) NULL COMMENT 'Whole line incl toppings; creation-time estimate; NULL means unknown',
  ADD refund_amount DECIMAL(10,2) NOT NULL DEFAULT 0;
-- 僅首次升級舊資料使用，不能用來重算新系統的部分退款或未付款訂單。
SET SESSION SQL_SAFE_UPDATES = 0;
START TRANSACTION;
UPDATE pos_order o SET paid_amount = GREATEST(total,
  COALESCE((SELECT SUM(qty*unit_price) FROM order_item i WHERE i.order_id=o.order_id),0)+delivery_fee);
UPDATE pos_order SET refunded_amount=paid_amount-total;
UPDATE order_item SET refund_amount=qty*unit_price WHERE status IN ('CANCELLED','WASTED');
COMMIT;
-- Historical ingredient costs stay NULL: today's costs cannot prove past costs.
ALTER TABLE pos_order ADD CONSTRAINT chk_order_collection CHECK (
  paid_amount>=0 AND refunded_amount>=0 AND refunded_amount<=paid_amount AND
  ((payment_status='UNPAID' AND payment IS NULL AND paid_amount=0 AND refunded_amount=0
      AND received_cash IS NULL AND change_cash IS NULL)
   OR (payment_status='PAID' AND payment IS NOT NULL AND
      ((payment='CASH' AND received_cash IS NOT NULL AND received_cash>=paid_amount
        AND change_cash IS NOT NULL AND change_cash=received_cash-paid_amount+refunded_amount)
       OR (payment<>'CASH' AND received_cash IS NULL AND change_cash IS NULL)))));
ALTER TABLE order_item ADD CONSTRAINT chk_item_cost_refund CHECK (
  (material_cost IS NULL OR material_cost>=0) AND refund_amount>=0 AND refund_amount<=qty*unit_price);

  SET SESSION SQL_SAFE_UPDATES = previous_safe_updates;
  SELECT '007 applied successfully.' AS result;
END$$
DELIMITER ;
CALL pos_upgrade_007_once();
DROP PROCEDURE IF EXISTS pos_upgrade_007_once;
