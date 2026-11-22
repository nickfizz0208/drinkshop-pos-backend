-- 048: Temporary stock availability is independent of manual sales suspension.
ALTER TABLE product ADD COLUMN sold_out BOOLEAN NOT NULL DEFAULT FALSE;
