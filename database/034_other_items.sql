-- 034：新增「其他」分類的商品——購物袋、保冷袋（單）、保冷袋（雙）
-- 僅由資料庫負責人確認後執行；需要先執行 032（用到 color_tone）。可以重複執行：同名的商品已經存在就略過。
--
-- 分類不是一張表，就是 product.category 的文字：有商品用到的分類才會出現在點餐頁的分類按鈕。
-- 這次會出現「其他」；「特調」「芝芝」要等之後新增了那兩類的飲品才會出現（例：INSERT ... category = '特調'）。
--
-- 這些不是飲料，只有一種價格：先記在 price_m（中杯）欄，大杯、瓶留空白（不賣）。
-- hot_allowed = FALSE、color_tone = 'WHITE'（白色卡片，排在飲品列表最後）。
-- 注意：目前點餐頁點了還是會打開飲品客製（杯型、溫度、甜度、冰量），前端要另外處理「其他」分類。

INSERT INTO product (name, category, price_m, price_l, price_bottle, hot_allowed, color_tone, recipe_steps)
SELECT v.name, '其他', v.price, NULL, NULL, FALSE, 'WHITE', ''
FROM (
            SELECT '購物袋'       AS name, 1  AS price, 1 AS ord
  UNION ALL SELECT '保冷袋（單）', 5,  2
  UNION ALL SELECT '保冷袋（雙）', 10, 3
) v
WHERE NOT EXISTS (SELECT 1 FROM product p WHERE p.name = v.name)
ORDER BY v.ord;

-- 核對：
-- SELECT product_id, name, category, price_m AS 價格, color_tone FROM product WHERE category = '其他' ORDER BY product_id;
