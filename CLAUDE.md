# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew bootRun          # Start server on port 8080
./gradlew test             # Run all tests (JUnit 5)
./gradlew build            # Full build
./gradlew bootJar          # Build executable JAR → build/libs/app.jar
docker-compose up          # Start postgres + KurrentDB + api containers
```

Run a single test class: `./gradlew test --tests "pl.szymanski.wiktor.ApplicationTest"`

## Scaling (identical on all 8 branches)

Every branch runs its API behind an **nginx load balancer that owns host `:8080`**; the API
service itself has no `container_name` and no published port, only `expose: 8080` and
`deploy.replicas`. Scale with the single `REPLICAS` knob in `.env` (auto-loaded by compose,
so the value is sticky):

```bash
REPLICAS=3 PG_MAX_CONNECTIONS=1300 docker compose up -d
```

- **Never also pass `--scale`.** Mixing `--scale` with `deploy.replicas` makes Compose
  remove middle replicas.
- `REPLICAS` drives *both* the container count and `API_REPLICAS`, which on ES branches
  sets the saga per-node claim to `ceil(axon.saga.total-segments / replicas)`. If they
  diverge, segments are left unclaimed and those orders are never processed.
- `PG_MAX_CONNECTIONS` must rise with `REPLICAS` — ~350 connections per ES replica
  (Hikari 50 + Axon 300). The default is `600`; add ~350 per additional replica.
- Containers are named `<project>-api-es-N`, **not** `api-es`, even at `REPLICAS=1`. Use
  `docker compose logs api-es` / `docker compose exec api-es` (service name), not
  `docker logs api-es`. Any cadvisor query must match `name=~".*api-es.*"`.
- Prometheus discovers the API through `dns_sd_configs`, so a replica count change needs no
  config edit.

### `REPLICAS>1` is wired up, but the domain is not multi-node safe yet

The infrastructure is verified at `REPLICAS=3`: nginx spreads load, Prometheus finds all
targets, and the saga splits its 60 segments evenly (20/20/20, none unclaimed). **The
application logic is a different matter, and currently fails on both families.** Treat
`REPLICAS=1` as the only measurement-grade configuration until these are fixed.

- **ES: cross-node append conflicts strand orders permanently.** Two replicas load the same
  `InventoryItem` and race to append at the same sequence number. Axon's JDBC event store
  raises `EventStoreException("An event for aggregate [item-1] at sequence [123] was
  already inserted")`, but `config/ConcurrencyRetryScheduler.kt:33` retries **only**
  `ConcurrencyException`, so the saga's `SagaReserveItemCommand` fails and is never
  retried. Measured on ES-2 at `REPLICAS=2`, RATE=30: 3537 of 10401 orders stuck `PENDING`
  forever, backlog never drained. The aggregate lock is in-process only, so nothing
  serialises the two writers. The likely fix is a `PersistenceExceptionResolver` /
  `SQLStateResolver` that translates the duplicate-key into `ConcurrencyException` so the
  existing retry path picks it up — untested, and it would change measured retry behaviour.
- **TO: the outbox poller runs on every node.** Spring Modulith's
  `republication-interval: PT30S` means orphaned `event_publication` rows may be
  republished by more than one replica. Not investigated.

Do not publish scale-out numbers for either family without resolving the above.

## Architecture

Kotlin 2.3 / Spring Boot 3.5 REST API on Netty via Spring WebFlux. **Event Sourcing** implementation: KurrentDB is the source of truth (append-only event log per aggregate); PostgreSQL holds a projection (read model) updated by a KurrentDB persistent subscription.

- **`controller/InventoryController.kt`** — `@RestController`; `GET /inventory`, `GET /inventory/{itemId}`, `POST /inventory`, `POST /inventory/reserve`
- **`service/InventoryService.kt`** — retry loop (5 attempts, exponential backoff with jitter); catches `WrongExpectedVersionException` for optimistic concurrency
- **`service/command/CreateItemCommandHandler.kt`** — appends `InventoryCreatedEvent` to KurrentDB with `StreamState.noStream()` (throws `ItemAlreadyExistsException` on conflict)
- **`service/command/ReserveItemCommandHandler.kt`** — loads aggregate by replaying KurrentDB stream, appends `InventoryReservedEvent` with exact stream revision
- **`repository/EventStoreRepository.kt`** — `loadAggregate(itemId)` reads and replays KurrentDB stream; `appendEvent(...)` appends with expected revision
- **`repository/InventoryRepository.kt`** / **`InventoryProjection.kt`** — R2DBC read model; `inventory_state` table is the PostgreSQL projection
- **`subscription/InventoryProjectionSubscriber.kt`** — `ApplicationRunner`; creates and subscribes to a KurrentDB persistent subscription group (`inventory-projection-group`) on `$all`; updates the PostgreSQL projection on each event
- **`config/KurrentDbConfig.kt`** — `KurrentDBClient` and `KurrentDBPersistentSubscriptionsClient` beans
- **`config/KurrentDbProperties.kt`** — `@ConfigurationProperties("kurrentdb")` for `connectionString`

Config lives in `src/main/resources/application.yaml`. Flyway migrations in `classpath:db/migration`. Env overrides: `DB_JDBC_URL`, `DB_R2DBC_URL`, `DB_USER`, `DB_PASSWORD`, `KURRENTDB_URL`.

KurrentDB UI: `http://localhost:2113`. API docs: Swagger UI at `/swagger-ui.html`.

### Stream naming

One KurrentDB stream per aggregate: `inventory-{itemId}`. Events: `InventoryCreatedEvent` (revision 0), `InventoryReservedEvent` (revision ≥ 1).

### Coroutine rules

- Never use `runBlocking` or `.block()` in controllers, services, or repositories.
- All blocking KurrentDB calls (CompletableFuture `.get()`) must be inside `withContext(Dispatchers.IO)`.
- `runBlocking` is allowed in `InventoryProjectionSubscriber.handleEvent()` because the KurrentDB subscription listener callback runs on a non-coroutine thread.
