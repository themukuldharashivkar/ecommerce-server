# Performance measurements

Every number here comes from a real run. Each section states how it was measured.

## 1. Database indexes (Flyway `V2__indexes.sql`), measured 2026-11-17

### What was measured

The catalog filter query and the search query, using `EXPLAIN (ANALYZE, BUFFERS)` in PostgreSQL.
The SQL is exactly what Hibernate generates (captured from `show-sql`), with the parameters bound as literals.
Each query ran once to warm up, then 9 times. The table shows the **median server execution time**, with the min–max range in brackets.
This is database time only. It excludes network, JDBC and the Java/JSON work in the API (see "Limits" below).

### Data

The real catalog is only 499 products. At that size PostgreSQL scans the whole table in well under a millisecond, so indexes barely matter.
To get meaningful numbers, a **separate benchmark database** was filled with synthetic data:

- the 499 real products and their 16 categories, plus 200 generated variants of each product (**100,299 products**, 300,897 size rows, 40 MB)
- variants keep the category, brand, colour and text; their price is varied by ±40% with a fixed random seed
- `men_jeans` matches 15,678 products and `saree` 15,678; search for `jeans` matches 23,718 and `silk` 1,809

This is benchmark data, not production data.

### Environment

PostgreSQL 16.15 (Docker, `postgres:16-alpine`, default settings: `shared_buffers` 128 MB, `work_mem` 4 MB) on an Apple M5 MacBook with 16 GB RAM (10 CPUs and 7.7 GB available to Docker). `VACUUM ANALYZE` ran before each set of runs, and the data fit in memory.

"Before" means the schema from `V1__init_schema.sql` only (primary keys, no other indexes). "After" means `V2__indexes.sql` applied as well.

### Results

| Query | Before | After | Speed-up | Plan after |
|---|---|---|---|---|
| Catalog: `men_jeans`, any price, sort by price | 21.95 ms (21.43–22.31) | 12.98 ms (12.66–13.20) | 1.7× | Bitmap scan on `idx_product_category_price` |
| Catalog: `men_jeans`, price 500–1000 | 11.40 ms (11.29–11.86) | 5.48 ms (5.32–5.63) | 2.1× | Bitmap scan on `idx_product_category_price` |
| Catalog: `saree`, price 500–1000, discount ≥ 50% | 9.54 ms (9.33–9.99) | 3.53 ms (3.49–3.60) | 2.7× | Bitmap scan on `idx_product_category_price` |
| Search `jeans`: original query | 76.40 ms (72.46–80.85) | 78.91 ms (75.05–83.26) | none | Sequential scan; indexes unused |
| Search `silk`: original query | 94.42 ms (90.85–99.02) | 95.88 ms (91.15–104.22) | none | Sequential scan; indexes unused |
| Search `jeans`: rewritten query | 63.22 ms (59.23–66.20) | **10.69 ms** (10.57–10.86) | 5.9× | `BitmapOr` of 3 trigram indexes and the category index |
| Search `silk`: rewritten query | 30.23 ms (29.09–32.24) | **1.82 ms** (1.80–1.86) | 16.6× | `BitmapOr` of 3 trigram indexes and the category index |

End to end, the search change (rewritten query plus indexes) went from **76.40 → 10.69 ms** for `jeans` and **94.42 → 1.82 ms** for `silk`.

### What changed and why

- **Catalog.** Before, PostgreSQL scanned all ~100k products and hash-joined them to categories. The composite index `(category_id, discounted_price)` lets it read only the rows of the matching category and price range.
  The result is still sorted separately, because the query sorts with `ORDER BY CASE WHEN :sort = ... END` (so the same query can sort ascending or descending), and no index can serve that.
- **Search.** The original query was `LOWER(col) LIKE '%term%'` on three product columns, plus `OR` on the category name through a join. A normal B-tree can't serve a leading `%`, so V2 adds `pg_trgm` trigram GIN indexes.
  With the `OR` spanning two tables, PostgreSQL still could not use them (the "after" result for the original query is unchanged).
  The search now looks up the matching category ids first (categories has 16 rows), then queries `product` with `category_id IN (...)`. All four conditions are then on one table, and PostgreSQL combines the indexes with `BitmapOr`.
  The rewritten query returns exactly the same products as the original. This was checked on 9 search terms (including upper case, and a term with no matches) against the 499-product catalog.
- **Foreign keys.** MySQL/InnoDB indexes foreign-key columns automatically; PostgreSQL does not. V2 indexes every foreign key (orders by user, order items by order, reviews and ratings by product, cart items by cart, and so on).
  Without this, the move to PostgreSQL would have made those lookups full scans. These indexes were not benchmarked separately, because the benchmark database has no orders, reviews or carts.

### Limits

- **The API does more than this query.** `GET /api/products` loads every product matching the category and price, then filters colour, size and stock in Java, then pages the list in memory. It also loads each product's sizes, ratings and reviews one product at a time. At 100k products that Java work will outweigh the SQL time above. Moving filtering and paging into the database is part of the pagination work (upgrade Step 5).
- **Search has no paging.** Search returns every match: 23,718 products for `jeans` in the benchmark data, 118 in the real catalog.
- **Numbers are from one laptop.** They are useful as a before/after comparison on the same machine, not as absolute production latencies.

### Reproduce

```bash
# 1. a database with the seeded 499 products (start the app once on an empty DB), then copy it:
createdb -T ecommerce ecommerce_bench        # or pg_dump | psql
# 2. remove the V2 indexes, add synthetic rows, measure "before"
psql -d ecommerce_bench -f scripts/benchmark/drop_v2_indexes.sql
psql -d ecommerce_bench -f scripts/benchmark/generate_products.sql
python3 -I scripts/benchmark/explain_bench.py before
# 3. apply the indexes and measure "after"
psql -d ecommerce_bench -f src/main/resources/db/migration/V2__indexes.sql
psql -d ecommerce_bench -c "VACUUM ANALYZE"
python3 -I scripts/benchmark/explain_bench.py after
```

`explain_bench.py` runs `psql` inside a Docker container. Set `BENCH_CONTAINER`, `BENCH_DB` and `BENCH_USER` if yours differ.

A second run following these steps on a fresh copy gave medians within 10% of the table above (for example catalog `men_jeans` 23.20 → 13.08 ms, search `silk` 30.81 → 1.80 ms).
