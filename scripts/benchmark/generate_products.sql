-- Benchmark data only: never run against a real database.
-- Expects the 499 seeded products (and their categories/sizes) to be present already.
-- Adds 200 synthetic copies of each product (~100k rows): same category, brand, colour
-- and text, with the discounted price varied by +/-40% using a fixed random seed.
SELECT setseed(0.42);

INSERT INTO product (id, brand, color, created_at, description, discount_persent, discounted_price,
                     image_url, num_ratings, price, quantity, title, category_id)
SELECT p.id + g * 1000000, p.brand, p.color, p.created_at, p.description, p.discount_persent,
       greatest(1, round(p.discounted_price * (0.6 + random() * 0.8)))::int,
       p.image_url, p.num_ratings, p.price, p.quantity, p.title, p.category_id
FROM product p CROSS JOIN generate_series(1, 200) g
WHERE p.id < 1000000;

INSERT INTO product_sizes (product_id, name, quantity)
SELECT s.product_id + g * 1000000, s.name, s.quantity
FROM product_sizes s CROSS JOIN generate_series(1, 200) g;

VACUUM ANALYZE;
