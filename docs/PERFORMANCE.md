# Performance measurements

Every number here comes from a real run. Each section states how it was measured.

1. [Database indexes](#1-database-indexes-flyway-v2__indexessql-measured-2026-11-17)
2. [Redis cache for the catalog endpoints](#2-redis-cache-for-the-catalog-endpoints-upgrade-step-3-measured-2026-11-18)
3. [Catalog filtering and paging in the database](#3-catalog-filtering-and-paging-in-the-database-upgrade-step-5-measured-2026-11-18)
4. [Orders for one hot product](#4-orders-for-one-hot-product-revised-plan-step-3-measured-2026-11-19)

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

- **The API used to do more than this query.** At the time, `GET /api/products` loaded every product matching the category and price, then filtered and paged in Java. Since Step 5 the database does the filtering and paging ([section 3](#3-catalog-filtering-and-paging-in-the-database-upgrade-step-5-measured-2026-11-18)), so the catalog SQL is now a different query (with `LIMIT` and a count). The indexes measured here still serve it.
- **Search had no paging** when this was measured: it returned every match (23,718 products for `jeans` in the benchmark data). Since Step 5 it returns 20 per page (at most 50), with the total in `X-Total-Count`.
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

## 2. Redis cache for the catalog endpoints (upgrade Step 3), measured 2026-11-18

### What was measured

End-to-end HTTP latency of the two cached endpoints, measured with ApacheBench (`ab -k`, keep-alive):

- **catalog**: `GET /api/products?category=men_jeans&sort=price_low&pageNumber=0&pageSize=12&...`, the request the shop page sends
- **detail**: `GET /api/products/id/448`, a product with a review and a rating

Each case had 300 warm-up requests, then 3 runs of 2,000 requests, at concurrency 1 and 10. The table shows the **median of the 3 runs**.
All 24,000 measured requests succeeded (0 failed, 0 non-2xx).

The only difference between the two setups is `CACHE_TYPE`: `none` (no cache) or `redis`. With Redis, every request after the first is a cache hit, which is the best case.

### Environment

- App: the `prod` profile (SQL logging off), run as a jar on OpenJDK 21.0.13
- Database: PostgreSQL 16.15 with the real catalog (499 products)
- Cache: Redis 7.4.12 in Docker
- Machine: Apple M5 MacBook, 16 GB RAM
- `ab` ran on the same machine over loopback, so the numbers have no network latency. In a deployment, each request also pays the network round trip to the client, and each cache hit pays a round trip to Redis.

### Results

| Endpoint | Concurrency | No cache p50 / p95 | Redis p50 / p95 | p95 speed-up | Throughput (req/s) |
|---|---|---|---|---|---|
| catalog | 1 | 4.89 / 6.53 ms | 0.21 / 0.32 ms | 20.4× | 194 → 4,356 |
| catalog | 10 | 14.72 / 20.42 ms | 0.59 / 0.78 ms | 26.2× | 639 → 16,424 |
| detail | 1 | 0.77 / 0.92 ms | 0.20 / 0.24 ms | 3.8× | 1,258 → 4,936 |
| detail | 10 | 1.97 / 2.59 ms | 0.45 / 0.57 ms | 4.5× | 4,854 → 21,859 |

p99 at concurrency 10: catalog 26.88 → 1.19 ms, detail 2.92 → 0.68 ms.

### Why the catalog gains the most

Without a cache, a catalog request loads every product in the category, filters and pages them in Java, loads each product's sizes, ratings and reviews one product at a time, then serialises the page to JSON.
A cache hit skips all of that: it returns the stored JSON string straight from Redis. A product detail page is a single primary-key lookup, so there is less work to save.

### Limits

- **These are hit latencies.** A miss costs the same as no cache, plus one Redis write. The real hit rate depends on traffic: catalog keys combine category, filters, sort and page, so less common filter combinations will often miss. The home page and default shop pages repeat the same few requests, so they should hit often.
- **Small dataset.** The numbers come from the real 499-product catalog. With more products the uncached catalog gets slower (see section 1, Limits).
- **One machine, loopback.** Compare the two columns with each other; don't read them as production latencies.

### Correctness checks

- Responses with the cache match the responses from before caching for 7 captured requests (4 catalog pages, 2 product pages, 1 unknown product), including status code and `Content-Type`.
  The comparison sorts each product's `sizes`, because their order already changed between requests before this work (`Product.sizes` is a `HashSet`), and ignores error timestamps.
- A cache hit returns the same bytes as the miss that stored it. Without a cache, the same request returned different bytes from one request to the next (the `sizes` order again).
- `CachingIntegrationTest` (real Redis via Testcontainers) proves hits and evictions:
  - it changes the database behind the application's back, and the API keeps serving the cached data
  - an admin update, a new product, a review, a rating or a delete makes the fresh data visible on the next request

### Reproduce

```bash
docker run -d --name bazaarly-redis -p 6379:6379 redis:7-alpine
./mvnw -DskipTests package
# with the variables from .env exported:
SPRING_PROFILES_ACTIVE=prod CACHE_TYPE=none  java -jar target/e-commerce-0.0.1-SNAPSHOT.jar &
scripts/benchmark/cache_bench.sh no-cache http://localhost:5454 <product id>
# stop the app, then:
SPRING_PROFILES_ACTIVE=prod CACHE_TYPE=redis java -jar target/e-commerce-0.0.1-SNAPSHOT.jar &
scripts/benchmark/cache_bench.sh redis http://localhost:5454 <product id>
```

## 3. Catalog filtering and paging in the database (upgrade Step 5), measured 2026-11-18

### What changed

Before, `GET /api/products` worked like this:
1. load every product matching the category and price range
2. filter colour, size and stock in Java, loading each product's sizes one product at a time
3. cut out the requested page in memory

Now all filters, the sort, `LIMIT`/`OFFSET` and the total count run in PostgreSQL (a JPA `Specification`). Lazy collections load in batches of 50 (`hibernate.default_batch_fetch_size`).

### How it was measured

- Data: a copy of the dev database plus `scripts/benchmark/generate_products.sql`, giving **100,299 products** (synthetic data, see section 1)
- Cache: off (`CACHE_TYPE=none`), so every request hits the database. With the Redis cache on, repeated requests are served from Redis (section 2).
- Code: two jars built from this repository, run one after the other with the same settings (`prod` profile, `-Xmx1g`, OpenJDK 21, PostgreSQL 16, Apple M5):
  - **before**: the Step 4 commit `3a40548`
  - **after**: the Step 5 code
- Load: ApacheBench, concurrency 1, 10 warm-up requests, then 3 runs of 50 requests. The table shows the median of the 3 runs. 0 failed requests.
- Both versions returned the same `totalElements` and the same first products for both requests.

| Request (`category=men_jeans`, 12 per page, sort `price_low`) | Matching products | Before p50 / p95 | After p50 / p95 | p50 speed-up |
|---|---|---|---|---|
| No other filters | 15,678 | 68.13 / 80.60 ms | 6.30 / 9.63 ms | 10.8× |
| `color=Black,Blue&size=M,L&minPrice=300&maxPrice=1500&minDiscount=20&stock=in_stock` | 6,776 | 756.79 / 844.99 ms | 23.03 / 26.76 ms | 32.9× |

The filtered request gained the most. To apply the size filter, the old code loaded the sizes of every candidate product, one query per product. Now that check is an `EXISTS` subquery.

On the real 499-product catalog the same requests return the same products in the same order as before the change. This was checked against responses captured before Step 3; the only differences were reviews and ratings added by test runs in between.

## 4. Orders for one hot product (revised plan Step 3), measured 2026-11-19

### What was measured

`POST /api/orders/` when many shoppers order the same product at once. This exercises stock reservation (a conditional `UPDATE` on the size row and the product row, see [CONCURRENCY.md](CONCURRENCY.md)).

- 500 different shoppers (each signed up, with 1 × size M of the product in their bag), **50 requests in flight**, **200 units in stock**, so 300 orders must be refused.
- A warm-up run first (200 orders, 1,000 in stock), then 3 measured runs, each with a new product and new shoppers.
- Latency is per request, measured by the client from send to full response.

### Environment

- App: the final Step 3 code, run as a jar (`prod` profile, `-Xmx1g`, OpenJDK 21.0.13), in-memory cache, mail off
- Database: a fresh PostgreSQL 16 container (`postgres:16-alpine`, default settings), with Flyway V1–V7
- Client: `scripts/benchmark/hot_product_load.mjs` (Node 26, `fetch`), on the same machine over loopback
- Machine: Apple M5 MacBook, 16 GB RAM

### Results

| | Run 1 | Run 2 | Run 3 |
|---|---|---|---|
| Orders placed (200) | 200 | 200 | 200 |
| Refused (409) | 300 | 300 | 300 |
| Any other status | 0 | 0 | 0 |
| Final stock, product / size M | 0 / 0 | 0 / 0 | 0 / 0 |
| Placed: p50 / p95 / p99 | 156.5 / 204.9 / 222.7 ms | 146.3 / 192.0 / 235.0 ms | 128.8 / 171.2 / 223.5 ms |
| Refused: p50 / p95 / p99 | 60.7 / 148.4 / 168.2 ms | 75.7 / 129.3 / 148.9 ms | 60.3 / 113.4 / 155.8 ms |
| All requests: p50 / p95 | 80.3 / 185.0 ms | 84.1 / 174.8 ms | 70.4 / 159.7 ms |
| Throughput | 474.6 req/s | 477.2 req/s | 544.5 req/s |

- The database agreed: exactly 200 orders per product. The API log had no errors and no `deadlock detected`.
- An earlier session with the same script, on the code just before the last lock-order change (that change only matters for orders with several sizes of one product), gave the same counts. Its latencies were lower (placed p50 114–126 ms, refused p50 42–44 ms, 644–666 req/s). That shows how much the numbers vary from run to run on this laptop.

### Why refusals are faster

A refused order stops at the first `UPDATE` that matches 0 rows, and the transaction rolls back. A placed order also writes the order, its items and the address. Every order for this product waits its turn on the same two rows (size M and the product), so latency grows with the number of requests in flight.

### Limits

- One laptop, loopback, a single hot product: this measures contention on one row, not general checkout throughput.
- Placing an order doesn't call Razorpay (the payment link is created in a separate request), so no external latency is included.

### Reproduce

```bash
# a throwaway database and the API (no Razorpay needed for placing orders)
docker run -d --rm --name bazaarly-loadtest-db -e POSTGRES_DB=ecommerce -e POSTGRES_USER=bazaarly \
  -e POSTGRES_PASSWORD=loadtest -p 55432:5432 postgres:16-alpine
./mvnw -DskipTests package
SPRING_PROFILES_ACTIVE=prod SERVER_PORT=5460 DB_PORT=55432 DB_PASSWORD=loadtest SEED_ENABLED=false \
  CACHE_TYPE=simple MAIL_ENABLED=false RATE_LIMIT_SIGNUP_REQUESTS=1000000 RATE_LIMIT_SIGNIN_REQUESTS=1000000 \
  JWT_SECRET=<32+ random characters> RAZORPAY_KEY_ID=x RAZORPAY_KEY_SECRET=x FRONTEND_URL=http://localhost:3000 \
  ADMIN_EMAIL=admin@loadtest.local ADMIN_PASSWORD=admin-loadtest-123 java -Xmx1g -jar target/e-commerce-0.0.1-SNAPSHOT.jar &
API=http://localhost:5460 node scripts/benchmark/hot_product_load.mjs
docker rm -f bazaarly-loadtest-db
```

