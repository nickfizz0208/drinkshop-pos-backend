-- Test-only mirror of MySQL baseline + migrations 004, 005 and 006.
-- Run manually against a NEW database. Never run through the old quiz app.

CREATE TABLE staff_account (
  staff_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(50) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  name VARCHAR(50) NOT NULL,
  level ENUM('TRAINEE','STAFF','MANAGER','HQ') NOT NULL,
  pin_hash VARCHAR(255) NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE member (
  phone VARCHAR(20) PRIMARY KEY,
  name VARCHAR(50) NOT NULL,
  address VARCHAR(255) NULL,
  lat DECIMAL(10,7) NULL,
  lng DECIMAL(10,7) NULL,
  note VARCHAR(255) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CHECK (lat IS NULL OR lat BETWEEN -90 AND 90),
  CHECK (lng IS NULL OR lng BETWEEN -180 AND 180)
);

CREATE TABLE inventory (
  inventory_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(50) NOT NULL UNIQUE,
  base_unit VARCHAR(10) NOT NULL,
  pack_unit VARCHAR(10) NULL,
  per_pack DECIMAL(12,2) NULL,
  book_qty DECIMAL(12,2) NOT NULL DEFAULT 0,
  safety_stock DECIMAL(12,2) NOT NULL DEFAULT 0,
  reported_qty DECIMAL(12,2) NULL,
  reported_by BIGINT NULL,
  reported_at DATETIME(6) NULL,
  unit_cost DECIMAL(10,2) NULL,
  FOREIGN KEY (reported_by) REFERENCES staff_account(staff_id),
  CHECK (book_qty >= 0 AND safety_stock >= 0),
  CONSTRAINT chk_inventory_unit_cost CHECK (unit_cost IS NULL OR unit_cost >= 0),
  CHECK ((pack_unit IS NULL AND per_pack IS NULL) OR (pack_unit IS NOT NULL AND per_pack IS NOT NULL AND per_pack > 0)),
  CHECK ((reported_qty IS NULL AND reported_by IS NULL AND reported_at IS NULL)
    OR (reported_qty IS NOT NULL AND reported_qty >= 0 AND reported_by IS NOT NULL AND reported_at IS NOT NULL))
);

CREATE TABLE product (
  product_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(50) NOT NULL UNIQUE,
  category VARCHAR(30) NOT NULL,
  base_price DECIMAL(10,2) NOT NULL,
  large_extra DECIMAL(10,2) NOT NULL DEFAULT 0,
  hot_allowed BOOLEAN NOT NULL DEFAULT TRUE,
  recipe_steps TEXT NOT NULL,
  recipe_json JSON NOT NULL,
  min_view_level ENUM('TRAINEE','STAFF','MANAGER','HQ') NOT NULL DEFAULT 'TRAINEE',
  on_sale BOOLEAN NOT NULL DEFAULT TRUE,
  CHECK (base_price >= 0 AND large_extra >= 0)
);

CREATE TABLE topping (
  topping_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(30) NOT NULL UNIQUE,
  price DECIMAL(10,2) NOT NULL,
  inventory_id BIGINT NOT NULL,
  deduct_qty DECIMAL(12,2) NOT NULL,
  on_sale BOOLEAN NOT NULL DEFAULT TRUE,
  FOREIGN KEY (inventory_id) REFERENCES inventory(inventory_id),
  CHECK (price >= 0 AND deduct_qty > 0)
);

CREATE TABLE conflict_rule (
  rule_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  rule_type ENUM('FORBID','AUTO_ADJUST','MAX_COUNT','MUTEX') NOT NULL,
  condition_json JSON NOT NULL,
  action_json JSON NOT NULL,
  message VARCHAR(200) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE daily_shift (
  business_date DATE PRIMARY KEY,
  status ENUM('OPEN','CLOSED') NOT NULL DEFAULT 'OPEN',
  opened_by BIGINT NOT NULL,
  opened_at DATETIME(6) NOT NULL,
  petty_cash DECIMAL(10,2) NOT NULL,
  roles_json JSON NOT NULL,
  revenue_by_payment JSON NULL,
  cups INT NULL,
  expected_cash DECIMAL(10,2) NULL,
  counted_cash DECIMAL(10,2) NULL,
  diff_reason VARCHAR(255) NULL,
  closed_by BIGINT NULL,
  closed_at DATETIME(6) NULL,
  FOREIGN KEY (opened_by) REFERENCES staff_account(staff_id),
  FOREIGN KEY (closed_by) REFERENCES staff_account(staff_id),
  CHECK (petty_cash >= 0),
  CHECK (cups IS NULL OR cups >= 0),
  CHECK (expected_cash IS NULL OR expected_cash >= 0),
  CHECK (counted_cash IS NULL OR counted_cash >= 0),
  CHECK ((status = 'OPEN' AND closed_by IS NULL AND closed_at IS NULL)
    OR (status = 'CLOSED' AND closed_by IS NOT NULL AND closed_at IS NOT NULL
      AND revenue_by_payment IS NOT NULL AND cups IS NOT NULL
      AND expected_cash IS NOT NULL AND counted_cash IS NOT NULL)),
  CHECK (counted_cash IS NULL OR expected_cash IS NULL OR counted_cash >= expected_cash
    OR (diff_reason IS NOT NULL AND CHAR_LENGTH(TRIM(diff_reason)) > 0))
);

CREATE TABLE pos_order (
  order_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_no VARCHAR(40) NOT NULL UNIQUE,
  business_date DATE NOT NULL,
  member_phone VARCHAR(20) NULL,
  source ENUM('DINE_IN','TAKE_OUT','PHONE_DELIVERY','UBER_EATS') NOT NULL,
  payment ENUM('CASH','CREDIT_CARD','LINE_PAY','UBER_EATS') NULL,
  payment_status VARCHAR(10) DEFAULT 'PAID' NOT NULL,
  paid_amount DECIMAL(10,2) DEFAULT 0 NOT NULL,
  refunded_amount DECIMAL(10,2) DEFAULT 0 NOT NULL,
  total DECIMAL(10,2) NOT NULL COMMENT 'Net retained amount; sum of non-cancelled, non-wasted items',
  received_cash DECIMAL(10,2) NULL,
  change_cash DECIMAL(10,2) NULL,
  status ENUM('PENDING','MAKING','DONE','PICKED_UP','CANCELLED') NOT NULL DEFAULT 'PENDING',
  distance DECIMAL(10,2) NULL,
  duration INT NULL,
  delivery_fee DECIMAL(10,2) NOT NULL DEFAULT 0,
  address_note VARCHAR(255) NULL,
  note VARCHAR(255) NULL,
  created_by BIGINT NOT NULL,
  platform_no VARCHAR(80) NULL UNIQUE,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  FOREIGN KEY (business_date) REFERENCES daily_shift(business_date),
  FOREIGN KEY (member_phone) REFERENCES member(phone) ON UPDATE CASCADE,
  FOREIGN KEY (created_by) REFERENCES staff_account(staff_id),
  INDEX idx_order_date_payment (business_date, payment),
  CHECK (total >= 0 AND delivery_fee >= 0),
  CHECK (distance IS NULL OR distance >= 0),
  CHECK (duration IS NULL OR duration >= 0),
  CHECK ((payment = 'CASH' AND received_cash IS NOT NULL AND change_cash IS NOT NULL
      AND received_cash >= paid_amount AND change_cash = received_cash - paid_amount + refunded_amount)
    OR (payment <> 'CASH' AND received_cash IS NULL AND change_cash IS NULL)
    OR (payment_status='UNPAID' AND payment IS NULL AND received_cash IS NULL AND change_cash IS NULL))
);

CREATE TABLE order_item (
  item_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  product_name VARCHAR(50) NOT NULL COMMENT 'Name at purchase',
  size ENUM('M','L') NOT NULL,
  temperature ENUM('COLD','WARM','HOT') NOT NULL,
  sugar ENUM('NORMAL','LESS','HALF','LIGHT','NONE') NOT NULL,
  ice ENUM('NORMAL','LESS','LIGHT','NONE') NOT NULL,
  qty INT NOT NULL,
  unit_price DECIMAL(10,2) NOT NULL COMMENT 'Final per-cup price INCLUDING all toppings',
  status ENUM('PENDING','MAKING','DONE','PICKED_UP','CANCELLED','WASTED') NOT NULL DEFAULT 'PENDING',
  note VARCHAR(255) NULL,
  reason VARCHAR(255) NULL,
  photo_path VARCHAR(500) NULL,
  approved_by BIGINT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  FOREIGN KEY (order_id) REFERENCES pos_order(order_id),
  FOREIGN KEY (product_id) REFERENCES product(product_id),
  FOREIGN KEY (approved_by) REFERENCES staff_account(staff_id),
  INDEX idx_item_order_status (order_id, status),
  CHECK (qty > 0 AND unit_price >= 0),
  CHECK (temperature = 'COLD' OR ice = 'NONE'),
  CHECK (status NOT IN ('CANCELLED','WASTED') OR (reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason)) > 0)),
  CHECK (status <> 'WASTED' OR approved_by IS NOT NULL)
);

CREATE TABLE order_item_topping (
  order_topping_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  item_id BIGINT NOT NULL,
  topping_id BIGINT NOT NULL,
  topping_name VARCHAR(30) NOT NULL COMMENT 'Name at purchase',
  price_at_order DECIMAL(10,2) NOT NULL COMMENT 'Per-cup add-on price; already included in order_item.unit_price',
  CONSTRAINT uq_order_item_topping UNIQUE (item_id, topping_id),
  FOREIGN KEY (item_id) REFERENCES order_item(item_id),
  FOREIGN KEY (topping_id) REFERENCES topping(topping_id),
  CHECK (price_at_order >= 0)
);
ALTER TABLE pos_order ADD COLUMN request_hash CHAR(64);
ALTER TABLE order_item ADD COLUMN deducted_inventory_json JSON;

ALTER TABLE order_item ADD material_cost DECIMAL(10,2);
ALTER TABLE order_item ADD refund_amount DECIMAL(10,2) DEFAULT 0 NOT NULL;
