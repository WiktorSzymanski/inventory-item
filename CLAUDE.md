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
