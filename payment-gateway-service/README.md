# Payment Gateway Service

Spring Boot 3 / Java 17 HTTP service that processes payments through a payment gateway
(mocked in-process), handles signed gateway callbacks, and stores everything in **PostgreSQL**.

## Features
- JSON over HTTP, `Idempotency-Key` based duplicate-payment protection
- Payment state machine: `PENDING → SUCCESS | FAILED | UNKNOWN`, and `UNKNOWN → SUCCESS | FAILED`
- Mock bank driven by an optional `scenario` field: success, decline, timeout, 503, async callback, duplicate callback
- Callback handler: HMAC-SHA256 signature check, event-ID de-duplication, no overwrite of final states
- Gateway client with timeouts and retries (only where safe)
- Audit trail table (`payment_events`) and Flyway-managed schema
- Reconciliation job: asks the bank for the real status of payments stuck in `PENDING`/`UNKNOWN`
- Centralised error handling with a consistent JSON error body
- Correlation-ID logging (console + rolling file), Spring Boot Actuator + Prometheus metrics
- Swagger UI / OpenAPI
- Unit and web-layer tests (JUnit 5, Mockito, MockMvc)

## Prerequisites
- JDK 17+
- Maven 3.9+
- PostgreSQL 13+ running locally on port 5432

## Setup
1. Create the database (once):
   ```bash
   psql -U postgres -f db-setup.sql        # or: psql -U postgres -c "CREATE DATABASE paymentdb;"
   ```
2. Configure credentials if yours differ (defaults: `postgres` / `postgres`):
   ```bash
   export DB_USERNAME=postgres
   export DB_PASSWORD=5106
   export DB_URL=jdbc:postgresql://localhost:5432/payment_db
   ```
   Windows PowerShell: `$env:DB_PASSWORD="your_password"`
3. Run (Flyway creates the tables on first start):
   ```bash
   mvn spring-boot:run
   ```
4. Run tests (no database needed): `mvn test`
   End-to-end test against your real local PostgreSQL: `mvn test -Dtest=PaymentFlowIT` (uses port 18080)
5. Build a jar: `mvn clean package` then `java -jar target/payment-gateway-service-1.0.0.jar`

## URLs
| What | URL |
|---|---|
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| Health (DB included) | http://localhost:8080/actuator/health |
| Metrics | http://localhost:8080/actuator/metrics (custom: `payments.transitions`) |
| Prometheus | http://localhost:8080/actuator/prometheus |
| Logs | `logs/payment-service.log` |

## API
| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/payments` | Create a payment (header `Idempotency-Key` required) |
| GET | `/api/v1/payments/{paymentId}` | Get a payment |
| GET | `/api/v1/payments?status=&page=&size=` | List payments, paged |
| GET | `/api/v1/payments/{paymentId}/events` | Audit trail |
| POST | `/api/v1/payments/{paymentId}/reconcile` | Ask the bank for the real status of a PENDING/UNKNOWN payment |
| POST | `/api/v1/payments/callback` | Gateway webhook (header `X-Signature`) |
| POST | `/mock-gateway/payments` | Simulated external gateway |

### Mock bank scenarios (`scenario` field)
`scenario` is an optional **test hook**: it tells the mock bank how to behave (default `SUCCESS`). A real client never
chooses the outcome; in production the field is removed and the bank decides.

| scenario | Mock bank behaviour | Resulting payment |
|---|---|---|
| `SUCCESS` (default) | Approves immediately | `SUCCESS` |
| `DECLINE` | Declines | `FAILED` with `failureReason` |
| `TIMEOUT` | Stalls past the read timeout, later sends a callback | `UNKNOWN`, then `SUCCESS` after ~6 s |
| `UNAVAILABLE` | Returns 503 (retried 3x) | `FAILED` |
| `ASYNC_SUCCESS` | Returns 202, then calls back | `PENDING`, then `SUCCESS` after ~1.5 s |
| `DUPLICATE_CALLBACK` | Returns 202, then sends the same callback twice | `SUCCESS`; 2nd callback reported as `DUPLICATE` |

### Examples
```bash
# Success
curl -i -X POST localhost:8080/api/v1/payments \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: order-1001-1' \
  -d '{"orderId":"ORD-1001","amount":1000.00,"currency":"INR"}'

# Postman: import postman/PaymentGateway.postman_collection.json

# Replay: same key + same body -> 200 + Idempotent-Replayed: true, no second payment
# Same key + different body   -> 409

# Declined
curl -s -X POST localhost:8080/api/v1/payments -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: order-1002-1' \
  -d '{"orderId":"ORD-1002","amount":500.00,"scenario":"DECLINE","currency":"INR"}'

# Timeout -> UNKNOWN, then poll until the callback resolves it
curl -s -X POST localhost:8080/api/v1/payments -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: order-1003-1' \
  -d '{"orderId":"ORD-1003","amount":250.00,"scenario":"TIMEOUT","currency":"INR"}'
curl -s localhost:8080/api/v1/payments/<paymentId>
curl -s localhost:8080/api/v1/payments/<paymentId>/events

# Ask the bank for the real status of a PENDING/UNKNOWN payment
curl -s -X POST localhost:8080/api/v1/payments/<paymentId>/reconcile

# Validation error -> 400
curl -s -X POST localhost:8080/api/v1/payments -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: bad-1' -d '{"orderId":"ORD-1","amount":-10,"currency":"INR"}'

# Unknown payment -> 404
curl -i localhost:8080/api/v1/payments/PAY-DOESNOTEXIST

# Manual signed callback (secret defaults to "mock-secret")
BODY='{"eventId":"EVT-MANUAL-1","paymentId":"<paymentId>","gatewayReference":"GW-1","status":"SUCCESS"}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "mock-secret" | awk '{print $NF}')
curl -i -X POST localhost:8080/api/v1/payments/callback -H 'Content-Type: application/json' \
  -H "X-Signature: $SIG" -d "$BODY"
```
Error body (all errors): `{timestamp, status, error, message, path, correlationId, details[]}`.
Send `X-Correlation-Id` to trace a request across log lines; one is generated if absent.

## Design
```
web (controllers, filter, exception handler)
  -> service (PaymentService, CallbackService, PaymentLifecycle, SignatureService)
       -> gateway (PaymentGateway port; HttpPaymentGateway)  --HTTP-->  mock (MockGatewayController)
       -> repository (Spring Data JPA) -> PostgreSQL (Flyway)
```
- **Payment row is committed before the gateway call.** No gateway failure can lose a record; the
  gateway call runs outside any DB transaction so no connection/locks are held during network I/O.
- **Idempotency:** unique `idempotency_key` column + request fingerprint. Concurrent duplicates hit
  the unique constraint and resolve to the winner's payment.
- **Timeouts:** a read timeout means "outcome unknown", so the payment becomes `UNKNOWN` (never
  `FAILED`) and is not blindly re-submitted; a callback resolves it. Connection errors and 5xx are
  retried because the paymentId is sent to the gateway as an `Idempotency-Key`.
- **Callbacks:** payment row is locked (`SELECT ... FOR UPDATE`), the event ID is recorded in
  `processed_callbacks` (PK), and the state machine refuses changes to `SUCCESS`/`FAILED`.
  Duplicate or conflicting callbacks return 200 with `DUPLICATE`/`IGNORED` so the gateway stops
  retrying; they are audited, not applied.
- **Money** is `BigDecimal` / `NUMERIC(19,2)`.
- `PaymentGateway` is an interface: swap `HttpPaymentGateway` for a real provider adapter.

- **Reconciliation:** a scheduled job (every 30 s) and `POST /payments/{id}/reconcile` ask the bank for the real status of
  payments stuck in `PENDING`/`UNKNOWN` (lost callback, timeout, crash after saving). Bank says approved: `SUCCESS`;
  declined or no record: `FAILED`. It locks the row and skips payments a callback already resolved.

  A scheduled job and a manual reconciliation endpoint check payments that remain in `PENDING` or `UNKNOWN`. Confirmed provider outcomes are applied through the payment state machine. If the provider cannot establish the outcome, the payment remains unresolved and is retried or investigated according to the reconciliation policy. Updates are protected against concurrent callback processing.

See `ASSESSMENT.md` (assumptions + production questions) and `AI_USAGE.md`.
