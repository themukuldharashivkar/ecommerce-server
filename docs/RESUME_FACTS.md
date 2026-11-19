# Resume facts

Only statements that are true for this repository, each with where it can be checked. All numbers are from real runs on 17–19 Nov 2026. "Laptop" means an Apple M5 MacBook (16 GB) over loopback: those are before/after comparisons, not production latencies.

## Facts

| Area | Fact | Source |
|---|---|---|
| Stack | Spring Boot 3.0 / Java 17 REST API; React 18 storefront; PostgreSQL 16; Redis; Razorpay (test mode); Docker | `pom.xml`, `docker-compose.yml`, frontend `package.json` |
| Database | MySQL → PostgreSQL with **7 Flyway migrations** (V1–V7); Hibernate validates the schema (`ddl-auto=validate`) | `src/main/resources/db/migration` |
| Indexes | Search (100,299 products): `jeans` **76.4 → 10.7 ms**, `silk` **94.4 → 1.8 ms** (query rewrite plus `pg_trgm` GIN indexes); catalog filter 1.7–2.7× faster (composite index) | `docs/PERFORMANCE.md` §1 (PostgreSQL `EXPLAIN ANALYZE`, median of 9) |
| Paging in SQL | Catalog with filters (100,299 products), HTTP p50: **756.8 → 23.0 ms** (32.9×); without filters **68.1 → 6.3 ms** (10.8×) | `docs/PERFORMANCE.md` §3 |
| Redis cache | Catalog endpoint at concurrency 10: p95 **20.42 → 0.78 ms** (26.2×), **639 → 16,424 req/s**; cache hits on the 499-product catalog; evicted on product, review, rating and stock changes | `docs/PERFORMANCE.md` §2; `CachingIntegrationTest` |
| Tests | **199 test methods, 313 executions**: 76 unit (JUnit 5, Mockito) and 237 integration (Spring Boot + Testcontainers PostgreSQL/Redis), including 6 concurrency tests × 20 repetitions; 0 failures | `./mvnw verify`, 19 Nov 2026 |
| Coverage | JaCoCo line coverage: service layer (`service` + `payment`) **86.3%**, `service` alone 94.7%, whole codebase 80.9% (63.8% of branches) | `target/site/jacoco` after `./mvnw verify` |
| Concurrency | **50 threads** ordering the last **10 units**: exactly **10 succeed, 40 get 409, final stock 0**, never negative. Passed 20 × 20 repetitions across 3 local executions and in CI. | `StockConcurrencyIntegrationTest`, `docs/CONCURRENCY.md` |
| Before the fix | Same 50-buyer test on an older commit: **19–42 orders accepted for 10 units**, plus 500s from deadlocks; a read-check-write version lost updates (size stock 6–9 after selling out) | `docs/CONCURRENCY.md` |
| Concurrency bugs fixed | Stale entity under a row lock (stock released up to 3×; an expired order marked paid), an unlocked delete, and two deadlock patterns. Each was reproduced by a failing test committed first (`37898e3`), then fixed (`9244a2a`). | `docs/CONCURRENCY.md` |
| DB constraint | `CHECK (quantity >= 0)` on product and size stock | `V7__stock_constraints.sql` |
| Load test | 500 shoppers, 50 in flight, 200 units of one product: **exactly 200 placed, 300 refused (409), 0 errors, stock 0**, in 3 of 3 runs; 475–545 req/s; placed orders p95 171–205 ms (laptop) | `docs/PERFORMANCE.md` §4 |
| Payments | Server-side verification with Razorpay (captured, full amount, this order's payment link); idempotent confirmation (row lock); automatic refund of payments that arrive after expiry; refunds on cancel | `PaymentService`; `docs/DEPLOYMENT.md` (live test payment) |
| Async job | Order confirmation through a transactional outbox; `@Async` after commit; scheduled retry sweeper; exactly one email under retries and concurrency (tested) | `notification/*`, `OrderConfirmationIntegrationTest` |
| Hardening | Bean validation, one JSON error format, 409 for out-of-stock, pagination with `X-Total-Count`, per-IP rate limits (Bucket4j: sign-in 10/min, sign-up 5/10 min), Swagger UI, Actuator health | `README.md`, `GlobleException`, `AuthRateLimitFilter` |
| Security | JWT auth with 3 roles (user, admin, read-only admin); gitleaks secret scan over the full history in CI | `AppConfig`, `docs/SECURITY.md` |
| CI | GitHub Actions on every push and PR. Backend: **secret scan → build and all tests (`mvn verify`) → Docker image** (pushed to GHCR on `main`). Frontend: secret scan → lint and build → Docker image. | `.github/workflows/ci.yml` in both repos |
| Docker | Multi-stage image (non-root, healthcheck); `docker compose up --build` starts API, PostgreSQL, Redis and Mailpit (storefront optional) | `Dockerfile`, `docker-compose.yml` |
| Deployment | API on **Render** (Docker, Singapore) with Render PostgreSQL, **Upstash** Redis, storefront on **Vercel**. Live API: https://bazaarly-api.onrender.com. A real end-to-end test-mode payment was verified on the live site. | `render.yaml`, `docs/DEPLOYMENT.md` |

## Ready-to-use bullets (all numbers above)

- Built and deployed a full-stack e-commerce app (Spring Boot 3, React, PostgreSQL, Redis) on Render, Upstash and Vercel, with a GitHub Actions pipeline that runs a secret scan, 313 tests and a Docker build on every push.
- Migrated the schema from MySQL to PostgreSQL with 7 Flyway migrations, and rewrote search to use trigram indexes, cutting search on 100k products from 76 ms to 11 ms.
- Moved catalog filtering and paging from Java into SQL, cutting a filtered catalog request on 100k products from 757 ms to 23 ms (p50).
- Cached the catalog in Redis with eviction on every product, review and stock change, reducing p95 latency from 20.4 ms to 0.8 ms and raising throughput from 639 to 16,424 req/s.
- Wrote 199 JUnit, Mockito and Testcontainers tests (86% service-layer line coverage) covering auth, cart, orders, payments, caching and concurrency.
- Prevented overselling with an atomic conditional stock update, a database `CHECK` constraint and a global lock order: 50 concurrent orders for 10 units give exactly 10 successes and zero negative stock, where an older version accepted up to 42.
- Found and fixed four concurrency bugs with failing-first tests, including Hibernate returning a stale entity under a row lock (stock released up to 3 times) and two deadlock patterns.
- Made payment confirmation idempotent and server-verified with Razorpay, with automatic refunds for late payments and a transactional outbox for confirmation emails.

## Don't claim (not true, or not yet)

- **A public storefront demo link.** The upgraded storefront is only on a Vercel preview behind Vercel's login. The production domain (`urbanwear-nu.vercel.app`) still serves the old version, which talks to the old Railway API. This is true only after the go-live steps in `docs/DEPLOYMENT.md`.
- **"Production" traffic or users.** All numbers are from benchmarks and tests; payments are in Razorpay test mode.
- **Guarded `UPDATE ... WHERE status = ...` transitions.** Status changes use row locks (`SELECT ... FOR UPDATE`), which are equivalent. Say "row-locked status transitions".
- **Optimistic locking or an alternative implementation benchmark.** They are compared in writing only.
- **The Flyway migration made queries faster.** The speed-ups come from the indexes and query rewrites (V2 and code), not the MySQL → PostgreSQL move itself.
- **Frontend tests.** The storefront has none; its CI lints and builds.
- **The backend code is public.** `ecommerce-server` is a private repository until you make it public.
