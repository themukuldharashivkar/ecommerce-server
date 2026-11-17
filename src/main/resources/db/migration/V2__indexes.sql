-- Indexes for the heavy queries and for every foreign key.
-- MySQL/InnoDB indexes foreign keys automatically; PostgreSQL does not, so without
-- these every join and "by parent" lookup would be a sequential scan.

-- ---------- catalog ----------
-- Filtered catalog: WHERE category = ? AND discounted_price BETWEEN ? AND ? ORDER BY discounted_price.
-- Leading with category_id also covers the product -> category foreign key.
CREATE INDEX idx_product_category_price ON product (category_id, discounted_price);
-- Catalog without a category filter ("all products"), sorted by price.
CREATE INDEX idx_product_discounted_price ON product (discounted_price);

-- Search uses LOWER(col) LIKE '%term%', which a B-tree cannot serve; trigram GIN indexes can.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_product_title_trgm ON product USING gin (lower(title) gin_trgm_ops);
CREATE INDEX idx_product_brand_trgm ON product USING gin (lower(brand) gin_trgm_ops);
CREATE INDEX idx_product_description_trgm ON product USING gin (lower(description) gin_trgm_ops);

CREATE INDEX idx_categories_name ON categories (name);
CREATE INDEX idx_categories_parent ON categories (parent_category_id);
CREATE INDEX idx_product_sizes_product ON product_sizes (product_id);

-- ---------- reviews and ratings by product ----------
CREATE INDEX idx_review_product ON review (product_id);
CREATE INDEX idx_review_user ON review (user_id);
CREATE INDEX idx_rating_product ON rating (product_id);
CREATE INDEX idx_rating_user ON rating (user_id);

-- ---------- orders ----------
CREATE INDEX idx_orders_user ON orders (user_id);
CREATE INDEX idx_orders_shipping_address ON orders (shipping_address_id);
CREATE INDEX idx_order_item_order ON order_item (order_id);
CREATE INDEX idx_order_item_product ON order_item (product_id);

-- ---------- users and carts ----------
-- Signup checks for an existing email in code; this makes it a hard guarantee.
ALTER TABLE users ADD CONSTRAINT uk_users_email UNIQUE (email);
-- Each user has exactly one cart (Cart.user is @OneToOne).
ALTER TABLE cart ADD CONSTRAINT uk_cart_user UNIQUE (user_id);
CREATE INDEX idx_cart_item_cart ON cart_item (cart_id);
CREATE INDEX idx_cart_item_product ON cart_item (product_id);
CREATE INDEX idx_address_user ON address (user_id);
CREATE INDEX idx_payment_information_user ON payment_information (user_id);
