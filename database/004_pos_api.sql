-- Apply ONCE to the existing POS database before running the API.
USE drinkshop_pos;
ALTER TABLE pos_order ADD COLUMN request_hash CHAR(64) NULL;
ALTER TABLE order_item ADD COLUMN deducted_inventory_json JSON NULL
  COMMENT 'Total inventory quantities deducted for this line, for exact restoration';

-- Backfill only the known original fixtures. Unknown rows stay NULL and
-- cannot be cancelled with stock restoration without reviewed snapshots.
UPDATE order_item SET deducted_inventory_json='{"2":2,"3":2,"6":2}'
WHERE item_id=1 AND order_id=1 AND qty=2 AND EXISTS
 (SELECT 1 FROM pos_order WHERE order_id=1 AND order_no='DEMO-20261028-001');
UPDATE order_item SET deducted_inventory_json='{"1":1,"3":1,"5":1,"7":1}'
WHERE item_id=2 AND order_id=1 AND qty=1 AND EXISTS
 (SELECT 1 FROM pos_order WHERE order_id=1 AND order_no='DEMO-20261028-001');
UPDATE order_item SET deducted_inventory_json='{"1":1,"8":1}'
WHERE item_id=3 AND order_id=2 AND qty=1 AND EXISTS
 (SELECT 1 FROM pos_order WHERE order_id=2 AND order_no='DEMO-20261028-002');
UPDATE order_item SET deducted_inventory_json='{"1":1,"4":1}'
WHERE item_id=4 AND order_id=3 AND qty=1 AND EXISTS
 (SELECT 1 FROM pos_order WHERE order_id=3 AND order_no='DEMO-20261028-003');
UPDATE order_item SET deducted_inventory_json='{"2":1,"3":1,"5":1,"6":1}'
WHERE item_id=5 AND order_id=4 AND qty=1 AND EXISTS
 (SELECT 1 FROM pos_order WHERE order_id=4 AND order_no='DEMO-20261028-004');
