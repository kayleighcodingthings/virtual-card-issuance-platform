# Virtual Card Issuance Platform

A production-grade virtual card platform built with **Java 21**, **Spring Boot 3.3**, and **Spring Modulith**. Designed
for correctness under concurrent load, with explicit attention to the failure modes common in financial systems.

## Scope

This project satisfies all mandatory requirements for the virtual card issuance platform, and all three bonus items -
asynchronous auditing via Kafka, card expiry scheduling, rate limiting via Bucket4j.
Beyond the specification, the following intentional additions were made:

- **Spring Modulith** - enforces explicit module boundaries at compile time, preventing the silent cross-module coupling
  that degrades large codebases over time.
- **Testcontainers for PostgreSQL + Kafka** - used in integration tests to run against the real database and broker, not
  mocks, so concurrency and locking behaviour is faithfully tested.
- **Quartz Scheduler** - more appropriate than `@Scheduled` for a financial expiry job, due to the usage of a persistent
  job store, `@DisallowConcurrentExecution` to prevent overlapping runs, and observable job history.
- **`EXPIRED` card status** - a fourth terminal status added to the specification's three (ACTIVE, BLOCKED, CLOSED) to
  support the expiry scheduler (bonus requirement).
- **`PENDING` transaction status** - the specification lists `PENDING` as a valid transaction status. Transactions now
  follow a `PENDING → SUCCESSFUL | DECLINED` lifecycle, providing an observable intermediate state useful for diagnosing
  timeouts and partial failures in production.

All other additions are called out in the design decisions section with reasoning.

---

## How to run

### Option 1 - Docker (recommended, one command)

```bash
docker compose up --build
```

The app starts on **http://localhost:8080**. PostgreSQL, Zookeeper, and Kafka are provisioned and healthy-checked
automatically. Liquibase runs migrations on first startup.

To stop and remove volumes:

```bash
docker compose down -v
``` 

### Option 2 - Local (requires PostgreSQL and Kafka running locally)

```bash
# 1. Start infrastructure
docker compose up postgres zookeeper kafka -d

# 2. Run the application
mvn spring-boot:run

# Or build and run the jar
mvn package -DskipTests
java -jar target/card-platform-*.jar
```

### Running tests

```bash
# Unit tests only (fast, no containers)
mvn test

# Unit + integration tests (requires Docker for Testcontainers)
mvn verify
```

### Key endpoints

| Method | Path                         | Notes                             |
|--------|------------------------------|-----------------------------------|
| `POST` | `/api/v1/cards`              | Create card                       |
| `GET`  | `/api/v1/cards/{id}`         | Card details                      |
| `POST` | `/api/v1/cards/{id}/debit`   | Requires `Idempotency-Key` header |
| `POST` | `/api/v1/cards/{id}/credit`  | Requires `Idempotency-Key` header |
| `GET`  | `/api/v1/cards/{id}/history` | Paginated (`?page=0&size=20`)     |
| `POST` | `/api/v1/cards/{id}/block`   |                                   |
| `POST` | `/api/v1/cards/{id}/unblock` |                                   |
| `POST` | `/api/v1/cards/{id}/close`   | Terminal state                    |
| `GET`  | `/actuator/health`           | Health check                      |
| `GET`  | `/actuator/prometheus`       | Micrometer metrics                |

### Example requests

```bash
# Create a card
curl -X POST http://localhost:8080/api/v1/cards \
  -H "Content-Type: application/json" \
  -d '{"cardholderName":"Alice Smith","initialBalance":500.00}'

# Spend
curl -X POST http://localhost:8080/api/v1/cards/{id}/debit \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"amount":50.00}'

# Top-up
curl -X POST http://localhost:8080/api/v1/cards/{id}/credit \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"amount":100.00}'
```

---

## Architecture

The project uses **Spring Modulith** to enforce explicit module boundaries at compile time. Modules can only communicate
through published interfaces, preventing the silent coupling that degrades large codebases over time. This is especially
important in a financial system, where the complexity of the domain can easily lead to tangled code if not disciplined.

```
com.nium.cardplatform
├── card/           Card lifecycle, expiry scheduler
├── transaction/    Spend, top-up, idempotency, history
├── audit/          Kafka publisher and consumer
└── shared/         Exceptions, AOP aspect, rate limiter, config
```

### Module dependency rules - Spring Modulith

Spring Modulith verifies the module dependency graph at build time via `ModularStructureTest`.
Both `shared` and `card` are declared `@ApplicationModule(type = OPEN)` via `package-info.java`:

- **`shared`** is a true shared kernel - its exceptions, events, and config are cross-cutting
  infrastructure that all modules legitimately depend on. Enforcing encapsulation here would
  be artificial.
- **`card`** is a foundational module that `transaction` depends on deeply for balance mutation -
  it accesses `Card` entities, `CardRepository`, and `CardService` directly. Declaring it `OPEN`
  reflects the real architecture honestly rather than papering over genuine coupling.

The dependency graph is:

```
transaction  →  card   (CardService, CardRepository, Card entity)
transaction  →  shared (CardPlatformException, CardAuditEvent)
card         →  shared (CardPlatformException, CardAuditEvent)
audit        →  shared (CardAuditEvent)
```

`audit` never imports `card` or `transaction` directly, and `card` never imports `transaction`.
The graph remains acyclic.

Run the module structure verification test to assert these rules at build time:

```java
// ModularStructureTest.java
@Test
void verifyModularStructure() {
    ApplicationModules.of(CardPlatformApplication.class).verify();
}
```

---

## Design Decisions

### Concurrency Model

**The Problem**

A virtual card balance is a shared mutable value. Under concurrent load, two requests reading the same balance and both
deciding they can proceed will produce incorrect results - a classic lost-update anomaly. For debits this is a safety
violation (balance goes negative); for credits it means money silently disappears from a cardholder's balance.

**The Solution**

Optimistic Locking + `REPEATABLE_READ` + Application-Layer retries. The system uses three complementary layers of
protection each catching what the other miss.

1. **Layer 1: JPA `@Version` Optimistic Locking** - The `Card` table has a version `BIGINT` column. Every `UPDATE`
   statement Hibernate generates includes `WHERE version = N`, and Hibernate increments the version on every write. If
   two transactions both read version = 5 and both attempt to commit, the second write matches zero rows - Hibernate
   throws `ObjectOptimisticLockingFailureException`. No explicit locks are held, so read throughput is unaffected.
2. **Layer 2: `REPEATABLE_READ` Isolation Level** - At the default `READ_COMMITTED` isolation, a transaction re-reads
   committed data on every statement. This means a snapshot taken at the start of the transaction can be stale by the
   time the `UPDATE` executes, creating a window where the version check passes but the balance mutation races with
   another commit. `REPEATABLE_READ` closes this window: PostgreSQL takes a snapshot at the start of the transaction and
   aborts any transaction whose writes conflict with a concurrent commit, returning SQLState 40001. This fires before
   Hibernate's version check and arrives as `PessimisticLockingFailureException` despite no explicit locks being
   involved - a PostgreSQL quirk worth knowing. Both exception types are subclasses of Spring's
   `TransientDataAccessException`, which is what the retry loop catches.
3. **Layer 3: Application-Layer Retries** - `TransactionService` sits outside the `@Transactional` boundary on purpose.
   It delegates to `TransactionProcessor.processDebit()/processCredit()`, which are the transactional units. When a
   `TransientDataAccessException` escapes the committed transaction, `TransactionService` catches it, sleeps for 50ms ×
   attempt (linear backoff), and retries with a fresh transaction and a fresh snapshot. After
   optimistic-lock-max-retries exhausted attempts, the debit is declined with reason `LOCK_CONTENTION_EXHAUSTED` rather
   than throwing a 500, because the caller supplied a valid request that simply lost too many races. The
   `TransactionService`/`TransactionProcessor` split is deliberately not self-injection or AopContext.currentProxy().
   Those patterns make `@Transactional` work within a single bean but break unit tests and are considered Spring
   anti-patterns. Extracting `TransactionProcessor` as a separate Spring bean means the proxy wraps it naturally -
   `TransactionService` calls through the proxy on every retry without any framework tricks.
    - **Why not @Retryable?**  
      Each retry must re-run the idempotency check before attempting to process again - while this thread was sleeping,
      another thread racing on the same key may have already committed. @Retryable retries the annotated method in
      isolation with no awareness of the idempotency pre-check above it, so there's no clean way to express this with
      annotations alone. The explicit retry loop also keeps the backoff strategy, exhaustion behaviour, and
      idempotency-aware path visible in code rather than hidden behind annotation attributes.
4. **Layer 4: Database CHECK constraint as last resort** - Even if all three application-layer defences somehow failed,
   the database itself would reject the commit. This is a last-resort safety net, not part of normal control flow. It
   should never fire in production.

```sql
CONSTRAINT card_non_negative CHECK (balance >= 0)
```

- **Why Not Pessimistic Locking?**  
  Pessimistic locking (`SELECT FOR UPDATE`) was considered and rejected. Under high concurrency, all but one thread
  block waiting for the row lock while still holding a connection - 50 concurrent threads becomes 49 parked connections
  doing nothing, quickly exhausting HikariCP. Optimistic locking lets all threads proceed in parallel; only the losers
  retry, and they do so without holding a connection during the sleep.

- **Idempotency Under Concurrency**  
  Each request carries an `Idempotency-Key` header, checked before any processing. If the key already exists, the
  committed result is returned immediately. The `idempotency_key` columns has a `UNIQUE` constraint as a backstop - if
  two threads race past the initial check and both try to insert, one gets a `DataIntegrityViolationException` which the
  handler resolves by fetching and returning the winner's record rather than propagating the error.

- **What the Concurrency Tests Verify?**  
  `ConcurrencyIntegrationTest` runs against a real PostgreSQL instance via Testcontainers, using a `CountDownLatch` to
  release all threads simultaneously and maximise contention.  
  The debit test fires 50 threads at a card balance of `500.00`, each debiting `20.00`. It asserts four invariants;
    1. balance >= 0,
    2. balance == 0,
    3. application-layer success count = 25,
    4. database successful count = 25

  All four must hold - checking only the balance would miss double-processing bugs.

  The credit test fires 50 threads each crediting `10.00` at a zero-balance card. The final balance must be exactly
  `500.00` - any lost update produces a lower number.

### Money Representation

All balance and amount fields use `BigDecimal` stored as `DECIMAL(19,4)` in PostgreSQL. `float` and `double` are banned
from the domain model - floating-point arithmetic produces rounding errors unacceptable in financial systems (e.g.
`0.1 + 0.2 != 0.3` in IEEE 754). Scale 4 provides sub-cent precision for multi-currency scenarios.

### Idempotency Key

`Idempotency-Key` is a mandatory header for all mutating financial operations (debit, credit). The key is stored
alongside the transaction record using a `UNIQUE` constraint on its column. A repeated request with the same idempotency
key returns the first result without re-processing.

A `DataIntegrityViolationException` on the unique constraint is caught and resolved by re-reading the now-committed
record - the correct behaviour under concurrent idempotent replays.

### Card Expiry Scheduler - Quartz with targeted query

The Quartz `CardExpiryJob` queries only `ACTIVE` cards past their `expiresAt` date:

```sql
SELECT c
FROM Card c
WHERE c.expiresAt <= :now
  AND c.status = 'ACTIVE'
```

The `status = ACTIVE` filter is critical. Without it, the scheduler would re-process already expired or closed cards on
every run, producing spurious state changes and audit events - a production-breaking bug in naive implementation.

`@DisallowConcurrentExecution` prevents two instances of the job running in parallel. Each card is expired in its own
transaction, so a single failure does not roll back the entire batch.

### Asynchronous Auditing via Kafka &amp; Spring Events

Services publish `CardAuditEvent` Spring Application Events rather than calling Kafka directly. The
`AuditKafkaPublisher` listener forwards them to Kafka using `@TransactionalEventListener(AFTER_COMMIT)`.  
This decoupling provides two guarantees:

- **No audit event for a rolled-back transaction** - events only fire after the business transaction commits
  successfully.
- **Kafka unavailability does not fail business operations** - audit is best-effort; errors are logged but not
  propagated.

### Kafka Consumer - @RetryableTopic with DLT

The `AuditKafkaConsumer` uses `@RetryableTopic` with exponential backoff (500ms -> 1s -> 2s -> 4s across 4 attempts). On
exhaustion, messages land in the Dead Letter Topic (`card-audit-events.DLT`). The DLT handler logs the full event
context for operational investigation and replay.

This pattern avoids the poison-pill problem where a single malformed message blocks consumer progress indefinitely.

### AOP Logging Aspect

`ServiceLoggingAspect` applies an `@Around` advice to every public method in every `@Service` bean across all modules.
Entry and exit are logged at `DEBUG`, and later elapsed times are also logged at `DEBUG`. Additionally at `WARN` when a
method exceeds 500ms.

This provides consistent method-level tracing without any per-service boilerplate, and surfaces performance regressions
automatically.

### Rate Limiting - Bucket4j Token Bucket per IP

Each client IP gets a token bucket with a configurable refill rate (default 100 req/min). The token bucket algorithm
allows short bursts up to the bucket capacity while enforcing a sustained rate limit. Response includes a
`Retry-After: 60` header.

Bucket state is currently held in a `ConcurrentHashMap`. In a multi-instance deployment, replace with a Redis-backed
`Bucket4j Proxy Manager`- the `RateLimitInterceptor` remains unchanged.

---

## Observability

### Metrics

Custom Micrometer counters and timers are instrumented at strategic points in the business logic. The `TimedAspect` bean
is registered explicitly in `AppConfig` - without it `@Timed` is a no-op.

| Metric                         | Type    | Description                                                            |
|--------------------------------|---------|------------------------------------------------------------------------|
| `cards.created`                | Counter | Incremented on every successful card creation                          |
| `cards.expired`                | Counter | Incremented by the expiry scheduler per card expired                   |
| `transactions.debit.success`   | Counter | Successful debit operations                                            |
| `transactions.debit.declined`  | Counter | Declined debits (insufficient funds, card not active, lock exhaustion) |
| `transactions.credit.success`  | Counter | Successful credit operations                                           |
| `transactions.credit.declined` | Counter | Declined credit operations (card not active, lock exhaustion)          |
| `transaction.debit.time`       | Timer   | Latency of debit operations - exposes P50/P95/P99                      |
| `transaction.credit.time`      | Timer   | Latency of credit operations                                           |
| `card.create.time`             | Timer   | Latency of card creation                                               |

All standard Spring Boot metrics are also available automatically - JVM memory, GC pause times, HikariCP connection pool
utilisation, and HTTP request rates and latencies via Spring MVC auto-instrumentation.

Metrics are instrumented at deliberately chosen points rather than applied blanket-wide. The named counters (
`transactions.debit.declined`, `cards.expired`) answer business questions - how many transactions are failing and why.
The`@Timed` annotations cover the three endpoints with SLA implications. `ServiceLoggingAspect` surfaces slow operations
across all services without per-method boilerplate.

### Actuator endpoints

| Endpoint                       | Description                                    |
|--------------------------------|------------------------------------------------|
| `GET /actuator/health`         | Application and DB connectivity health         |
| `GET /actuator/metrics`        | All available metric names                     |
| `GET /actuator/metrics/{name}` | Specific metric value and tags                 |
| `GET /actuator/prometheus`     | Prometheus scrape format for Grafana / Datadog |

Example - check debit counter after making a debit request:

```bash
curl http://localhost:8080/actuator/metrics/transactions.debit.success
```

### Logging

Structured JSON logging via `logstash-logback-encoder`. Every log line includes MDC fields injected automatically:

- `requestId` - from `X-Request-Id` header or a generated UUID, echoed in the response header
- `cardId` - injected into MDC by each controller method

`ServiceLoggingAspect` logs entry and exit for every `@Service` method at DEBUG level, with elapsed time always logged.
Any method exceeding 500ms is logged at WARN to surface slow operations without requiring a profiler.

---

## Trade-offs Made Under Time Constraints

| Trade-off                    | What was done                                                                                  | Production alternative                                                               |
|------------------------------|------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------|
| In-memory rate limit buckets | `ConcurrentHashMap<IP, Bucket>` per node - resets on restart, not shared across instances      | Redis-backed Bucket4j `ProxyManager` for distributed, persistent limits              |
| No idempotency key expiry    | Keys live forever in the `transaction` table                                                   | Separate `idempotency_record` table with TTL and a cleanup job                       |
| Linear retry backoff         | `50ms × attempt + random jitter(0–50ms)` - adequate for low concurrency                        | Full exponential backoff with wider jitter window for high-cardinality retry storms  |
| No OpenAPI spec              | Manual curl examples in README                                                                 | Springdoc OpenAPI (`/swagger-ui.html`) auto-generated from controllers               |
| Audit is fire-and-forget     | `@Async` Kafka publish after commit - no delivery guarantee if the process crashes post-commit | Transactional outbox pattern with Debezium CDC for guaranteed at-least-once delivery |
| Single-node Quartz scheduler | `isClustered=false` - two instances would double-fire the expiry job                           | Quartz JDBC clustering with `isClustered=true` and a shared job store                |

### Test Coverage Note

Controller unit tests (`@WebMvcTest`) were omitted in favour of full-stack integration tests which cover every endpoint
end-to-end with real HTTP calls against a real PostgreSQL instance via Testcontainers. This provides stronger guarantees
than mocked controller test since it exercises the full request lifecycle including serialization, validation, service
logic & database interaction.

Two targeted tests were added to complement the integration suite:

- `CardRepositoryTest` - tests the expiry query in isolation against real PostgreSQL. This query has a critical
  `status=ACTIVE` filter that prevents the scheduler from re-processing already expired cards or cards in terminal state
  or blocked. Its correctness is important enough to warrant a dedicated test independent of the full stack.
- `CardControllerTest` - tests input validation rules (`@Valid`, `@NotBlank`, `@DecimalMin`) and missing header
  behaviour using `@WebMvcTest`. These are fast, isolated and complement the integration tests which focus on business
  logic rather than request validation edge cases.

---

## Future Improvements

- **Transactional outbox pattern** - persist audit events to an `outbox` table within the business transaction, then
  relay to Kafka via a separate poller. Guarantees no lost events even if Kafka is down at commit time.
- **Redis-backed Bucket4j** - distribute rate limit state across instances with no code changes beyond the Bucket4j
  configuration.
- **Card number generation** - Luhn checksum validation, PAN tokenisation via a vault.
- **OpenAPI / Swagger** - add `springdoc-openapi-starter-webmvc-ui`.
- **Soft deletes** - add `deleted_at` column to `card` instead of hard deletes.
- **Daily / monthly spend limits** - add a `CardLimit` entity with configurable per-period caps.
- **Per-card rate limiting** - extend `RateLimitInterceptor` to bucket on `cardId` for financial ops, in addition to the
  global IP bucket.
- **Metrics dashboards** - Grafana dashboards on top of the Prometheus endpoint (`/actuator/prometheus`).
- **Per-card expiry jobs** - an alternative to the polling scheduler would be to schedule a dedicated Quartz job per
  card at creation time, firing at exactly `expiresAt`. This improves precision (expiry fires at the exact second rather
  than within 60 seconds) and eliminates polling overhead. The trade-off: 100,000 cards means 100,000 Quartz trigger
  rows, job cancellation is required when a card is closed early, and `expiresAt` changes require rescheduling. The
  polling approach with a targeted `status = ACTIVE` filter scales better and handles card closure and expiry date
  changes for free. A hybrid approach - polling for bulk expiry, per-card jobs for precision-sensitive notifications
  like "expiry warning emails" - would be the production choice.
- **Deeper audit history** - two complementary enhancements would round out the audit story
  for a production system:
    - *Point-in-time balance reconstruction via Hibernate Envers* - the `Card` entity changes
      state across its lifecycle (balance mutations, status transitions). Spring Data Envers would
      automatically capture a full snapshot at every revision, enabling queries like "what was the
      balance at time T?" without manual audit logic. Not included here because the Kafka audit
      pipeline already satisfies the spec's audit requirement, and adding both would create two
      overlapping mechanisms with no clear source of truth. The right trigger would be a regulatory
      requirement for immutable, queryable balance history. Note: Envers is not useful on
      `Transaction` since transactions are already immutable records.
    - *Status change reasons* - block, unblock, and close operations currently record the
      transition in the audit log but not the reason. A production system would accept an optional
      `reason` string on each status-change endpoint and persist it as a `last_status_reason`
      column on the `card` table for the current reason. If Envers is adopted, the reason would
      be captured automatically in the revision snapshot alongside the status change - making a
      separate history table unnecessary.
- **Authentication and authorisation** - all endpoints are currently unprotected. In production, card issuance and
  status management (block/unblock/close) would be restricted to authenticated business accounts via API key or OAuth2
  client credentials. Spend and topup would require cardholder-level authentication. Spring Security with
  `@PreAuthorize` annotations would enforce this at the controller layer without touching business logic.