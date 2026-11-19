-- 040：每種加料的份數上限記在資料庫；一杯飲料的加料最多 5 份
-- 僅由資料庫負責人確認後執行；需要先執行 039。這版後端需要 040（菜單、送單會讀 max_qty），請先執行再部署。
--
-- topping.max_qty：這種加料一杯最多加幾份，1～5，預設 2（跟 039 的「同一種最多兩份」一樣）。
-- 一杯全部加料加起來最多 5 份是寫在程式裡的固定規則（後端 OrderRequests.MAX_TOPPINGS_PER_CUP、前端 models/pos.ts）。

ALTER TABLE topping
  ADD COLUMN max_qty INT NOT NULL DEFAULT 2 AFTER price,
  ADD CONSTRAINT chk_topping_max_qty CHECK (max_qty BETWEEN 1 AND 5);

-- 設定範例：芝芝類一杯只能加一份、波霸最多三份
-- UPDATE topping SET max_qty = 1 WHERE name IN ('金絨芝芝', '芝芝', '開心果芝芝') AND topping_id > 0;
-- UPDATE topping SET max_qty = 3 WHERE name = '波霸' AND topping_id > 0;

-- 注意：conflict_rule 裡如果還有「額外加料最多三種」（MAX_COUNT、maxDistinct = 3），它仍然有效，
-- 會再限制「種類」最多 3 種。不需要的話用下面這行刪掉（先用 SELECT 確認是哪一條）：
-- SELECT rule_id, message FROM conflict_rule WHERE rule_type = 'MAX_COUNT';
-- DELETE FROM conflict_rule WHERE rule_type = 'MAX_COUNT' AND rule_id = （上面查到的編號）;

-- 核對：
-- SELECT topping_id, name, price, max_qty FROM topping ORDER BY topping_id;
