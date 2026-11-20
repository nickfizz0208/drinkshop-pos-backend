-- 043：半成品可以用另一個半成品當材料，並記錄用量
-- 僅由資料庫負責人確認後執行；需要先執行 028（停售）。這支只能執行一次（第 2 步會刪掉舊的主鍵）。
-- 這版後端需要 043（停售、消耗、配方會讀 sub_prep_id、qty），請先執行再部署。
--
-- 例如雙Q果＝熟波霸（半成品，由生波霸做成）＋椰果（原料）。
-- prep_item_ingredient 一列是「這個半成品用到的一項材料」：
--   inventory_id：用到的原料；sub_prep_id：用到的另一個半成品。兩個只能填一個。
--   qty：做一份這個半成品要用多少——原料用它的基本單位（base_unit），半成品用「份」。舊資料都是 1。
-- 停售：每項材料停售時連不連動由 044 的 link 決定（預設連動）。
-- 消耗報表：半成品一層層展開成原料，最多往下追 3 層（A 用 B、B 用 C、C 用 D 的 D 是第 3 層），更深的不算；
-- 繞圈（A 用 B、B 又用 A）不會出錯，只是沒有意義，請不要這樣建。

-- 1. 先補一個 (prep_id, inventory_id) 的唯一索引：舊主鍵刪掉後，prep_id 的外鍵還要有索引可用
ALTER TABLE prep_item_ingredient
  ADD UNIQUE KEY uq_prep_ingredient_inventory (prep_id, inventory_id);

-- 2. 主鍵改成流水號；inventory_id 可以是 NULL；加上 sub_prep_id、qty
ALTER TABLE prep_item_ingredient
  DROP PRIMARY KEY,
  ADD COLUMN line_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST,
  MODIFY COLUMN inventory_id BIGINT NULL,
  ADD COLUMN sub_prep_id BIGINT NULL AFTER inventory_id,
  ADD COLUMN qty DECIMAL(10,2) NOT NULL DEFAULT 1 AFTER sub_prep_id,
  ADD CONSTRAINT fk_prep_ingredient_sub_prep FOREIGN KEY (sub_prep_id) REFERENCES prep_item(prep_id),
  ADD UNIQUE KEY uq_prep_ingredient_sub_prep (prep_id, sub_prep_id),
  ADD CONSTRAINT chk_prep_ingredient_one CHECK ((inventory_id IS NULL) <> (sub_prep_id IS NULL)),
  ADD CONSTRAINT chk_prep_ingredient_not_self CHECK (sub_prep_id IS NULL OR sub_prep_id <> prep_id),
  ADD CONSTRAINT chk_prep_ingredient_qty CHECK (qty > 0);

-- 核對：舊資料都是原料、qty = 1
-- SELECT g.line_id, x.name AS 半成品, i.name AS 原料, s.name AS 子半成品, g.qty
--   FROM prep_item_ingredient g JOIN prep_item x ON x.prep_id = g.prep_id
--   LEFT JOIN inventory i ON i.inventory_id = g.inventory_id LEFT JOIN prep_item s ON s.prep_id = g.sub_prep_id
--  ORDER BY g.prep_id, g.line_id;

-- 範例：雙Q果＝熟波霸 0.5 份＋椰果 0.5 份（名稱、用量請照實際情況改；椰果的用量是椰果原料的基本單位）
-- INSERT INTO prep_item (name, steps, category) VALUES ('雙Q果', '熟波霸、椰果各半混合', '底料');
-- SET @sq = (SELECT prep_id FROM prep_item WHERE name = '雙Q果');
-- INSERT INTO prep_item_ingredient (prep_id, sub_prep_id, qty) SELECT @sq, prep_id, 0.5 FROM prep_item WHERE name = '熟波霸';
-- INSERT INTO prep_item_ingredient (prep_id, inventory_id, qty) SELECT @sq, inventory_id, 0.5 FROM inventory WHERE name = '椰果';
-- UPDATE topping SET prep_id = @sq, inventory_id = NULL WHERE name = '雙Q果' AND topping_id > 0;
