-- 039：同一種加料可以加兩份
-- 僅由資料庫負責人確認後執行；需要先執行 038。這版後端需要 039（送單會寫 qty），請先執行再部署。
--
-- order_item_topping 多一欄 qty（份數），舊資料都是 1 份。同一杯同一種加料還是只有一筆（唯一限制不變），份數記在 qty。
-- price_at_order 是一份的價格；這杯的加料金額＝price_at_order × qty，已經算在 order_item.unit_price 裡。
-- 一杯最多幾份由後端限制（目前 2 份，OrderRequests.MAX_SAME_TOPPING），資料庫只檢查至少 1 份。

ALTER TABLE order_item_topping
  ADD COLUMN qty INT NOT NULL DEFAULT 1 AFTER topping_name,
  ADD CONSTRAINT chk_order_item_topping_qty CHECK (qty >= 1);

-- 核對：
-- SELECT qty, COUNT(*) FROM order_item_topping GROUP BY qty;
