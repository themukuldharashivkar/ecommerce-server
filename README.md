# Bazaarly API

Spring Boot 3 REST API for the Bazaarly clothing store: catalog, cart, orders, Razorpay (test mode) payments, reviews and an admin panel.
PostgreSQL with Flyway migrations, stateless JWT auth.

> A full README (architecture, decisions, live demo) is coming. For now see [docs/CURRENT_STATE.md](docs/CURRENT_STATE.md).

## Run locally

### With Docker (one command)

Needs Docker. From this folder:

```bash
docker compose up --build
```

| | URL |
|---|---|
| API | http://localhost:5454 |
| Swagger UI | http://localhost:5454/swagger-ui.html |
| Health | http://localhost:5454/actuator/health |
| Mail inbox (order confirmations) | http://localhost:8025 |

This starts the API with PostgreSQL 16, Redis and Mailpit. On first start Flyway creates the schema and the 499 demo products are loaded. Data lives in the `bazaarly_postgres-data` volume, so it survives restarts (`docker compose down -v` removes it).

To run the React storefront too (http://localhost:3000), check out `ecommerce-frontend` next to this folder and add the profile:

```bash
docker compose --profile storefront up --build
```

Without a `.env` file the stack uses **local-only** defaults:
- admin login `admin@bazaarly.local` / `admin-local-only-123`
- a fixed JWT secret
- placeholder Razorpay keys, so creating a payment link fails until you set `RAZORPAY_KEY_ID`/`RAZORPAY_KEY_SECRET`

Copy `.env.example` to `.env` to override any of them. Never deploy with the defaults.

### Without Docker

Needs Java 17+ and PostgreSQL 14+.

```bash
cp .env.example .env     # set DB_PASSWORD, ADMIN_EMAIL/ADMIN_PASSWORD, Razorpay test keys
./mvnw spring-boot:run   # http://localhost:5454, dev profile (in-memory cache, emails logged)
```

## Tests

```bash
./mvnw verify
```

Needs Docker running: integration tests start a throwaway PostgreSQL 16 container with [Testcontainers](https://testcontainers.com/), and the schema is built by the real Flyway migrations (no H2). Razorpay is never called; the `PaymentGateway` interface is mocked.

| Suite | Where | What |
|---|---|---|
| Unit (JUnit 5 + Mockito) | `src/test/java/com/bazaarly/unit` | cart, cart items, orders, payment creation and confirmation, product filtering and search, JWT, roles, admin bootstrap |
| Integration (Testcontainers) | `src/test/java/com/bazaarly/integration` | signup/signin and JWT access (valid, expired, forged, malformed), admin-only endpoints (normal user gets 403), cart → order → payment flow, catalog and search queries on PostgreSQL, error response shape and status codes, Redis cache hits and evictions (real Redis container), async order-confirmation job: fast response, one email under retries and concurrency, retries after failures, request validation, pagination limits, rate limiting, health, OpenAPI and CORS |

Coverage report (JaCoCo): `target/site/jacoco/index.html` after `mvnw verify`.

Run one class: `./mvnw test -Dtest=CheckoutFlowIntegrationTest`.
