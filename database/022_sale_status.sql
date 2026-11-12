-- 022：停售管理——原料、半成品也可以停售，用到它的飲品與加料會一起停售
-- 僅由資料庫負責人確認後執行；需要先執行 021。這一版後端需要 022，請先執行 022 再部署。
--
-- 改動重點：
--   1. inventory、prep_item 加上 on_sale（預設 TRUE＝販售中）。
--   2. 飲品、加料的 on_sale 維持「自己有沒有停售」，不會被原料改寫。
--      實際能不能賣由後端判斷：自己販售中，而且材料清單（product_recipe）與加料指向的原料、半成品都沒有停售；
--      半成品用到的原料（prep_item_ingredient）停售，也算這個半成品停售。
--   3. 原料恢復販售時，因它停售的飲品、加料自動恢復；自己被停售的不受影響。
--   紙杯不在材料清單裡，停售紙杯不會連動任何飲品。

ALTER TABLE inventory ADD COLUMN on_sale BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE prep_item ADD COLUMN on_sale BOOLEAN NOT NULL DEFAULT TRUE;

-- 核對：目前因原料或半成品停售而不能賣的飲品
-- SELECT p.name, COALESCE(i.name, x.name) AS 停售材料 FROM product_recipe r
--   JOIN product p ON p.product_id = r.product_id
--   LEFT JOIN inventory i ON i.inventory_id = r.inventory_id
--   LEFT JOIN prep_item x ON x.prep_id = r.prep_id
--  WHERE i.on_sale = FALSE OR x.on_sale = FALSE;
