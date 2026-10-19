# Accreditation Service

A REST service that lets administrators record and finalise investors' accreditation evidence, and serves each
user's current accreditation statuses to the client application. Every state change is published to Kafka as an
audit event, and the client view is built from those events.

Java 21 · Micronaut 4.10 · MySQL 8.4 · Kafka 3.9 · Redis 7 · Docker Compose

The full design (diagrams, decisions and rejected alternatives) is in
[`docs/architecture.html`](docs/architecture.html) ([PDF](docs/architecture.pdf)). This README gives the short answers.

## Run it

Requirements: Docker with Compose v2. No JDK needed to run; the image is built inside Docker.

```bash
./run.sh            # build and start everything; returns when the service is healthy on http://localhost:9999
./smoke.sh          # replays the brief's requests and checks every response
./run.sh infra      # only MySQL, Kafka and Redis, to run the service from an IDE
./run.sh logs       # follow the service logs
./run.sh down       # stop (data kept)    ./run.sh clean   # stop and delete all data
```

[`postman/accreditation-service.postman_collection.json`](postman/accreditation-service.postman_collection.json)
has every request, with tests on each: import it into Postman and run the "Security off" folder against
`./run.sh`, or "Security on" against `SECURITY_ENABLED=true ./run.sh`. Each folder runs the admin API, then the
client API, and leaves nothing PENDING, so it can be re-run. From the command line:

```bash
npx newman run postman/accreditation-service.postman_collection.json --folder "Security off"
```

`requests.http` has the same requests for the IntelliJ or VS Code HTTP client. Health and metrics are on the
management port, 9998.

There is one OpenAPI contract per audience, so the client application never sees the admin API:
[admin](http://localhost:9999/swagger/accreditation-admin-api-1.0-admin.yml) and
[client](http://localhost:9999/swagger/accreditation-client-api-1.0-client.yml). Swagger UI at
http://localhost:9999/swagger-ui/index.html switches between them. `POST /auth/token` is in neither.
The stack is memory-capped at about 2.3 GB (it uses about 1 GB idle).
Running on a Mac, or from IntelliJ: see [`docs/LOCAL_SETUP.md`](docs/LOCAL_SETUP.md).

Compose creates the Kafka topics and publishes four sample users, including the brief's
`g8NlYJnk7zK9BlB1J2Ebjs0AkhCTpE1V`, standing in for the Users service.

Security is off by default, so the brief's requests work unchanged. To turn it on:

```bash
SECURITY_ENABLED=true ./run.sh
curl -s -X POST localhost:9999/auth/token -H 'Content-Type: application/json' -d '{"role":"ADMIN","subject":"admin-7"}'
curl -s -X POST localhost:9999/auth/token -H 'Content-Type: application/json' -d '{"role":"USER","user_id":"g8NlYJnk7zK9BlB1J2Ebjs0AkhCTpE1V"}'
```

`POST /auth/token` is a local stand-in for the authentication service's login, so do not deploy it.

## Test it

```bash
./mvnw verify       # all tests; needs Docker, starts its own MySQL, Kafka and Redis (Testcontainers)
```

136 tests. The ones that matter most:

- **State machine:** every (state × command) pair through the pure decision function, as a table.
- **HTTP contract:** the brief's exact payloads, every status code and error body.
- **End to end:** POST → PUT → Kafka → client GET and history, including an expiry by the job.
- **Concurrency (9b):** 20 parallel PUTs on one accreditation must form a legal chain of events; 20 parallel POSTs
  for one user must create exactly one PENDING; the expiry job racing revocations makes exactly one decision each.
  Removing the row lock makes the first test fail.
- **Failure modes:** a locked row answers 503 after 3 s; Redis down does not fail writes; Kafka down does not affect
  the admin API and the events are delivered on recovery; poison events go to a dead-letter topic.

## API

| Method and path | Who | Success | Errors |
|---|---|---|---|
| `POST /user/accreditation` | admin | `201 {"accreditation_id"}` | 400 · 413 · 409 `PENDING_ACCREDITATION_EXISTS` · 422 `USER_NOT_FOUND`, `IDEMPOTENCY_KEY_REUSED` · 503 |
| `PUT /user/accreditation/{id}` | admin | `200 {"accreditation_id"}` + `ETag` | 400 · 404 · 409 `TRANSITION_NOT_ALLOWED` · 412 `VERSION_MISMATCH` · 422 · 503 |
| `GET /user/{userId}/accreditation` | admin, or that user | `200`, the brief's shape | 400 · 403 · 503 |
| `GET /user/accreditation/{id}/history` | admin | `200`, every change in version order | 404 · 503 |

Every error has the same body: `{error_code, message, request_id, invalid_fields?, current_status?}`. A 400 lists every
invalid field. Optional headers: `Idempotency-Key` makes a retried POST return the original ID; `If-Match: "<version>"`
on PUT rejects a decision made on a stale view (412). Every response carries `X-Request-Id`.

**Assumptions.** These are the brief's open points and how I read them:

- Allowed transitions: PENDING → CONFIRMED, FAILED or EXPIRED; CONFIRMED → EXPIRED, or FAILED (revoking a confirmation).
  FAILED and EXPIRED are final.
- Sending the current status again returns 200 and changes nothing, so retries are safe and the 30-day clock is not
  reset.
- The 30 days run from the last update.
- POST for an unknown user returns 422. GET for a user with no accreditations returns an empty map.
- Documents are PDF, PNG or JPEG, at most 5 MB. The file name is a bare name: one containing `/`, `\` or a
  control character is rejected with 400, not rewritten.

## 9a. Architecture

**One deployable, a modular monolith, built with CQRS and a transactional outbox.**

- **Write side (admin).** Each command runs in one MySQL transaction against the *write model*
  (`accreditation_write`). The transaction locks the accreditation's row, asks a pure decision function whether the
  change is allowed, updates the row, and inserts the event into an **outbox** table. State and event are therefore
  committed together, or not at all.
- **Relay.** A relay publishes the outbox to Kafka (`accreditation.events.v1`, keyed by accreditation ID) in order, at
  least once. Kafka is never on the request path: if it is down, admins keep working and the events wait.
- **Read side (client).** Consumers build the *read model* (`accreditation_read`) from the events. One consumer keeps
  the current state that `GET /user/{userId}/accreditation` serves; another keeps an append-only audit history.
  Consumers are idempotent (each event carries a version and a unique ID). They retry database outages in place, so
  per-key order is kept, and send unusable events to a dead-letter topic.
- **Events** are named facts (`AccreditationSubmitted`, `…Confirmed`, `…Failed`, `…Expired`). Each carries a full
  snapshot, the previous status and a timestamp. The topic is kept forever as the audit record.
- **Expiry job.** Hourly, it expires CONFIRMED accreditations not updated for 30 days through the same lock-and-decide
  path as an admin PUT. MySQL `GET_LOCK` makes each scheduled job run on one instance at a time.
- **Users.** Users belong to another service. The write model keeps a local copy fed by `user.events.v1`, so POST
  can reject unknown users without a remote call. The topic is compacted, so any record for a user means the user
  exists and a tombstone means they were deleted.

**Patterns.** CQRS; transactional outbox; event-carried state transfer; idempotent consumers; functional core and
imperative shell (business rules are pure functions over immutable records, and outcomes are values, not
exceptions); pessimistic locking; bulkheads (separate connection pools and request limits for admin and client
traffic). `command` and `query` never depend on each other; Kafka is their only link.

**Why two current-state tables.** Requirement 4 says the admin checks should use "the database containing the entity's
current state". The event-built store is updated *after* the request returns, so checks against it could not be
consistent (see 9b). The checks therefore run against the write model, updated in the same transaction as the event.
The event-built read model required by requirement 3 serves the client.

## 9b. Concurrent updates by different administrators

The checks always run against the latest committed state because **MySQL is the only arbiter**, inside the command's
transaction. They never run against the eventually consistent projection or an application cache. There are two
kinds of rule, and each gets its own mechanism:

1. **Rules about one accreditation (4a, 4b).** The first statement is `SELECT … FOR UPDATE` on the accreditation's
   row. A second administrator's request waits on that lock, then reads the row *as the first one committed it*, and
   is judged against that. Example: A fails an accreditation while B confirms it. Whoever is second sees FAILED and
   gets `409 TRANSITION_NOT_ALLOWED`, with the current status in the body. The 404, the version check and the
   decision all use this locked read; nothing read earlier is trusted.
2. **One PENDING per user (4c)** spans rows, so no row lock covers it. The database enforces it with a unique index
   on a generated column, `user_id_if_pending = CASE WHEN status = 'PENDING' THEN user_id END`. NULLs never collide,
   so at most one PENDING row per user can exist, whichever code path writes. Two simultaneous POSTs: one inserts,
   the other gets a duplicate-key error and `409`.

Supporting details:

- **Stale decisions.** If admin B decided on a view that admin A has since changed, B can send
  `If-Match: "<version>"` and get `412` instead of overwriting A's decision.
- **Deadlocks** retry the whole transaction (up to twice). A lock waited on for more than 3 s returns `503` with
  `Retry-After`.
- **Expiry job.** It scans for candidates with a plain read, then locks each row and re-checks eligibility before
  expiring it. A row that an admin revoked in the meantime is skipped.

This holds for any number of service instances, because the lock and the unique index live in the one writable
primary. I chose row locks over a distributed lock (a Redis round trip on every write and a second system to trust)
and over optimistic retries (contention here is rare, and a lock gives a clean, deterministic business error).

## 9c. Scaling the client path

Client reads already have their own logical database, connection pool, consumers and request limit, so they cannot
take admin resources. Client traffic produces no events, so it doesn't touch Kafka. I would scale in measured steps,
each taken only when its trigger shows in the metrics:

1. **Measure first.** Load-test at 1×, 5× and 10×. Dashboards for per-route latency and errors, connection-pool
   waits, consumer lag and read-model freshness.
2. **Keep each GET cheap.** It is one range scan of the `(user_id, accreditation_id)` primary key. Size the read pool,
   and answer `503` immediately when it is saturated rather than queuing.
3. **Run more copies.** The service is stateless (the jobs run once across instances), so add replicas behind the
   load balancer.
4. **Read replicas** for the read model. Route each user to one replica, so their reads never go backwards in time.
5. **Cache, if it pays off.** A separate Redis with versioned entries, so a stale fill can never overwrite a newer
   value.
6. **Split the deployable** into a small command service, a query service that autoscales on CPU and in-flight
   requests, and the projector sized to the partition count. This removes the remaining shared CPU and heap. It
   is configuration, because the read side already has its own database and consumers.
7. **Edge.** Authenticate once at the gateway and rate-limit per user or IP (429).

## Designed, not built

- Rate limiting at a gateway; keyset pagination on the GET; a response cache; read replicas and the service split.
- Automatic expiry of PENDING accreditations left unreviewed, so they cannot block a resubmission indefinitely.
- Processed-request records written in the same transaction as each change, so any retried request returns exactly
  its original result.
- Documents in object storage with encryption and malware scanning; crypto-shredding for erasure requests.
- Avro or Protobuf with a Schema Registry; a CDC relay (Debezium) instead of polling; tracing across Kafka.
- A tool that replays dead-lettered events onto the main topic.

## Project layout

```
src/main/java/com/supriya/accreditation/
  domain/     pure business rules: state machine, decision function, events (no framework code)
  command/    admin API, command service, outbox relay, expiry job, write-model repositories
  query/      client and history APIs, projection and history consumers, read-model repositories
  users/      consumer that keeps the local copy of known users
  events/     the event wire format shared by both sides
  platform/   errors, request IDs, transactions, idempotency, job locks, security, consumer loop
src/main/resources/db/{write,read}/   Flyway migrations, one folder per logical database
docker/                               MySQL and Kafka initialisation (databases, users, topics, sample users)
```

## AI usage

I used Claude Code throughout this project, in different roles for different parts of the work.

**1. Architecture and system design: designed by me, challenged by AI.** I decided the architecture and the system
design before any code was written:

- one deployable, structured as a modular monolith, with command and query sides that communicate only through Kafka
- a write model that every admin command runs against, in one transaction
- an event-built read model for the client, in its own logical database with its own connection pool
- a transactional outbox between the two, so state and events are always committed together
- the consistency mechanisms: row locks for rules 4a and 4b, a unique index for 4c, and version guards in the
  consumers
- the Kafka design: topics, keys, consumer groups and dead-letter topics
- the bulkheads between admin and client traffic, and the path for scaling the client side

I used Claude Code as a sparring partner to challenge that design, including an adversarial review of the
architecture document. I decided which of its suggestions to keep.

**2. Business logic: specified by me.** I decided how the brief's requirements behave:

- the state machine and its allowed transitions
- how rules 4a–4c hold when administrators act concurrently
- the 30-day expiry rule

I also set the engineering standards every change had to meet: idempotent writes, correctness under concurrency,
performance, and room to scale. Claude Code implemented the logic to that specification.

**3. Tests: decided by me, written by AI.** I decided what had to be proven:

- that concurrent decisions on one accreditation form a legal chain
- that only one PENDING accreditation can exist per user
- that retries are idempotent
- that the service behaves correctly when MySQL, Kafka or Redis fails
- that poison events go to a dead-letter topic
- that each test fails when the rule it names is broken

Claude Code wrote the test code to those requirements.

**4. Supporting code, infrastructure and documentation: delegated.** I let Claude Code build these largely on its
own:

- the build scripts, Docker setup and CI pipeline
- the MySQL, Kafka and Redis setup and the application configuration
- the UUIDv7 generator
- error and exception handling
- the persistence layer and the request filters
- the idempotency guard and document handling
- most of the documentation

**Review.** I reviewed every step before it was committed. The coding conventions it followed are recorded in
[`CLAUDE.md`](CLAUDE.md).
