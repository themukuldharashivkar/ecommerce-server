# Bazaarly API

Spring Boot 3 REST API for the Bazaarly clothing store: catalog, cart, orders, Razorpay (test mode) payments, reviews and an admin panel.
PostgreSQL with Flyway migrations, stateless JWT auth.

> A full README (architecture, decisions, live demo) is coming. For now see [docs/CURRENT_STATE.md](docs/CURRENT_STATE.md).

## Run locally

Needs Java 17+ and PostgreSQL. With Docker:

```bash
docker run -d --name bazaarly-postgres -e POSTGRES_USER=bazaarly -e POSTGRES_PASSWORD=<password> \
  -e POSTGRES_DB=ecommerce -p 5432:5432 postgres:16-alpine

cp .env.example .env     # set DB_PASSWORD, ADMIN_EMAIL/ADMIN_PASSWORD, Razorpay test keys
                         # optional: CACHE_TYPE=redis with a local Redis (default is an in-memory cache)
./mvnw spring-boot:run   # http://localhost:5454
```

On first start Flyway creates the schema, the 499 demo products are loaded, and the admin account from `ADMIN_EMAIL`/`ADMIN_PASSWORD` is created.

## Tests

```bash
./mvnw verify
```

Needs Docker running: integration tests start a throwaway PostgreSQL 16 container with [Testcontainers](https://testcontainers.com/), and the schema is built by the real Flyway migrations (no H2). Razorpay is never called; the `PaymentGateway` interface is mocked.

| Suite | Where | What |
|---|---|---|
| Unit (JUnit 5 + Mockito) | `src/test/java/com/bazaarly/unit` | cart, cart items, orders, payment creation and confirmation, product filtering and search, JWT, roles, admin bootstrap |
| Integration (Testcontainers) | `src/test/java/com/bazaarly/integration` | signup/signin and JWT access (valid, expired, forged, malformed), admin-only endpoints (normal user gets 403), cart → order → payment flow, catalog and search queries on PostgreSQL, error response shape and status codes, Redis cache hits and evictions (real Redis container), async order-confirmation job: fast response, one email under retries and concurrency, retries after failures |

Coverage report (JaCoCo): `target/site/jacoco/index.html` after `mvnw verify`.

Run one class: `./mvnw test -Dtest=CheckoutFlowIntegrationTest`.
