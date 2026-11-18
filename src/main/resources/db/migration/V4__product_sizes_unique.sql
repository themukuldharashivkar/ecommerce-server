-- A product lists each size once. Stock is reserved per (product, size) with conditional UPDATEs,
-- so a duplicate row would let the same size be sold twice; make the database reject it.
CREATE UNIQUE INDEX uk_product_sizes_product_name ON product_sizes (product_id, lower(name));
