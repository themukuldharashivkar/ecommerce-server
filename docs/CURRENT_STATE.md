# Bazaarly: current state

Snapshot taken at the start of the upgrade (branch `upgrade/production-ready`, 2026-11-17).
It describes what exists today, not what is planned. Update it as each upgrade step lands.

## Repositories

There are two separate git repositories:

| Repo | Stack | Deployed at |
|---|---|---|
| `ecommerce-server` (this repo) | Spring Boot 3.0.6, Java 17, Spring Security, Spring Data JPA, PostgreSQL 16 + Flyway 9.5, Redis (Spring Cache), jjwt 0.11.1, razorpay-java 1.4.3 | Railway (still the MySQL build from `main`) |
| `ecommerce-frontend` | React 18 (Create React App), Redux + redux-thunk, Axios, Tailwind CSS, MUI | Vercel |

## Backend layout (`com.bazaarly`)

| Package | Contents |
|---|---|
| `config` | Security filter chain and CORS (`AppConfig`), JWT creation and validation, `DataSeeder`, `AdminBootstrap` |
| `cache` | Response cache for the public catalog endpoints (`ProductResponseCache`, `CacheConfig`) |
| `payment` | `PaymentService`, `PaymentGateway` interface and its Razorpay implementation |
| `notification` | Order-confirmation outbox and background job (`OrderConfirmationJob`, `OrderNotificationStore`, SMTP or logging sender) |
| `controller` | 12 REST controllers |
| `service` | Interface + implementation per domain (user, product, cart, cart item, order, order item, rating, review, category) |
| `repository` | 10 Spring Data JPA repositories |
| `modal` | JPA entities and embeddables |
| `request` / `response` | Request and response DTOs |
| `exception` | Domain exceptions and `GlobleException` (`@ControllerAdvice`) returning `ErrorDetails` |
| `user.domain` | Enums: `OrderStatus`, `PaymentMethod`, `PaymentStatus`, `UserRole`, product enums |

### Entities

10 `@Entity` classes: `User` (table `users`), `Address`, `Cart`, `CartItem`, `Category` (`categories`), `Product`, `Order` (`orders`), `OrderItem`, `Rating` (`rating`), `Review`.

Embeddables / element collections: `PaymentDetails` (inside `Order`), `PaymentInformation` (table `payment_information`, per user), `Size` (`product_sizes`, per product).

The schema is owned by Flyway (`src/main/resources/db/migration`); Hibernate runs with `ddl-auto=validate` and only checks that the entities match it.

| Migration | Contents |
|---|---|
| `V1__init_schema.sql` | All tables, identity columns, sequences (allocation size 50) for `GenerationType.AUTO` ids, foreign keys. Enums are stored as names with `CHECK` constraints. |
| `V2__indexes.sql` | Indexes on every foreign key, `(category_id, discounted_price)` for the catalog filter, `pg_trgm` trigram indexes for search, unique `users.email` and `cart.user_id`. |
| `V3__order_notifications.sql` | `order_notification` outbox table, unique per (order, type). |
| `V4__product_sizes_unique.sql` | Each product lists a size once (unique on product and lower-cased size name). |

Enums (`UserRole`, `OrderStatus`, `PaymentMethod`, `PaymentStatus`) use `@Enumerated(EnumType.STRING)`, so reordering an enum can no longer corrupt stored data. Benchmarks are in [PERFORMANCE.md](PERFORMANCE.md).

### Endpoints (31)

Auth is a JWT in the `Authorization: Bearer <token>` header; the token carries the user's role. "Public" means it works without a token. No token or an invalid one on a protected endpoint gives 401; a valid token without the admin role on `/api/admin/**` gives 403.

Signup always creates `ROLE_USER`. The only admin is the account named by `ADMIN_EMAIL`/`ADMIN_PASSWORD`, created (or promoted, keeping its password) on startup by `AdminBootstrap`.

| Controller | Method and path | Access |
|---|---|---|
| Home | `GET /` | public |
| Auth | `POST /auth/signup`, `POST /auth/signin` (rate limited per IP) | public |
| Catalog | `GET /api/products` (all filters optional: category, color, size, minPrice, maxPrice, minDiscount, sort, stock; pageNumber, pageSize ≤ 50; returns a Page; cached) | public |
| | `GET /api/products/id/{productId}` (cached) | public |
| | `GET /api/products/search?q=` (page, size ≤ 50) | public |
| User | `GET /api/users/profile` | logged in |
| Cart | `GET /api/cart/`, `PUT /api/cart/add` | logged in |
| Cart items | `PUT /api/cart_items/{id}`, `DELETE /api/cart_items/{id}` | logged in, own items |
| Orders | `POST /api/orders/`, `GET /api/orders/user` (page, size ≤ 100), `GET /api/orders/{orderId}` | logged in, own orders |
| Payments | `POST /api/payments/{orderId}` (creates a Razorpay Payment Link), `GET /api/payments?payment_id=&order_id=` (confirms payment server-side) | logged in, own orders |
| Reviews | `POST /api/reviews/create` (logged in), `GET /api/reviews/product/{productId}` (public; page, size ≤ 100) | |
| Ratings | `POST /api/ratings/create` (logged in), `GET /api/ratings/product/{productId}` (public; page, size ≤ 100) | |
| Admin products | `POST /api/admin/products/`, `POST /api/admin/products/creates` (≤ 100 per call), `GET /api/admin/products/all` (page, size ≤ 100), `PUT /api/admin/products/{id}/update`, `DELETE /api/admin/products/{id}/delete` | `ROLE_ADMIN` |
| Admin orders | `GET /api/admin/orders/` (page, size ≤ 100), `PUT /api/admin/orders/{id}/confirmed`, `.../ship`, `.../deliver`, `.../cancel`, `DELETE .../{id}/delete` | `ROLE_ADMIN` |
| Operations | `GET /actuator/health` (status only); Swagger UI at `/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs` | public |

List endpoints other than the catalog return a JSON array, with the total number of items in the `X-Total-Count` header (exposed to the browser through CORS). Lists are newest first, except the admin product list and search, which are ordered by id.

### Errors and validation

Every error has the same body: `{"error": "...", "details": "uri=/api/...", "timestamp": "...", "fieldErrors": {...}}`. `fieldErrors` only appears for validation errors.

| Status | When |
|---|---|
| 400 | Validation failed (request DTOs use `jakarta.validation`), malformed JSON, missing or wrong-typed parameter, business rule (e.g. empty cart, email already used, payment does not match) |
| 401 | No token or an invalid one; wrong email or password (same message for an unknown email) |
| 403 | Valid token without the admin role on `/api/admin/**` |
| 404 | Unknown product, order, cart item or route. Another user's order also gets 404, so ids cannot be probed. |
| 405 / 415 | Wrong HTTP method or content type |
| 429 | Rate limit on `/auth/signin` (10 per minute per IP) or `/auth/signup` (5 per 10 minutes per IP), with `Retry-After` |
| 500 | Anything unexpected. The response gives a generic message; the details are only in the server log. |

Signup takes a `SignupRequest` (first and last name, email, password) instead of the `User` entity. Passwords must be 8–72 characters with at least one letter and one number. Emails are stored lower-cased and compared case-insensitively.

### Caching

`GET /api/products` (catalog pages) and `GET /api/products/id/{id}` are cached. The cached value is the finished JSON response, so a hit is byte-for-byte what a miss returns.

| | |
|---|---|
| Store | `CACHE_TYPE`: `redis` (prod default), `simple` in-memory (dev and test default, no Redis needed), `none` |
| Keys | `bazaarly:product::<id>`; `bazaarly:catalog::<normalised query>` (category, colour and size lower-cased, colour/size sorted) |
| TTL | catalog 10 min, product 30 min (`CACHE_CATALOG_TTL`, `CACHE_PRODUCT_TTL`); a safety net only |
| Eviction | create product → all catalog pages; update or delete product, new review or rating → that product and all catalog pages |
| Redis failure | logged and treated as a miss: requests are served from the database (checked by stopping Redis under a running app). 500 ms command timeout. |

Known limit: an eviction runs after the write. A read that started before the write can still store old data just after the eviction, and that entry stays until its TTL runs out.

Search (`/api/products/search`) is not cached. The plan also mentioned a category list, but there is no category endpoint to cache.

Measurements are in [PERFORMANCE.md](PERFORMANCE.md#2-redis-cache-for-the-catalog-endpoints-upgrade-step-3-measured-2026-11-18): catalog p95 6.53 → 0.32 ms (concurrency 1) and 20.42 → 0.78 ms (concurrency 10).

### Stock and the cart

- **Placing an order reserves stock.** In the same transaction as the order, each item's quantity is taken from both the product total and the chosen size with a conditional `UPDATE ... WHERE quantity >= n`. The database therefore refuses to oversell, even when several orders for the last item arrive together (tested with 6 concurrent buyers: one succeeds). If any item is short, nothing is reserved and the shopper gets a 400 such as "Only 1 left of ... in size L".
- **Adding to the cart** checks that the size exists and has enough left. Changing a cart line's quantity checks the same.
- **Unpaid orders expire.** The Razorpay link expires `ORDER_RESERVATION_TTL` (default 60 min) after the order was placed. A job running every minute cancels unpaid orders 5 minutes after that and gives their stock back. A payment link is refused for an order with less than 16 minutes left (Razorpay's 15-minute minimum). A captured payment for an order that has already expired is refused with an error (logged for a manual refund), because its stock may have been sold again.
- **Cancelling** (admin) gives the stock back once; shipped or delivered orders cannot be cancelled. Deleting an order that still holds stock also gives it back.
- **The cart is emptied when the order is paid**, not when it is placed, so an abandoned payment does not lose the cart. Only the paid items are removed; anything added since stays.
- Stock is part of the cached product JSON, so the product and catalog caches are evicted after each stock change commits.

### Payment confirmation and the order-confirmation job

`GET /api/payments?payment_id=&order_id=` (called by the payment-success page) does this:

1. Checks that the order belongs to the caller. Another user's order gets the same 400 as an unknown order.
2. If the order is already paid, it returns straight away (a no-op).
3. Fetches the payment from Razorpay and only accepts it if it is `captured`, belongs to this order's Razorpay order (stored when the link was created), and is for the full discounted amount.
4. In one transaction, with the order row locked (`SELECT ... FOR UPDATE`), it re-checks that the order is unpaid, marks it `PLACED`/`COMPLETED`, and inserts an `order_notification` row (`ON CONFLICT DO NOTHING`).
5. After the transaction commits, `OrderConfirmationJob` runs on a bounded pool (2 threads, queue of 100) and sends the email, so the HTTP response does not wait for it.

The job claims the row (`PENDING`/`FAILED` → `SENDING`) with a conditional `UPDATE`, then marks it `SENT` or `FAILED` (recording the error).
A scheduled sweeper (every 30 s) does three things:
- retries `FAILED` rows with linear backoff (attempts × 1 min), up to 5 attempts
- sends `PENDING` rows that no job picked up within 2 minutes (app restart, full queue)
- releases rows stuck in `SENDING` for 10 minutes

| Guarantee | How |
|---|---|
| A retried or concurrent confirmation sends one email | already-paid short-circuit, row lock and re-check, unique outbox row, conditional claim |
| Payment confirmed but email intent lost | impossible: the outbox row commits with the payment |
| Mail server down | row `FAILED` with the error, retried automatically |
| Worker crashes after sending but before marking `SENT` | the email can be sent again after the 10-minute timeout (at-least-once in that case only) |

Mail: `MAIL_ENABLED=true` sends over SMTP (`MAIL_HOST`/`MAIL_PORT`; Mailpit locally); otherwise the confirmation is only logged. Mailpit is used instead of MailHog, which is unmaintained and has no ARM image.

Verified by hand with Mailpit:
- real SMTP delivery of the email, sent once
- with Mailpit stopped, the row went `FAILED` (3 attempts, error recorded) and was delivered once Mailpit was back (attempt 4, one email)

A real Razorpay test payment needs the hosted checkout in a browser, so the full redirect → confirm → email path was tested with the gateway mocked (`OrderConfirmationIntegrationTest`).

### Seed data

`DataSeeder` loads `src/main/resources/seed/products.json` (499 products) on startup when the product table is empty. It goes through `ProductService`, so categories are created the same way as from the admin panel. Turn it off with `SEED_ENABLED=false` (the `test` profile does).

## Configuration and profiles

All environment-specific values come from environment variables. `.env.example` lists every one.

| Profile | When | Behaviour |
|---|---|---|
| `dev` (default) | local development | Loads an optional, git-ignored `.env` from the project root. SQL logging on. Has a dev-only JWT secret fallback. |
| `prod` | deployment (`SPRING_PROFILES_ACTIVE=prod`) | No secret defaults: startup fails if `JWT_SECRET`, `RAZORPAY_KEY_ID` or `RAZORPAY_KEY_SECRET` is missing, or if the JWT secret is shorter than 32 bytes. SQL logging off. |
| `test` | automated tests | Dummy secrets, seeding off; uses a separate `ecommerce_test` database migrated by Flyway. |

All profiles run Flyway on startup and validate the schema.

Variables: `ORDER_RESERVATION_TTL`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `RATE_LIMIT_SIGNIN_REQUESTS`, `RATE_LIMIT_SIGNIN_PERIOD`, `RATE_LIMIT_SIGNUP_REQUESTS`, `RATE_LIMIT_SIGNUP_PERIOD`, `FORWARD_HEADERS_STRATEGY` (prod default `framework`: client IP from `X-Forwarded-For`, so only use behind a proxy), `API_DOCS_ENABLED`, `MAIL_ENABLED`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`, `CACHE_TYPE`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_SSL`, `CACHE_CATALOG_TTL`, `CACHE_PRODUCT_TTL`, `PORT`, `DB_URL` or `DB_HOST`/`DB_PORT`/`DB_NAME` (PostgreSQL, default `localhost:5432/ecommerce`), `DB_USERNAME`, `DB_PASSWORD`, `SEED_ENABLED`, `JWT_SECRET`, `JWT_EXPIRATION_MS`, `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `CORS_ALLOWED_ORIGINS` (comma-separated), `FRONTEND_URL` (Razorpay return URL).

The frontend reads `REACT_APP_API_URL`, falling back to `http://localhost:5454` in development and the Railway URL in production builds (`src/config/api.js`).

## How to run locally

Backend (needs Java 17+ and PostgreSQL 14+ with the `pg_trgm` extension available, which is standard):

```bash
cd ecommerce-server
cp .env.example .env        # fill in DB_PASSWORD, JWT_SECRET, Razorpay test keys
./mvnw spring-boot:run      # http://localhost:5454, dev profile
```

PostgreSQL with Docker:

```bash
docker run -d --name bazaarly-postgres -e POSTGRES_USER=bazaarly -e POSTGRES_PASSWORD=<password> \
  -e POSTGRES_DB=ecommerce -p 5432:5432 postgres:16-alpine
```

On first start Flyway creates the schema and the seeder loads the 499 products.

Frontend:

```bash
cd ecommerce-frontend
npm install
npm start                   # http://localhost:3000
```

## Tests

Run with `./mvnw verify` (needs Docker; integration tests use a Testcontainers PostgreSQL 16 and the real Flyway migrations). Razorpay is mocked at the `PaymentGateway` interface and never called.

Measured on 2026-11-18 (`./mvnw clean verify`, 19 s on an Apple M5):

| | Count |
|---|---|
| Unit tests (JUnit 5 + Mockito) | 72 |
| Integration tests (Spring Boot + Testcontainers PostgreSQL; caching tests also use a Redis container) | 105 |
| **Total** | **177, all passing** |

JaCoCo line coverage: service layer (`service` + `payment` packages) **87.0%** (450/517 lines); `service` alone 92.4%. `payment` is lower because `RazorpayPaymentGateway`, the thin wrapper around the Razorpay SDK, is deliberately not exercised. Whole codebase 79.9% of lines, 60.3% of branches.

To check that the tests catch real regressions, bugs were planted temporarily: removing the admin role rule failed 2 tests, marking uncaptured payments as paid failed 2 tests, removing the cache eviction on product update failed 1 test, running the confirmation job synchronously (no `@Async`) failed 1 test, removing the idempotency guards failed 2 tests (duplicate emails), removing the `quantity >= n` stock guard failed 2 tests (overselling), removing `Size` equality failed 12 tests, and not clearing the cart on payment failed 1 test.

## Baseline check (2026-11-17)

Backend run against MySQL 8.0 in Docker, smoke-tested with curl. Frontend production build (`npm run build`) succeeds with 58 lint warnings.

Working: signup, signin (401 on a wrong password), profile, catalog with filters and paging, product detail, search, add to cart, create order, order history, Razorpay test payment link creation, review and rating creation, CORS (other origins get 403).

### Fixed during the baseline (commit "Fix 500s on rated products and reviews...")

- As soon as a product had a rating, its detail page and any catalog page containing it returned 500 (JSON recursion `Product.ratings` ↔ `Rating.product`).
- `GET /api/reviews/product/{id}` always returned 500: the query selected from `Rating` instead of `Review`.
- The BCrypt password hash was returned in profile, order and review JSON.
- Public product pages exposed each reviewer's addresses and mobile number. Reviews now expose only the reviewer's id and name.

### Fixed in Step 0

- Razorpay keys were hardcoded in `PaymentController` (and a second, different secret in `application.properties`). The JWT secret was hardcoded in `JwtConstant`, and a DB password was in `application.properties`. All of these now come from environment variables.
- The Razorpay return URL was hardcoded to `http://localhost:3000`, so payments on the live site redirected to localhost. It now uses `FRONTEND_URL`.
- Signin printed the submitted plaintext password to stdout, and the JWT filter printed every token. Both are removed.

### Fixed in Step 1 (PostgreSQL + Flyway)

- The Java field `PaymentDetails.razorpayPaymentId` had an invisible zero-width space in its name; removed.
- Search and the catalog category filter relied on MySQL's case-insensitive comparison. They now lower-case both sides, so behaviour on PostgreSQL is unchanged (`JEANS` finds the same 118 products as `jeans`).
- Nothing stopped two accounts from having the same email; there is now a unique constraint.

A 32-check smoke test of the API (catalog, search, auth, cart, order, Razorpay link, reviews, ratings, admin product and order actions) passes against PostgreSQL.

### Fixed in Step 2 (tests)

- **Role enforcement.** `/api/admin/**` now requires `ROLE_ADMIN`; signup sets `ROLE_USER`; the JWT carries the role. Admins come only from `ADMIN_EMAIL`/`ADMIN_PASSWORD`. The frontend admin panel therefore only works when signed in as that account.
- Signin with an unknown email now returns the same 401 as a wrong password, so it does not reveal which emails are registered.
- Razorpay calls moved behind a `PaymentGateway` interface (`RazorpayPaymentGateway`), with the logic in `PaymentService`, so tests can mock the provider. Requests sent to Razorpay are unchanged.

### Fixed after Step 2: payment amount

- The Razorpay payment link charged `totalPrice` (the MRP) instead of `totalDiscountedPrice`, the total shown at checkout. It now charges the discounted total, sent as a whole number of paise; orders with nothing to pay are rejected before Razorpay is called. Verified against the Razorpay test API: an order with MRP ₹1,999 and discounted total ₹599 produced a link for 59,900 paise (₹599).

### Fixed in Step 4

- Any captured Razorpay payment id could mark **any** order as paid, and anyone could confirm or create a payment link for another user's order. Confirmation now checks the caller, the Razorpay order and the amount; link creation checks the caller and refuses already-paid orders.
- Confirming the same payment twice (the payment-success page runs its effect twice under React `StrictMode`) could process it twice; it is now idempotent.

### Fixed in Step 5 (hardening)

- **Any logged-in user could read any order** (`GET /api/orders/{id}`), including the address. Only the buyer can now (others get 404).
- **A user could take over another user's saved address** by sending its id at checkout. Saved addresses can now only be reused by their owner.
- Client errors (malformed JSON, missing or wrong-typed parameters) returned 500. They are now 400/404/405/415, and 401/403 responses have JSON bodies.
- 500 responses echoed internal exception messages. They now return a generic message and log the details.
- Validation on all request bodies. Input longer than a database column now gets a clean 400 instead of a database error.
- Rate limiting on signin and signup (Bucket4j, per client IP, in memory).
- HTTP Basic, the generated `/login` form page and logout endpoint are removed. CORS is limited to the configured origins, methods and headers.
- All `System.out.println` debugging is removed; several of these printed user emails.
- The catalog filters, sorts and pages in the database (10.8–32.9× faster uncached at 100k products, [PERFORMANCE.md](PERFORMANCE.md#3-catalog-filtering-and-paging-in-the-database-upgrade-step-5-measured-2026-11-18)). Every list endpoint is paged with a maximum size.
- Catalog filters are optional, and reviews/ratings reads are public (product detail already showed them).
- Adding an item with quantity 3 stored quantity 1; the requested quantity (1–10) is now kept.
- An admin can set a product's quantity to 0 (before, 0 meant "unchanged").
- Orders from an empty cart are rejected.
- An uncaptured payment now gets "payment not completed yet" with `status: false`, instead of "your order got placed".
- `/actuator/health` (status only, everything else in Actuator unexposed) and Swagger UI with bearer auth.

### Fixed after Step 5: stock and cart

- Placing an order did not reduce stock; the cart was never emptied. See "Stock and the cart" above.
- **Hibernate rewrote `product_sizes` on every flush of a loaded product.** `Size` (an `@ElementCollection` embeddable) had no `equals`/`hashCode`, so Hibernate saw the collection as changed and deleted and re-inserted its rows from memory. With stock now changed in SQL, this duplicated sizes and undid reservations. Fixed with value equality; V4 adds a unique index so the database rejects any duplicate. This also makes the order of sizes in responses stable.

### Known issues, not yet fixed

| Issue | Planned in |
|---|---|
| Old secrets (Razorpay test key and secret, DB password, JWT secret) are still in git history and on GitHub. They should be rotated. | Step 10 |
| The live Railway deployment still runs the MySQL build; moving it to PostgreSQL means a new database (existing live users and orders are not migrated). | Step 8 |
| Rate limits are per instance (in memory). With several instances each enforces its own limit. | if scaled out |
| Admin "list all orders" and the user's order history return the first 50 by default; the frontend does not request further pages yet. | open |
| Cancelling a paid order does not refund it through Razorpay (test mode only). | open |
