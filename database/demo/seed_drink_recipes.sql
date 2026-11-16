-- Optional project demo for a local test database. Run after the team schema and menu scripts.
CREATE TEMPORARY TABLE demo_recipe (
  drink VARCHAR(50) NOT NULL PRIMARY KEY,
  steps TEXT NOT NULL
) CHARACTER SET utf8mb4;
CREATE TEMPORARY TABLE demo_material (
  drink VARCHAR(50) NOT NULL,
  name VARCHAR(50) NOT NULL,
  unit VARCHAR(10) NOT NULL,
  sort_order INT NOT NULL,
  portions DECIMAL(8,2) NOT NULL,
  PRIMARY KEY (drink, name)
) CHARACTER SET utf8mb4;
CREATE TEMPORARY TABLE demo_target (product_id BIGINT PRIMARY KEY, drink VARCHAR(50) NOT NULL)
  CHARACTER SET utf8mb4;

INSERT INTO demo_recipe (drink, steps) VALUES
  ('鐵觀音拿鐵', CONCAT_WS(CHAR(10),
    '冰飲 1 杯',
    '茶湯、鮮奶每份均以 100 mL 計。依上方材料份數量取。',
    '1. 泡好濃鐵觀音茶，濾掉茶葉後放涼。',
    '2. 倒入濃茶湯與冷藏全脂鮮奶，攪拌均勻。',
    '3. 依口味加入糖漿和冰塊。'
  )),
  ('錫蘭奶茶', CONCAT_WS(CHAR(10),
    '冰飲 1 杯',
    '茶湯、鮮奶每份 100 mL；粉末和固體加料每份 10 g。依材料份數量取。',
    '趁錫蘭紅茶湯還溫熱時，把奶精粉攪拌至溶解。放涼後調糖、加冰。'
  )),
  ('鐵觀音奶茶', CONCAT_WS(CHAR(10),
    '冰飲 1 杯',
    '茶湯、鮮奶每份 100 mL；粉末和固體加料每份 10 g。依材料份數量取。',
    '依茶葉包裝建議泡濃茶並濾清。趁溫拌入奶精粉，放涼後調糖、加冰。'
  )),
  ('波霸奶茶', CONCAT_WS(CHAR(10),
    '冰飲 1 杯',
    '茶湯、鮮奶每份 100 mL；粉末和固體加料每份 10 g。依材料份數量取。',
    '依包裝將波霸煮熟瀝乾。奶精粉拌入溫紅茶湯，放涼後加冰，倒入放好波霸的杯中。'
  )),
  ('粉粿紅茶拿鐵', CONCAT_WS(CHAR(10),
    '冰飲 1 杯',
    '茶湯、鮮奶每份 100 mL；粉末和固體加料每份 10 g。依材料份數量取。',
    '將做好的粉粿放涼切塊，放入杯底。倒入放涼的紅茶湯與鮮奶，依口味加糖和冰。'
  )),
  ('紅茶拿鐵', CONCAT_WS(CHAR(10),
    '冰飲 1 杯',
    '茶湯、鮮奶每份 100 mL；粉末和固體加料每份 10 g。依材料份數量取。',
    '先泡濃錫蘭紅茶，濾掉茶葉並放涼。按份數加入鮮奶，依口味加糖和冰。'
  )),
  ('波霸紅茶拿鐵', CONCAT_WS(CHAR(10),
    '冰飲 1 杯',
    '茶湯、鮮奶每份 100 mL；粉末和固體加料每份 10 g。依材料份數量取。',
    '將煮熟瀝乾的波霸放入杯底，再倒入放涼的紅茶湯和鮮奶。依口味加冰。'
  )),
  ('高山金萱茶', CONCAT_WS(CHAR(10),
    '冰飲 1 杯',
    '茶湯、鮮奶每份 100 mL；粉末和固體加料每份 10 g。依材料份數量取。',
    '依茶葉包裝建議沖泡金萱，泡好後濾清、放涼。依口味加糖和冰。'
  )),
  ('粉粿金萱', CONCAT_WS(CHAR(10),
    '冰飲 1 杯',
    '茶湯、鮮奶每份 100 mL；粉末和固體加料每份 10 g。依材料份數量取。',
    '粉粿放涼後切成小塊鋪在杯底。把金萱茶湯放涼再倒入，最後加冰。'
  )),
  ('金萱雙Q', CONCAT_WS(CHAR(10),
    '冰飲 1 杯',
    '茶湯、鮮奶每份 100 mL；粉末和固體加料每份 10 g。依材料份數量取。',
    '杯底放入煮熟的波霸和瀝過糖水的椰果，倒入放涼的金萱茶湯，依口味加冰。'
  )),
  ('紅萱烏龍', CONCAT_WS(CHAR(10),
    '冰飲 1 杯',
    '茶湯、鮮奶每份 100 mL；粉末和固體加料每份 10 g。依材料份數量取。',
    '以紅烏龍和金萱調配。兩種茶各自沖泡、濾清並放涼，再依上方份數混合。'
  )),
  ('翡翠綠茶', CONCAT_WS(CHAR(10),
    '冰飲 1 杯',
    '茶湯、鮮奶每份 100 mL；粉末和固體加料每份 10 g。依材料份數量取。',
    '採用茉莉綠茶。用低於沸點的熱水按茶葉包裝沖泡，濾清放涼後再加冰。'
  )),
  ('錫蘭紅茶', CONCAT_WS(CHAR(10),
    '冰飲 1 杯',
    '茶湯、鮮奶每份 100 mL；粉末和固體加料每份 10 g。依材料份數量取。',
    '依茶葉包裝建議熱泡，達到想要的濃度後濾清。茶湯放涼後按口味加糖、加冰。'
  )),
  ('文山青茶', CONCAT_WS(CHAR(10),
    '冰飲 1 杯',
    '茶湯、鮮奶每份 100 mL；粉末和固體加料每份 10 g。依材料份數量取。',
    '用文山包種茶沖泡，取出茶包或濾掉茶葉。茶湯放涼後加冰，可先試無糖。'
  )),
  ('古早味紅茶', CONCAT_WS(CHAR(10),
    '冰飲 1 杯',
    '茶湯、鮮奶每份 100 mL；粉末和固體加料每份 10 g。依材料份數量取。',
    '紅茶和炒決明子分開沖泡並過濾，照上方份數混合。可趁溫拌入少量二砂，放涼後加冰。'
  )),
  ('蕎麥綠寶石', CONCAT_WS(CHAR(10),
    '冰飲 1 杯',
    '茶湯、鮮奶每份 100 mL；粉末和固體加料每份 10 g。依材料份數量取。',
    '將焙炒蕎麥與綠茶分開沖泡、過濾。以放涼的綠茶為主調，加入蕎麥茶後加冰。'
  ));

INSERT INTO demo_material (drink, name, unit, sort_order, portions) VALUES
  ('鐵觀音拿鐵', '鐵觀音濃茶湯', 'mL', 1, 2.00),
  ('鐵觀音拿鐵', '全脂鮮奶', 'mL', 2, 2.00),
  ('錫蘭奶茶', '錫蘭紅茶湯', 'mL', 1, 3.00),
  ('錫蘭奶茶', '奶精粉', 'g', 2, 3.00),
  ('鐵觀音奶茶', '鐵觀音濃茶湯', 'mL', 1, 3.00),
  ('鐵觀音奶茶', '奶精粉', 'g', 2, 3.00),
  ('波霸奶茶', '錫蘭紅茶湯', 'mL', 1, 3.00),
  ('波霸奶茶', '奶精粉', 'g', 2, 3.00),
  ('波霸奶茶', '熟波霸粉圓', 'g', 3, 5.00),
  ('粉粿紅茶拿鐵', '錫蘭紅茶湯', 'mL', 1, 2.00),
  ('粉粿紅茶拿鐵', '全脂鮮奶', 'mL', 2, 1.50),
  ('粉粿紅茶拿鐵', '熟粉粿', 'g', 3, 5.00),
  ('紅茶拿鐵', '錫蘭紅茶湯', 'mL', 1, 2.00),
  ('紅茶拿鐵', '全脂鮮奶', 'mL', 2, 1.00),
  ('波霸紅茶拿鐵', '錫蘭紅茶湯', 'mL', 1, 2.00),
  ('波霸紅茶拿鐵', '全脂鮮奶', 'mL', 2, 1.50),
  ('波霸紅茶拿鐵', '熟波霸粉圓', 'g', 3, 5.00),
  ('高山金萱茶', '高山金萱茶湯', 'mL', 1, 3.50),
  ('粉粿金萱', '高山金萱茶湯', 'mL', 1, 3.00),
  ('粉粿金萱', '熟粉粿', 'g', 2, 5.00),
  ('金萱雙Q', '高山金萱茶湯', 'mL', 1, 3.00),
  ('金萱雙Q', '熟波霸粉圓', 'g', 2, 3.00),
  ('金萱雙Q', '椰果', 'g', 3, 3.00),
  ('紅萱烏龍', '紅烏龍茶湯', 'mL', 1, 2.00),
  ('紅萱烏龍', '高山金萱茶湯', 'mL', 2, 1.50),
  ('翡翠綠茶', '茉莉綠茶湯', 'mL', 1, 3.50),
  ('錫蘭紅茶', '錫蘭紅茶湯', 'mL', 1, 3.50),
  ('文山青茶', '文山包種茶湯', 'mL', 1, 3.50),
  ('古早味紅茶', '錫蘭紅茶湯', 'mL', 1, 3.00),
  ('古早味紅茶', '焙炒決明子茶湯', 'mL', 2, 0.50),
  ('蕎麥綠寶石', '茉莉綠茶湯', 'mL', 1, 2.50),
  ('蕎麥綠寶石', '焙炒蕎麥茶湯', 'mL', 2, 1.00);

START TRANSACTION;
INSERT INTO demo_target (product_id, drink)
SELECT p.product_id, d.drink
FROM demo_recipe d
JOIN product p ON p.name = d.drink AND p.on_sale
WHERE COALESCE(TRIM(p.recipe_steps), '') = ''
  AND NOT EXISTS (SELECT 1 FROM product_recipe r WHERE r.product_id = p.product_id)
  AND NOT EXISTS (
    SELECT 1
    FROM demo_material m
    JOIN inventory i ON i.name = m.name
    WHERE m.drink = d.drink AND i.base_unit <> m.unit
  )
FOR UPDATE;

INSERT INTO inventory (name, base_unit, category)
SELECT DISTINCT m.name, m.unit, '配方原料'
FROM demo_material m
JOIN demo_target t ON t.drink = m.drink
WHERE NOT EXISTS (SELECT 1 FROM inventory i WHERE i.name = m.name);

INSERT INTO product_recipe (product_id, inventory_id, sort_order, portions)
SELECT t.product_id, i.inventory_id, m.sort_order, m.portions
FROM demo_target t
JOIN demo_material m ON m.drink = t.drink
JOIN inventory i ON i.name = m.name AND i.base_unit = m.unit;

UPDATE product p
JOIN demo_target t ON t.product_id = p.product_id
JOIN demo_recipe d ON d.drink = t.drink
SET p.recipe_steps = d.steps;

COMMIT;

SELECT p.name, COUNT(r.product_id) AS materials,
       p.recipe_steps LIKE '冰飲 1 杯%' AS has_steps
FROM product p
LEFT JOIN product_recipe r ON r.product_id = p.product_id
WHERE p.on_sale AND p.name IN (SELECT drink FROM demo_recipe)
GROUP BY p.product_id, p.name, p.recipe_steps
ORDER BY p.product_id;

DROP TEMPORARY TABLE demo_target;
DROP TEMPORARY TABLE demo_material;
DROP TEMPORARY TABLE demo_recipe;
