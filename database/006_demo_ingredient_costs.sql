-- Fictional teaching/demo costs in TWD per inventory.base_unit.
-- Apply ONCE after 005. This adds no tables and changes no selling prices or stock.
USE drinkshop_pos;
SET NAMES utf8mb4;
ALTER TABLE inventory
  ADD COLUMN unit_cost DECIMAL(10,2) NULL COMMENT 'Estimated cost in TWD per base unit; NULL means unknown',
  ADD CONSTRAINT chk_inventory_unit_cost CHECK (unit_cost IS NULL OR unit_cost >= 0);

START TRANSACTION;
-- Only seed the known fictional ingredients; never overwrite an existing cost.
UPDATE inventory i JOIN (
  SELECT 1 AS id,'中杯紙杯' AS name,'個' AS unit,0.80 AS cost
  UNION ALL SELECT 2,'大杯紙杯','個',1.00
  UNION ALL SELECT 3,'紅茶包','包',3.00
  UNION ALL SELECT 4,'綠茶包','包',3.00
  UNION ALL SELECT 5,'奶料','份',6.00
  UNION ALL SELECT 6,'珍珠','份',3.00
  UNION ALL SELECT 7,'茶凍','份',2.50
  UNION ALL SELECT 8,'芒果冰沙料','份',12.50
) demo ON i.inventory_id=demo.id AND i.name=demo.name AND i.base_unit=demo.unit
SET i.unit_cost=demo.cost
WHERE i.unit_cost IS NULL;
COMMIT;
