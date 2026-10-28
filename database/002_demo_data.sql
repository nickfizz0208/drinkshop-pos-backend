USE drinkshop_pos;
SET NAMES utf8mb4;
SET time_zone = '+08:00';
START TRANSACTION;

-- Disabled placeholders, NOT login credentials. Authentication is not implemented here.
INSERT INTO staff_account (staff_id,username,password_hash,name,level,active) VALUES
 (1,'demo_staff','!DISABLED_DEMO_ACCOUNT','測試店員','STAFF',FALSE),
 (2,'demo_manager','!DISABLED_DEMO_ACCOUNT','測試店長','MANAGER',FALSE);

INSERT INTO member (phone,name,address,note) VALUES
 ('0900000001','測試會員甲','測試市測試路一號（虛構地址）','僅供測試，請勿配送');

-- Opening stock before the sample orders below.
INSERT INTO inventory (inventory_id,name,base_unit,pack_unit,per_pack,book_qty,safety_stock) VALUES
 (1,'中杯紙杯','個','袋',50,100,20),
 (2,'大杯紙杯','個','袋',50,100,20),
 (3,'紅茶包','包','盒',20,100,20),
 (4,'綠茶包','包','盒',20,100,20),
 (5,'奶料','份',NULL,NULL,100,20),
 (6,'珍珠','份',NULL,NULL,100,20),
 (7,'茶凍','份',NULL,NULL,100,20),
 (8,'芒果冰沙料','份',NULL,NULL,8,10);

-- Recipe items reference inventory IDs, never ingredient names as keys.
-- Quantities are per cup; M/L recipes include the appropriate paper cup.
INSERT INTO product (product_id,name,category,base_price,large_extra,hot_allowed,recipe_steps,recipe_json,min_view_level) VALUES
 (1,'經典紅茶','茶類',25,5,TRUE,'依杯型備杯，加入紅茶，依客製甜度與冰量調製。',
 '{"M":[{"inventoryId":1,"qty":1},{"inventoryId":3,"qty":1}],"L":[{"inventoryId":2,"qty":1},{"inventoryId":3,"qty":1}]}','TRAINEE'),
 (2,'茉香綠茶','茶類',25,5,TRUE,'依杯型備杯，加入綠茶，依客製甜度與冰量調製。',
 '{"M":[{"inventoryId":1,"qty":1},{"inventoryId":4,"qty":1}],"L":[{"inventoryId":2,"qty":1},{"inventoryId":4,"qty":1}]}','TRAINEE'),
 (3,'經典奶茶','奶茶',40,10,TRUE,'依杯型備杯，混合紅茶與一份奶料，依客製規格調製。',
 '{"M":[{"inventoryId":1,"qty":1},{"inventoryId":3,"qty":1},{"inventoryId":5,"qty":1}],"L":[{"inventoryId":2,"qty":1},{"inventoryId":3,"qty":1},{"inventoryId":5,"qty":1}]}','TRAINEE'),
 (4,'珍珠奶茶','奶茶',50,10,TRUE,'依杯型備杯，加入一份珍珠、紅茶與一份奶料。品項已含珍珠，額外點珍珠代表再加一份。',
 '{"M":[{"inventoryId":1,"qty":1},{"inventoryId":3,"qty":1},{"inventoryId":5,"qty":1},{"inventoryId":6,"qty":1}],"L":[{"inventoryId":2,"qty":1},{"inventoryId":3,"qty":1},{"inventoryId":5,"qty":1},{"inventoryId":6,"qty":1}]}','STAFF'),
 (5,'芒果冰沙','冰沙',55,10,FALSE,'依杯型備杯，加入一份冰沙料，以固定正常甜度與正常冰量攪打。',
 '{"M":[{"inventoryId":1,"qty":1},{"inventoryId":8,"qty":1}],"L":[{"inventoryId":2,"qty":1},{"inventoryId":8,"qty":1}]}','STAFF');

INSERT INTO topping (topping_id,name,price,inventory_id,deduct_qty) VALUES
 (1,'珍珠',10,6,1),(2,'茶凍',10,7,1);

-- JSON contract examples, not an implemented rules engine.
INSERT INTO conflict_rule (rule_id,rule_type,condition_json,action_json,message) VALUES
 (1,'FORBID','{"temperatureIn":["WARM","HOT"]}','{"forbidToppingIds":[2]}','溫熱飲不可加茶凍'),
 (2,'AUTO_ADJUST','{"category":"冰沙"}','{"set":{"temperature":"COLD","sugar":"NORMAL","ice":"NORMAL"},"lockFields":["temperature","sugar","ice"]}','冰沙固定冰量與甜度'),
 (3,'AUTO_ADJUST','{"temperatureIn":["WARM","HOT"]}','{"set":{"ice":"NONE"},"lockFields":["ice"]}','溫熱飲不加冰塊'),
 (4,'MAX_COUNT','{"always":true}','{"field":"toppingIds","maxDistinct":3}','額外加料最多三種');

-- Fixed historical fixture, not an automatic opening for today's business.
INSERT INTO daily_shift (business_date,status,opened_by,opened_at,petty_cash,roles_json)
 VALUES ('2026-10-28','OPEN',1,'2026-10-28 09:00:00',2000,
 '[{"staffId":1,"role":"CASHIER"},{"staffId":2,"role":"BAR"}]');

INSERT INTO pos_order (order_id,order_no,business_date,member_phone,source,payment,total,received_cash,change_cash,status,created_by,created_at) VALUES
 (1,'DEMO-20261028-001','2026-10-28',NULL,'TAKE_OUT','CASH',130,200,70,'PENDING',1,'2026-10-28 10:00:00'),
 (2,'DEMO-20261028-002','2026-10-28',NULL,'TAKE_OUT','LINE_PAY',55,NULL,NULL,'MAKING',1,'2026-10-28 10:05:00'),
 (3,'DEMO-20261028-003','2026-10-28',NULL,'TAKE_OUT','LINE_PAY',0,NULL,NULL,'CANCELLED',1,'2026-10-28 10:10:00'),
 (4,'DEMO-20261028-004','2026-10-28',NULL,'DINE_IN','LINE_PAY',0,NULL,NULL,'CANCELLED',1,'2026-10-28 10:15:00');

-- Order 1: two large red teas + pearl at 40 each, plus one medium milk tea + jelly at 50.
-- Order 3: cancelled before making, all stock returned.
-- Order 4: made then wasted, refunded but stock NOT returned; photo optional.
INSERT INTO order_item (item_id,order_id,product_id,product_name,size,temperature,sugar,ice,qty,unit_price,status,reason,approved_by,note) VALUES
 (1,1,1,'經典紅茶','L','COLD','HALF','LESS',2,40,'PENDING',NULL,NULL,'同規格兩杯'),
 (2,1,3,'經典奶茶','M','COLD','NORMAL','NORMAL',1,50,'PENDING',NULL,NULL,NULL),
 (3,2,5,'芒果冰沙','M','COLD','NORMAL','NORMAL',1,55,'MAKING',NULL,NULL,NULL),
 (4,3,2,'茉香綠茶','M','COLD','NONE','NONE',1,25,'CANCELLED','測試：尚未製作，客人取消',NULL,NULL),
 (5,4,4,'珍珠奶茶','L','COLD','HALF','LESS',1,60,'WASTED','測試：已製作棄單',2,NULL);

INSERT INTO order_item_topping (item_id,topping_id,topping_name,price_at_order) VALUES
 (1,1,'珍珠',10),(2,2,'茶凍',10);

-- Apply the NET stock effects of the fixtures exactly once inside this transaction.
-- Cancelled item 4 has zero net stock effect; wasted item 5 remains deducted.
UPDATE inventory SET book_qty=book_qty-2 WHERE inventory_id=1;
UPDATE inventory SET book_qty=book_qty-3 WHERE inventory_id=2;
UPDATE inventory SET book_qty=book_qty-4 WHERE inventory_id=3;
UPDATE inventory SET book_qty=book_qty-2 WHERE inventory_id=5;
UPDATE inventory SET book_qty=book_qty-3 WHERE inventory_id=6;
UPDATE inventory SET book_qty=book_qty-1 WHERE inventory_id=7;
UPDATE inventory SET book_qty=book_qty-1 WHERE inventory_id=8;
COMMIT;
