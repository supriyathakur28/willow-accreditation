# CLAUDE.md

Coding principles for this repository. They describe *how* code is written here, not what the service does;
the design itself is in `docs/architecture.html`.

## Stack and commands

- Java 21, Micronaut 4.10 (Data JDBC, Serde, Validation, Kafka, Redis Lettuce, Security JWT), MySQL 8.4, Kafka 3.9, Redis 7, Flyway, Maven wrapper.
- `./mvnw -B test`: every test; needs Docker (Testcontainers). `./mvnw -B verify` is what CI runs.
- `./run.sh` starts the stack (`down`, `clean`, `logs`). Compose is memory-capped to fit an 8 GB laptop; keep it that way.
- Check a library's version in `pom.xml` before using its API; APIs move between majors.

## Design

- **Functional core, imperative shell.** Business rules are pure functions over immutable records: no framework imports, no I/O, no clock, no exceptions for business outcomes. The shell loads, calls the function, persists, and maps the result.
- **Outcomes are values.** Use sealed interfaces of records for results and exhaustive `switch` to handle them; the compiler then flags any unhandled case. Exceptions are for failures, not for "not found" or "not allowed". Controllers translate values to HTTP at the edge.
- **Module boundaries are enforced, not documented.** ArchUnit rules fail the build when a package reaches across a boundary. Add a rule whenever you introduce a boundary. Anchor package patterns to this project's root (`com.supriya.accreditation.query..`, not `..query..`, which also matches framework packages).
- **The database is the arbiter for invariants.** Rules that span rows are unique constraints (generated columns when MySQL needs a partial index); rules on one row are decided on a row read with `SELECT … FOR UPDATE`. Never decide on data read before the lock, and never check-then-insert.
- **Simple over clever.** No wrapper that only delegates, no abstraction with one caller, no speculative configuration. Build what is needed; describe the rest as "designed, not built".

## Java style

- Records for data, `sealed` for closed hierarchies, pattern-matching `switch`, `var` only when the type is obvious.
- Primitives when a value cannot be null; `@Nullable` (Micronaut's) on everything that can be. `Optional` for return values only.
- Constructor injection with package-private constructors; no field injection. No Lombok.
- Logging: `private static final Logger log = LoggerFactory.getLogger(X.class)`. `info` for business events, `warn` for recovered problems, `error` for failures with the stack trace, `debug` for noise. Never log request bodies, documents or secrets; log IDs.
- Names say what the method does (`existsForShare`, `deletePublishedBefore`). Comments explain *why*, only where the code cannot; no comments that restate the code.
- Time comes from an injected `Clock`, never `Instant.now()` in logic. IDs come from an injected generator.

## Persistence

- Flyway owns the schema: one folder per logical database, append-only `V{n}__name.sql`, never edit an applied migration. Every table is `utf8mb4`; every constraint and index is named.
- Opaque, case-sensitive identifiers (tokens from other systems) use `CHARACTER SET ascii COLLATE ascii_bin`; the default collation is case-insensitive.
- UUIDs are `BINARY(16)`, big-endian. On entities: `@MappedProperty(converter = UuidBinaryConverter.class) @TypeDef(type = DataType.BYTE_ARRAY)`. A native-query parameter named like an entity property converts automatically; any other UUID parameter needs `@TypeDef(type = DataType.BYTE_ARRAY, converter = UuidBinaryConverter.class)`.
- Times are `DATETIME(6)` in UTC, set by the application. Enums are `VARCHAR` with a named `CHECK`, not MySQL `ENUM`.
- Repositories are Micronaut Data JDBC interfaces bound to a datasource with `@Repository("<name>")`. Derived queries for the simple cases; explicit SQL in `@Query` where the SQL matters (locking, keyset paging, guarded updates). Every `@Query` is covered by an integration test.
- Transactions are short and contain database work only: no HTTP, Kafka or Redis call inside one. Decode, hash and validate before the transaction starts.
- Locking reads (`…ForUpdate`, `FOR SHARE`) only inside a write transaction.
- Run write work through `WriteTransactions`: it retries the whole unit on deadlock and turns other transient SQL errors into 503. Business errors (duplicate key) pass through.
- On a duplicate key, use `DuplicateKeys.violatedKey` to react to the specific constraint; a retry can violate two unique keys and MySQL reports only one. Never `INSERT IGNORE`, which also hides other errors.
- Page large scans with a keyset on an indexed `(column, id)` pair, not `OFFSET`.

## HTTP API

- JSON is snake_case everywhere (requests, responses, errors, events). Unknown request fields are rejected (`@JsonIgnoreProperties(ignoreUnknown = false)`).
- Enum-like request fields are `String` validated with Bean Validation, so a single 400 lists every invalid field instead of failing on the first parse error. Convert to the enum after validation.
- One error envelope for every error, ours and the framework's: `{error_code, message, request_id, invalid_fields?, current_status?}`. Framework messages are not echoed (they can name internal types).
- Validate size before work proportional to size (check an encoded length before decoding it).
- Every response carries `X-Request-Id`; a caller value is accepted only if it matches a safe pattern, otherwise one is generated. It goes to the MDC, outbox rows and message headers.
- Handlers that block run on `@ExecuteOn(TaskExecutors.BLOCKING)` (virtual threads). Do not hold a `synchronized` monitor around blocking calls.
- Status codes are part of the contract: 400 validation, 404 unknown (including a malformed ID), 409 business conflict, 412 precondition, 413 too large, 422 well-formed but unprocessable, 503 transient with `Retry-After`.

## Resilience and messaging

- Every remote call has a timeout. Optional dependencies fail open (log a warning, carry on with the guarantee the database gives); required ones fail fast with 503.
- State change and event are written in one transaction (transactional outbox); nothing is ever written to only one of database and broker.
- Consumers are idempotent (version guard, unique event ID) and commit offsets only after the database commit. Infrastructure errors retry in place; only poison records go to a dead-letter topic.
- Events are named facts with a full snapshot, versioned, additive changes only.

## Testing

- Test against the real thing: MySQL, Kafka and Redis in Testcontainers (`support/IntegrationTest`). Mock only what you cannot run.
- Pure code gets exhaustive, table-driven unit tests (`@CsvSource`, `@ParameterizedTest`).
- Test what the database enforces (constraints, collation, locking) and what the HTTP contract promises (status, envelope, headers), not only the happy path.
- Test failure modes deliberately: hold a row lock to see the 503, pause a container to see fail-open.
- Every bug found gets a test that fails without the fix. After writing a rule's test, break the rule once to see the test fail.
- Integration tests reset state in `@BeforeEach` and must not depend on order; a test that leaves a hung request or a row behind will break the next one.
- Background work (relay, consumers) is off in tests by default (`application-test.yml`); only the end-to-end test switches it on, so other tests never race with it.
- Before a task is done, run the real stack once (`./run.sh`) and replay the main flow with curl: tests do not cover compose wiring, topic creation or seed data.
- Before committing, run the full suite and check the Maven exit code, not a grep of its output.

## Framework notes (learned the hard way)

- **Custom constraints:** Micronaut ignores `@Constraint(validatedBy = X.class)`. Declare `validatedBy = {}` and make the validator a `@Singleton` implementing `io.micronaut.validation.validator.constraints.ConstraintValidator`.
- **Redis timeout:** when `redis.uri` is set, Micronaut ignores `redis.timeout`. Put it in the URI: `redis://host:6379?timeout=100ms` (this also bounds opening the connection).
- **Error bodies:** replace `JsonErrorResponseBodyProvider` to shape framework errors; replace `ConstraintExceptionHandler` to shape validation errors.
- **MDC on virtual threads:** add an `MdcPropagationContext` to the `MutablePropagatedContext` in a server filter; Micronaut carries it onto the blocking executor.
- **MySQL Connector/J** reports *matched* rows, not changed rows, so `ON DUPLICATE KEY UPDATE` returns 1 even when nothing changed. Do not branch on that count.
- **Testcontainers 2** needs `commons-compress` ≥ 1.28 (pinned in `pom.xml`; the Micronaut BOM downgrades it).
- **Flyway Community** does not run undo (`U…`) scripts; roll back with a new forward migration.
- **Serde omits empty collections and nulls** by default. Where the contract needs `{}`, `[]` or `null`, annotate the response record with `@JsonInclude(JsonInclude.Include.ALWAYS)`.
- **Health indicators of optional dependencies** must be off (`redis.health.enabled: false`), or an outage of something the service can run without marks it DOWN and the orchestrator restarts a working app.
- **Caller identity in controllers:** take `@Nullable Principal`, not `Authentication`: with security off, Micronaut binds a `Principal` as null but fails (500) trying to build an `Authentication`. Check `principal instanceof Authentication` for roles and claims.
- **Raw connections** (for example a `GET_LOCK` that must not join a transaction): unwrap the `DataSource` with the injected `DataSourceResolver` bean; `DataSourceResolver.DEFAULT` does not unwrap Micronaut Data's contextual wrapper.
- **Kafka:** the plain `kafka-clients` API is used on purpose. Micronaut Kafka's listener retries have an uncapped exponential backoff and its health check ties the service's health to the broker. The consumer loop is `platform/messaging/RetryingConsumer`.
- **Testing Kafka:** test consumer logic against the Testcontainers broker, not `MockConsumer`, which drops records once polled and so cannot show a seek-back redelivering.

## Git

- One commit per coherent step, with a message that says what changed and why. The full test suite passes before every commit.
- Author: Supriya Thakur <Supriyathakur28@yahoo.com>.
