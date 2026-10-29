-- 007 的 Workbench 版本：給 Aiven 等「不能用 scripts/migrate_payment.py」的資料庫使用。
-- 內容 = migrate_payment.py 會先做的「刪除舊現金約束」+ 原本的 007_payment_and_wastage.sql。
-- 只在 001、002、004、005、006 都跑完之後執行一次，執行前先停止後端 API。
USE drinkshop_pos;
SET SQL_SAFE_UPDATES = 0;

-- 找出 pos_order 上檢查 received_cash 的舊約束並刪除（名稱是自動產生的，所以用內容去找）
SET @cash_check := (
  SELECT c.CONSTRAINT_NAME
  FROM information_schema.CHECK_CONSTRAINTS c
  JOIN information_schema.TABLE_CONSTRAINTS t
    ON t.CONSTRAINT_SCHEMA = c.CONSTRAINT_SCHEMA AND t.CONSTRAINT_NAME = c.CONSTRAINT_NAME
  WHERE t.TABLE_SCHEMA = 'drinkshop_pos' AND t.TABLE_NAME = 'pos_order'
    AND c.CHECK_CLAUSE LIKE '%received_cash%'
    AND c.CONSTRAINT_NAME <> 'chk_order_collection'
    AND NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = 'drinkshop_pos' AND TABLE_NAME = 'pos_order'
                      AND COLUMN_NAME = 'payment_status')
  LIMIT 1);
SET @drop_sql := IFNULL(CONCAT('ALTER TABLE pos_order DROP CHECK `', @cash_check, '`'),
                        'SELECT ''已經套用過 007，不用再跑。接下來的錯誤訊息可忽略'' AS warning');
PREPARE stmt FROM @drop_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ===== 以下為原本的 007_payment_and_wastage.sql =====
-- Run with scripts/migrate_payment.py while the API is stopped.
-- total remains the value of live items. Net receipts = paid_amount - refunded_amount.
ALTER TABLE pos_order
  MODIFY payment ENUM('CASH','CREDIT_CARD','LINE_PAY','UBER_EATS') NULL,
  ADD payment_status ENUM('UNPAID','PAID') NOT NULL DEFAULT 'PAID',
  ADD paid_amount DECIMAL(10,2) NOT NULL DEFAULT 0,
  ADD refunded_amount DECIMAL(10,2) NOT NULL DEFAULT 0;
ALTER TABLE order_item
  ADD material_cost DECIMAL(10,2) NULL COMMENT 'Whole line incl toppings; creation-time estimate; NULL means unknown',
  ADD refund_amount DECIMAL(10,2) NOT NULL DEFAULT 0;
-- Legacy API always collected on creation and refunded every cancelled line.
UPDATE pos_order o SET paid_amount = GREATEST(total,
  COALESCE((SELECT SUM(qty*unit_price) FROM order_item i WHERE i.order_id=o.order_id),0)+delivery_fee);
UPDATE pos_order SET refunded_amount=paid_amount-total;
UPDATE order_item SET refund_amount=qty*unit_price WHERE status IN ('CANCELLED','WASTED');
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

SET SQL_SAFE_UPDATES = 1;
