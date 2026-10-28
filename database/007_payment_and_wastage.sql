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
