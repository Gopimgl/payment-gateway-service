# Assumptions and Production Questions

## Assumptions
- The payment gateway is mocked inside this application (`/mock-gateway/*`); no real money moves.
- Authentication/authorisation is out of scope (per the brief). Callbacks are still signed with a
  shared-secret HMAC to show the pattern.
- The create request has an optional `scenario` field. It is a **test-only hook** that tells the mock bank how to behave;
  a real client never chooses the outcome, so the field would be removed and the bank would decide.
- Actuator health details are open for the demo; in production they would be restricted.
- One currency per payment; supported set is INR, USD, EUR, GBP. Amounts have at most 2 decimals.
- Clients send an `Idempotency-Key` per logical payment (required header).
- PostgreSQL runs locally; Flyway owns the schema (`ddl-auto=none`).
- Gateway read-timeout is treated as "outcome unknown", not failure.
- Payments stuck in `UNKNOWN`/`PENDING` are recovered by the reconciliation job, which asks the bank for the real status.
- A callback that arrives for an already-final payment with a different status is acknowledged
  (HTTP 200, outcome `IGNORED`), audited, and not applied.
- Single application instance for the demo; row locks and unique constraints keep it correct with several.

## Questions I would ask for a production service
**Business / product**
1. Which real gateway(s) and payment methods (cards, UPI, net banking, wallets)? Which currencies?
2. Are refunds, partial refunds, captures/authorisations, chargebacks, and settlement in scope?
3. What are the SLAs for payment confirmation and for resolving `UNKNOWN` payments?

**Correctness and reliability**
4. If the gateway times out but processed the charge, how do we reconcile: status-query API, polling job, daily settlement files?
5. How long should idempotency keys live, and what is the policy when a key is reused with a different payload?
6. What is the retry policy and which gateway errors are safely retryable?
7. What ordering/delivery guarantees does the gateway give for webhooks (at-least-once, out-of-order)?

**Security and compliance**
8. How does the provider sign callbacks (HMAC, asymmetric, IP allow-list), and how do we rotate secrets and prevent replay (timestamps/nonces)?
9. PCI-DSS scope: do we ever touch card data, or only tokens? What must be masked in logs?
10. How are clients authenticated (OAuth2/API keys/mTLS), rate-limited, and authorised per merchant?
11. Data retention, audit, and privacy (GDPR / India DPDP) requirements?

**Platform and operations**
12. Expected throughput and latency; database sizing, HA, backups, and failover for PostgreSQL?
13. Observability: tracing, alerting on stuck `PENDING`/`UNKNOWN` payments, dashboards, on-call?
14. Should the outcome be published as events (outbox + queue) to downstream systems such as orders and notifications?
15. Multi-tenancy (multiple merchants) and per-merchant gateway configuration?

## Known limitations (and what I would do next)
- Callback signatures have no timestamp/nonce, so replay protection relies on event-ID de-duplication; add a timestamp check.
- Actuator health details are open for the demo; restrict them in production.
- The mock bank lives in the same app and keeps its ledger in memory; a real provider adapter replaces the `mock` package.
- Reconciliation polls on a timer; at scale use a queue/outbox and per-payment backoff.
- `PaymentFlowIT` covers PostgreSQL behaviour (idempotency under concurrency, timeouts, duplicate callbacks) but needs a local
  database, so it is run on demand (`mvn test -Dtest=PaymentFlowIT`) rather than in a plain `mvn test`.
