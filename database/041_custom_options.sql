-- 041：不用錢的客製選項（加酸、少料、加梅子、不加梅子）
-- 僅由資料庫負責人確認後執行；需要先執行 040。這版後端需要 041（菜單、送單會讀這兩張表），請先執行再部署。
-- 可以重複執行：表已經存在就不建，同名的選項已經存在就略過。
--
-- custom_option：客製選項。max_qty＝一杯最多選幾份（預設 1），sort_order＝按鈕順序，active＝FALSE 就不顯示、不能選。
-- order_item_option：每杯選了哪些客製選項，存當時的名稱與份數。
-- 客製選項不用錢、沒有衝突規則（例如「加梅子」「不加梅子」可以同時選），也不扣原料。

CREATE TABLE IF NOT EXISTS custom_option (
  option_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(30) NOT NULL UNIQUE,
  max_qty INT NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT chk_custom_option_max_qty CHECK (max_qty BETWEEN 1 AND 5)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS order_item_option (
  order_option_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  item_id BIGINT NOT NULL,
  option_id BIGINT NOT NULL,
  option_name VARCHAR(30) NOT NULL COMMENT '下單當時的名稱',
  qty INT NOT NULL DEFAULT 1,
  CONSTRAINT uq_order_item_option UNIQUE (item_id, option_id),
  CONSTRAINT fk_order_item_option_item FOREIGN KEY (item_id) REFERENCES order_item(item_id),
  CONSTRAINT fk_order_item_option_option FOREIGN KEY (option_id) REFERENCES custom_option(option_id),
  CONSTRAINT chk_order_item_option_qty CHECK (qty >= 1)
) ENGINE=InnoDB;

INSERT INTO custom_option (name, max_qty, sort_order)
SELECT v.name, 1, v.ord
FROM (
            SELECT '加酸'     AS name, 1 AS ord
  UNION ALL SELECT '少料',     2
  UNION ALL SELECT '加梅子',   3
  UNION ALL SELECT '不加梅子', 4
) v
WHERE NOT EXISTS (SELECT 1 FROM custom_option c WHERE c.name = v.name)
ORDER BY v.ord;

-- 之後要新增、停用、調順序：
-- INSERT INTO custom_option (name, sort_order) VALUES ('去冰塊分裝', 5);
-- UPDATE custom_option SET active = FALSE WHERE name = '少料' AND option_id > 0;
-- UPDATE custom_option SET max_qty = 2 WHERE name = '加酸' AND option_id > 0;

-- 核對：
-- SELECT option_id, name, max_qty, sort_order, active FROM custom_option ORDER BY sort_order, option_id;
