# Bazaarly: current state

Snapshot taken at the start of the upgrade (branch `upgrade/production-ready`, 2026-11-17).
It describes what exists today, not what is planned. Update it as each upgrade step lands.

## Repositories

There are two separate git repositories:

| Repo | Stack | Deployed at |
|---|---|---|
| `ecommerce-server` (this repo) | Spring Boot 3.0.6, Java 17, Spring Security, Spring Data JPA, MySQL, jjwt 0.11.1, razorpay-java 1.4.3 | Railway |
| `ecommerce-frontend` | React 18 (Create React App), Redux + redux-thunk, Axios, Tailwind CSS, MUI | Vercel |

## Backend layout (`com.bazaarly`)

| Package | Contents |
|---|---|
| `config` | Security filter chain and CORS (`AppConfig`), JWT creation and validation, `DataSeeder` |
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

The schema is currently created by Hibernate (`ddl-auto=update`). There are no migrations yet.

### Endpoints (31)

Auth is a JWT in the `Authorization: Bearer <token>` header. "Public" means it works without a token.

| Controller | Method and path | Access |
|---|---|---|
| Home | `GET /` | public |
| Auth | `POST /auth/signup`, `POST /auth/signin` | public |
| User products | `GET /api/products` (filters: category, color, size, minPrice, maxPrice, minDiscount, sort, stock, pageNumber, pageSize; all required) | public |
| | `GET /api/products/id/{productId}` | public |
| | `GET /api/products/search?q=` | public |
| User | `GET /api/users/profile` | logged in |
| Cart | `GET /api/cart/`, `PUT /api/cart/add` | logged in |
| Cart items | `PUT /api/cart_items/{id}`, `DELETE /api/cart_items/{id}` | logged in |
| Orders | `POST /api/orders/`, `GET /api/orders/user`, `GET /api/orders/{orderId}` | logged in |
| Payments | `POST /api/payments/{orderId}` (creates a Razorpay Payment Link), `GET /api/payments?payment_id=&order_id=` (confirms payment server-side) | logged in |
| Reviews | `POST /api/reviews/create`, `GET /api/reviews/product/{productId}` | logged in |
| Ratings | `POST /api/ratings/create`, `GET /api/ratings/product/{productId}` | logged in |
| Admin products | `POST /api/admin/products/`, `POST /api/admin/products/creates`, `GET /api/admin/products/all`, `PUT /api/admin/products/{id}/update`, `DELETE /api/admin/products/{id}/delete` | logged in (see known issues) |
| Admin orders | `GET /api/admin/orders/`, `PUT /api/admin/orders/{id}/confirmed`, `.../ship`, `.../deliver`, `.../cancel`, `DELETE .../{id}/delete` | logged in (see known issues) |

### Seed data

`DataSeeder` loads `src/main/resources/seed/products.json` (499 products) on startup when the product table is empty.

## Configuration and profiles

All environment-specific values come from environment variables. `.env.example` lists every one.

| Profile | When | Behaviour |
|---|---|---|
| `dev` (default) | local development | Loads an optional, git-ignored `.env` from the project root. `ddl-auto=update`, SQL logging on. Has a dev-only JWT secret fallback. |
| `prod` | deployment (`SPRING_PROFILES_ACTIVE=prod`) | No secret defaults: startup fails if `JWT_SECRET`, `RAZORPAY_KEY_ID` or `RAZORPAY_KEY_SECRET` is missing, or if the JWT secret is shorter than 32 bytes. SQL logging off. |
| `test` | automated tests | Dummy secrets; uses a separate `ecommerce_test` database with `create-drop`. |

Variables: `PORT`, `DB_URL` or `DB_HOST`/`DB_PORT`/`DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `JWT_EXPIRATION_MS`, `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `CORS_ALLOWED_ORIGINS` (comma-separated), `FRONTEND_URL` (Razorpay return URL).

The frontend reads `REACT_APP_API_URL`, falling back to `http://localhost:5454` in development and the Railway URL in production builds (`src/config/api.js`).

## How to run locally

Backend (needs Java 17+ and a MySQL 8 database):

```bash
cd ecommerce-server
cp .env.example .env        # fill in DB_PASSWORD, JWT_SECRET, Razorpay test keys
./mvnw spring-boot:run      # http://localhost:5454, dev profile
```

Throwaway MySQL with Docker:

```bash
docker run -d --name bazaarly-mysql -e MYSQL_ROOT_PASSWORD=<password> -e MYSQL_DATABASE=ecommerce -p 3306:3306 mysql:8.0
```

Frontend:

```bash
cd ecommerce-frontend
npm install
npm start                   # http://localhost:3000
```

## Tests

1 test (`ECommerceApplicationTests.contextLoads`, Spring context starts on the `test` profile). It needs a running MySQL. No coverage measurement yet.

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

### Known issues, not yet fixed

| Issue | Planned in |
|---|---|
| **No role enforcement.** Any logged-in user can call `/api/admin/**` (verified: a normal user gets 200). `UserRole` exists, but signup never sets a role and the JWT carries no authorities. | Step 5 (and Step 2 tests) |
| Old secrets (Razorpay test key and secret, DB password, JWT secret) are still in git history and on GitHub. They should be rotated. | Step 10 |
| Payment confirmation does not check that the Razorpay payment belongs to the given order, or that the order belongs to the caller. | Step 4 |
| The catalog endpoint requires every filter parameter (400 if one is missing). | Step 5 |
| Reviews and ratings `GET` endpoints require login, although product detail (which includes them) is public. | Step 5 |
| No validation on most request DTOs. Error responses use a single generic shape. | Step 5 |
| Many `System.out.println` calls instead of a logger. | Step 5 |
| The Java field `PaymentDetails.razorpayPaymentId` contains an invisible zero-width space in its name (the generated column is a clean `razorpay_payment_id`). | Step 1 |
| `spring.security` HTTP Basic and form login are still enabled alongside JWT. | Step 5 |
