-- 019：原料可以用「大單位＋小單位」顯示（例如芒果：5 籃 3 顆），並加入芒果假資料
-- 僅由資料庫負責人確認後執行；不要忽略錯誤重跑。需要先執行 016–018。
--
-- display_by_pack = TRUE 的原料：資料庫照樣存基本單位的數量（per_pack 換算後），
-- 畫面上的帳面、實點、差異、報廢數量改成「幾籃幾顆」顯示。
-- 盤點時零散數量要少於 1 個包裝單位（per_pack），不然「1 籃 33 顆」和「2 籃 3 顆」會分不出來。
-- per_pack 建議填實際的平均數量（例如一籃平均 30 顆），原料成本、差異金額才算得準。

ALTER TABLE inventory ADD COLUMN display_by_pack BOOLEAN NOT NULL DEFAULT FALSE;

-- 假資料：芒果。1 籃 ≒ 30 顆（平均值）；帳面 5 籃 3 顆（153 顆）、安全庫存 2 籃（60 顆）、每顆 15 元；只能填整數顆
-- 已經有叫「芒果」的原料就不新增
INSERT INTO inventory (name, base_unit, pack_unit, per_pack, book_qty, safety_stock, unit_cost, allow_decimal, display_by_pack)
SELECT '芒果', '顆', '籃', 30, 153, 60, 15.00, FALSE, TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM inventory WHERE name = '芒果');

-- 核對：
-- SELECT inventory_id, name, base_unit, pack_unit, per_pack, book_qty, display_by_pack FROM inventory WHERE name = '芒果';
