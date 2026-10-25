# AI Tool Usage

> Edit this so it matches what YOU actually did. Do not claim checks you did not perform.

## Tools used
- Claude (Anthropic), chat interface: explained the assessment, proposed the architecture, generated the Spring Boot code,
  tests, Postman collection, documentation and the project presentation.

## Where the AI output was helpful
- Layered design, payment state machine, idempotency, callback de-duplication and the timeout/UNKNOWN handling.
- Boilerplate: entities, DTOs, repositories, Flyway migration, OpenAPI config, unit and web tests.
- Drafting README and production-readiness questions; the reconciliation job for stuck payments.

## Limitations and corrections
- The AI generated the code without compiling or running it. I ran `mvn test`, `mvn spring-boot:run` and the Postman
  collection locally. **Write here what passed, what failed, and what you changed.**
  
- My own decisions that changed AI output: the outcome is chosen with a `scenario` test hook (documented as test-only),
  and a reconciliation job was added after reviewing weak points (stuck UNKNOWN/PENDING payments).

- Risks I reviewed by hand: Flyway/Hibernate column mappings, read-timeout detection in `HttpPaymentGateway`
  (depends on the JDK "Read timed out" message), and transaction boundaries around the bank call.

- Suggestions I rejected or changed: Testability vs. production behavior: I retained the scenario field as a test-only mechanism to simulate payment outcomes such as success, decline, timeout, and temporary bank unavailability. In a production implementation, payment outcomes would be determined by the payment provider's actual response rather than a client-supplied test scenario.
Idempotency and duplicate requests: I reviewed the idempotency design to ensure that retrying a payment request with the same idempotency key does not create a second payment. Requests that reuse a key with different payment details should be rejected.
Asynchronous callback processing: I retained callback event de-duplication using a unique eventId, so repeated delivery of the same callback does not apply the same event more than once. I also considered how distinct events for the same payment should be handled.
Timeout and reconciliation handling: Rather than treating every timeout as a definitive payment failure, I retained the UNKNOWN state for outcomes that cannot be confirmed immediately. A reconciliation process can subsequently query the provider and resolve payments whose final status remains uncertain.
Error handling and observability: I considered the importance of distinguishing business declines from infrastructure failures, recording payment state transitions, and exposing operational health and metrics for troubleshooting.
Production-readiness considerations: I identified areas requiring additional validation before production use, including callback signature verification, transaction boundaries around external provider calls, database schema consistency, and reliable retry and reconciliation behavior.
These changes reflect my focus on correctness, testability, and failure handling rather than accepting generated code or design suggestions without review.

## Ownership
I reviewed the generated code and can explain every class and design decision.
