-- Stock can never be negative, even if application code has a bug: the database refuses the change.
-- Reservations already use conditional UPDATEs (... WHERE quantity >= n); this is the last line of defence.
-- Older code never reserved stock, so clamp any negative values before adding the constraints.
UPDATE product SET quantity = 0 WHERE quantity < 0;
UPDATE product_sizes SET quantity = 0 WHERE quantity < 0;

ALTER TABLE product ADD CONSTRAINT product_quantity_non_negative CHECK (quantity >= 0);
ALTER TABLE product_sizes ADD CONSTRAINT product_sizes_quantity_non_negative CHECK (quantity >= 0);
