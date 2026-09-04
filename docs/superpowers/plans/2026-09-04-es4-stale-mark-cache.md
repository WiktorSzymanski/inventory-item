# ES-4 Stale-Mark Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **At execution time, copy this file to `docs/superpowers/plans/2026-09-04-es4-stale-mark-cache.md` on the new branch** so it travels with the code, matching the repo's existing convention.

**Goal:** Replace `PessimisticCachingRepository`'s eager post-rollback cache repair with a stale *mark* recorded on the cache entry, checked and resolved lazily at the next load — so no command is ever handed state that is already known to be doomed.

**Architecture:** A cache entry gains `knownStoreSequence`: the lowest sequence number known to exist in the event store. On a `ConcurrencyException` rollback the losing command marks the entry with `baseSequence + 1` (the sequence it failed to insert, which by definition the winner now holds) instead of reading the delta. On the next cache hit the loader compares `sequence >= knownStoreSequence`; if it holds the entry is not known stale and is used as-is, otherwise the loader catches up from the store first and publishes the result before running the command. The check is *exact*, not heuristic: `sequence < knownStoreSequence` means the next append is a guaranteed conflict.

**Tech Stack:** Kotlin 2.3.0 / JVM 21, Spring Boot 4.0.6, Axon Framework 4.11.2, Caffeine, Micrometer, JUnit 5 + AssertJ, Testcontainers 1.20.4 (Postgres 16-alpine).

**Spec:** No separate spec document — the design was settled in conversation and is restated in full in Context and Design below.

---

## Context

ES-4 runs its `InventoryItem` repository lock-free (`AxonConfig.kt:249`, `NullLockFactory.INSTANCE`). Correctness rests on the event store's `UNIQUE (aggregate_identifier, sequence_number)` constraint; the loser of a race gets a `ConcurrencyException` and is retried by `ConcurrencyRetryScheduler` (max 5 attempts, backoff `25ms << attempt` capped at 500ms, **no jitter**).

Today the loser also repairs the cache eagerly: `registerCacheHooks` registers `uow.onRollback { catchUp(id, baseSequence) }`, which unconditionally issues `eventStore.readEvents(id, current.sequence + 1)`. Two problems:

1. **Every rollback pays a SELECT**, including the common case where a local winner's `afterCommit advance` has already moved the cache to head. That probe is the `outcome="noop"` arm of `inventory.opt.catchup.duration`.
2. **Freshness is pushed and hoped, never verified at the point of use.** Between the repair and the retry's load, the cache can go stale again; and if `catchUp` throws, the repository's own KDoc admits "commands on this aggregate will conflict until a later rollback repairs it" — a documented, silent failure mode with no self-healing path.

Marking instead of repairing inverts this: the loser records one number, and the *next reader* verifies before use. A doomed command costs 25 ms of backoff plus one of only five retries; a verified catch-up costs one SELECT (~0.2 ms) on the load path. Because the check is exact, that SELECT is never speculative — it converts a certain failure into a possible success.

**Intended outcome:** fewer wasted retries and fewer rejections under contention (`inventory.optimistic.exhausted` should fall), a cache that self-heals from a failed repair, and one clearly describable repair strategy for the thesis to contrast against ES-4's eager one.

## Decisions already made

- **Branch:** new branch `ES-4-staleMark` off `ES-4`, in its own worktree. `ES-4` stays the untouched eager-repair baseline; A/B is branch-vs-branch, matching `ES-4-bounded` / `ES-4-NullLock-A`.
- **Hard replace.** `onRollback` only marks. There is no property to restore the eager path — one code path, one story.
- **Tests:** new fast unit harness *and* extensions to the existing Testcontainers integration test.

## Design

### Cache entry

```kotlin
private data class Confirmed<T>(
    val root: T,
    val sequence: Long,
    val deleted: Boolean,
    val trigger: SnapshotTrigger,
    val knownStoreSequence: Long = UNKNOWN,   // UNKNOWN = -1L
)
```

`knownStoreSequence` is *not* "how stale we are" — it is a lower bound on the store head that we learned the hard way. `sequence >= knownStoreSequence` ⇒ not known stale.

Keeping it **inside the entry** rather than in a side map means it is bounded and evicted with the entry, needs no separate TTL, and is written through the same atomic `asMap()` operations that already protect the entry.

### Off-by-one

A command that loaded at `N` applies exactly one event (`InventoryItem.kt:72`) and appends `N+1`. A `ConcurrencyException` on that append means **`N+1` exists in the store**. So the mark is `baseSequence + 1`. This stays correct if a handler ever applies several events: two commands from the same base always collide first at `base+1`.

### Why the mark must be guarded on `ConcurrencyException`

`onRollback` fires for *any* failure — a business exception, a DB timeout, a serialization error. Those imply nothing about the store head. An unguarded mark would sit permanently above `sequence` (nothing will ever advance the cache to reach it) and cost a SELECT on **every** subsequent load, forever. The eager repair got away with being unconditional because it left no state behind; a mark does.

`AbstractUnitOfWork.commitAsRoot` calls `setRollbackCause(e)` *before* `changePhase(Phase.ROLLBACK)`, so inside an `onRollback` handler the cause is readable via `uow.executionResult?.exceptionResult`. Walk the cause chain exactly as `ConcurrencyRetryScheduler.kt:65` already does.

### Self-clearing

Defence in depth for a mark that turns out to be wrong (a `ConcurrencyException` from somewhere other than a real duplicate key, or a winner that itself rolled back): when a stale-marked load reads the delta and finds it **empty**, it lowers the mark to the entry's own sequence. A stray mark then costs exactly one SELECT rather than one per load.

There is a benign race: a foreign commit landing between the empty read and the clear would clear a mark that had just become valid. Cost is one doomed command, whose own rollback re-marks. Acceptable, and documented in the KDoc.

### Known consequence to measure, not to fix here

`ConcurrencyRetryScheduler` has **no jitter**, so all losers from one round retry at exactly `+25ms`, hit the stale mark together, and may each issue a SELECT before the first publisher lands. The design mitigates this by publishing the caught-up entry to the shared cache *immediately* (before the command runs, not at `afterCommit`) — later arrivals then find `sequence >= knownStoreSequence` and skip the read. Whether that is enough is an empirical question; `inventory.opt.catchup.duration{outcome="applied"}` count vs `inventory.optimistic.retry` answers it. **Do not add jitter or single-flight in this change.**

### Metrics

Existing meter names are load-bearing: `InventoryPessimisticConcurrencyTest` binds to them and `k6/bench/queries.promql` + `k6/bench/compare.py` consume `inventory_opt_catchup_total`, `inventory_opt_cache_hit_total`, `inventory_opt_cache_miss_total`. **`k6/` must not be touched** (it is shared across variant branches). So:

- **Reuse** `inventory.opt.catchup`, `.failed`, `.events`, `.duration{outcome=applied|noop|failed}` for the catch-up that now runs at load time. Semantics are unchanged; only the call site moved.
- **Add two** Prometheus-only counters (visible in Grafana, absent from `compare.py` scalars — that is fine and expected):
  - `inventory.opt.cache.stale.mark` — marks recorded.
  - `inventory.opt.cache.stale.hit` — loads that found a known-stale entry. This is the headline new number: doomed commands intercepted.

`state_load_time` tagging needs no change. The repair runs inside `AggregateLoadPath.on(REPAIR)`, which suppresses the storage engine's own `load` phase, so `{phase=load,path=repair}` stays absent (asserted by the existing integration test) and `{phase=events,path=repair}` still isolates the delta read from a cold miss. The outer `loadTimer` (`{phase=load,path=command}`, hard-coded tag) now legitimately includes repair time; the `path=repair` phases remain subtractable.

## File Structure

| File | Change |
|---|---|
| `src/main/kotlin/pl/szymanski/wiktor/config/PessimisticCachingRepository.kt` | Core change: entry field, mark on rollback, check + catch-up at load, self-clear, two counters, test accessor, KDoc rewrite |
| `src/main/kotlin/pl/szymanski/wiktor/config/AggregateLoadPath.kt` | KDoc only — `REPAIR` no longer runs "after the append failed" |
| `src/test/kotlin/pl/szymanski/wiktor/config/PessimisticCachingRepositoryStaleMarkTest.kt` | **New.** Fast unit harness (in-memory event store, no Spring, no container) |
| `src/test/kotlin/pl/szymanski/wiktor/InventoryPessimisticConcurrencyTest.kt` | Extend: assert the mark under a real race and in the deterministic foreign-append case |
| `CLAUDE.md` | One paragraph on the repair strategy, if it documents the current one |

`k6/**` — **do not modify.**

---

## Task 1: Unit harness + the marked cache entry

**Files:**
- Modify: `src/main/kotlin/pl/szymanski/wiktor/config/PessimisticCachingRepository.kt`
- Test: `src/test/kotlin/pl/szymanski/wiktor/config/PessimisticCachingRepositoryStaleMarkTest.kt` (create)

**Interfaces:**
- Produces: `internal data class Confirmed<T>(root, sequence, deleted, trigger, knownStoreSequence)`; `internal fun <T> mergeConfirmed(old: Confirmed<T>, candidate: Confirmed<T>): Confirmed<T>`; `PessimisticCachingRepository.cachedKnownSequence(id: String): Long?`; `const val UNKNOWN_SEQUENCE = -1L`.

- [ ] **Step 1: Create the branch and worktree**

```bash
cd /home/wiktor/Projects/Magisterka/InventoryItemReservation
git worktree add -b ES-4-staleMark .worktrees/ES-4-staleMark ES-4
```

All later commands run from `.worktrees/ES-4-staleMark`. Gradle needs `JAVA_HOME=~/.jdks/corretto-21.0.10`; if `build/` is root-owned from a Docker run, redirect `buildDir` with a `-I` init script rather than `sudo rm`.

- [ ] **Step 2: Write the failing unit test for the entry + merge**

Create `src/test/kotlin/pl/szymanski/wiktor/config/PessimisticCachingRepositoryStaleMarkTest.kt`. This file grows across Tasks 1–3; start with the harness and the merge tests.

> **Execution risk — this harness is greenfield.** No existing test constructs `PessimisticCachingRepository` directly, so the wiring below is derived from `AxonConfig.inventoryItemRepository` rather than copied from a working example. `InventoryItem` has a no-arg `constructor()` and `@JsonAutoDetect(fieldVisibility = ANY)`, so the Jackson deep copy works; `repository.load(id)` requires a started `CurrentUnitOfWork`, which the helpers provide. If `EmbeddedEventStore` proves awkward (it starts a background thread; call `.shutDown()` in an `@AfterEach` if tests hang), fall back to driving the storage engine through a plain `AbstractEventStore`. If the harness cannot be made to work in ~30 minutes, stop and report — do not silently degrade to integration-only coverage, since that was an explicit decision.

```kotlin
package pl.szymanski.wiktor.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.axonframework.common.lock.NullLockFactory
import org.axonframework.eventhandling.GenericDomainEventMessage
import org.axonframework.eventsourcing.EventSourcingRepository
import org.axonframework.eventsourcing.GenericAggregateFactory
import org.axonframework.eventsourcing.NoSnapshotTriggerDefinition
import org.axonframework.eventsourcing.eventstore.EmbeddedEventStore
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine
import org.axonframework.messaging.Message
import org.axonframework.messaging.unitofwork.DefaultUnitOfWork
import org.axonframework.modelling.command.ConcurrencyException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.szymanski.wiktor.domain.InventoryCreatedEvent
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import java.time.Duration
import java.util.UUID

/**
 * Fast, deterministic cover for the stale-mark repair strategy. No Spring, no container: an
 * in-memory event store plus a hand-built repository, so every case here runs in milliseconds.
 *
 * The races this logic exists for are NOT reproduced with threads — they are driven by hand:
 * a "foreign" append is published straight to the store, and a lost race is a UnitOfWork rolled
 * back with a [ConcurrencyException]. That is what makes these tests deterministic;
 * [pl.szymanski.wiktor.InventoryPessimisticConcurrencyTest] covers the real concurrent path.
 */
class PessimisticCachingRepositoryStaleMarkTest {

    private lateinit var eventStore: EventStore
    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var repository: PessimisticCachingRepository<InventoryItem>

    private val itemId: String = UUID.randomUUID().toString()

    @BeforeEach
    fun setUp() {
        eventStore = EmbeddedEventStore.builder()
            .storageEngine(InMemoryEventStorageEngine())
            .build()
        meterRegistry = SimpleMeterRegistry()
        val objectMapper = ObjectMapper().apply {
            registerModule(kotlinModule())
            findAndRegisterModules()
        }
        val builder = EventSourcingRepository.builder(InventoryItem::class.java)
            .eventStore(eventStore)
            .aggregateFactory(GenericAggregateFactory(InventoryItem::class.java))
            .snapshotTriggerDefinition(NoSnapshotTriggerDefinition.INSTANCE)
            .lockFactory(NullLockFactory.INSTANCE)
        repository = PessimisticCachingRepository(
            builder = builder,
            eventStore = eventStore,
            aggregateType = InventoryItem::class.java,
            snapshotTriggerDefinition = NoSnapshotTriggerDefinition.INSTANCE,
            objectMapper = objectMapper,
            meterRegistry = meterRegistry,
            cacheProperties = CacheProperties(enabled = true, ttl = Duration.ofMinutes(10), maximumSize = 1000),
        )
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Appends an event straight to the store, bypassing the repository — a "foreign" writer. */
    private fun foreignAppend(sequence: Long, payload: Any) {
        eventStore.publish(GenericDomainEventMessage("InventoryItem", itemId, sequence, payload))
    }

    private fun seedItem(quantity: Int = 100) =
        foreignAppend(0L, InventoryCreatedEvent(itemId, UUID.randomUUID(), quantity))

    private fun reserved(quantity: Int = 1) = InventoryReservedEvent(itemId, UUID.randomUUID(), quantity)

    /** Loads through the repository inside a UnitOfWork and commits — seeds/advances the cache. */
    private fun loadAndCommit() {
        val uow = DefaultUnitOfWork.startAndGet<Message<*>>(null)
        repository.load(itemId)
        uow.commit()
    }

    /** Loads, then rolls the UnitOfWork back with [cause] — the losing-command path. */
    private fun loadAndRollback(cause: Throwable) {
        val uow = DefaultUnitOfWork.startAndGet<Message<*>>(null)
        repository.load(itemId)
        uow.rollback(cause)
    }

    private fun counter(name: String): Double = meterRegistry.find(name).counter()?.count() ?: 0.0

    private fun catchupCount(outcome: String): Long =
        meterRegistry.find("inventory.opt.catchup.duration").tag("outcome", outcome).timer()?.count() ?: 0L

    // --- entry + merge semantics ---------------------------------------------------------------

    @Test
    fun `a newly seeded entry carries no stale mark`() {
        seedItem()
        loadAndCommit()

        assertThat(repository.cachedSequence(itemId)).isEqualTo(0L)
        assertThat(repository.cachedKnownSequence(itemId)).isEqualTo(UNKNOWN_SEQUENCE)
    }

    @Test
    fun `merge keeps the higher sequence and carries an unresolved mark forward`() {
        val old = Confirmed(root = "old", sequence = 4L, deleted = false,
            trigger = NoSnapshotTriggerDefinition.TRIGGER, knownStoreSequence = 7L)
        val candidate = Confirmed(root = "new", sequence = 5L, deleted = false,
            trigger = NoSnapshotTriggerDefinition.TRIGGER, knownStoreSequence = UNKNOWN_SEQUENCE)

        val merged = mergeConfirmed(old, candidate)

        assertThat(merged.root).isEqualTo("new")
        assertThat(merged.sequence).isEqualTo(5L)
        // 5 < 7: the store is still known to be ahead, so the mark must survive the advance.
        assertThat(merged.knownStoreSequence).isEqualTo(7L)
    }

    @Test
    fun `merge never moves the cache backwards`() {
        val old = Confirmed(root = "old", sequence = 9L, deleted = false,
            trigger = NoSnapshotTriggerDefinition.TRIGGER, knownStoreSequence = UNKNOWN_SEQUENCE)
        val candidate = Confirmed(root = "new", sequence = 5L, deleted = false,
            trigger = NoSnapshotTriggerDefinition.TRIGGER, knownStoreSequence = 3L)

        assertThat(mergeConfirmed(old, candidate)).isSameAs(old)
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

```bash
JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew test --tests "pl.szymanski.wiktor.config.PessimisticCachingRepositoryStaleMarkTest"
```
Expected: compilation failure — `UNKNOWN_SEQUENCE`, `Confirmed`, `mergeConfirmed`, `cachedKnownSequence` are unresolved (`Confirmed` is currently `private` and has four fields).

- [ ] **Step 4: Add the field, the merge function and the accessor**

In `PessimisticCachingRepository.kt`, move `Confirmed` out of the class to file scope as `internal`, add the field, and extract the merge lambda. Keep the existing KDoc on `trigger` verbatim.

```kotlin
/** Sentinel for "we have learned nothing about the store head" — see [Confirmed.knownStoreSequence]. */
internal const val UNKNOWN_SEQUENCE = -1L

/**
 * Confirmed (persisted) aggregate state at a known sequence number, plus what we have learned the
 * hard way about the store being ahead of it.
 *
 * [trigger] is cached alongside the state for the same reason Axon's `AggregateCacheEntry` keeps
 * one: [SnapshotTriggerDefinition.prepareTrigger] hands out a trigger with a ZEROED event counter,
 * so preparing a fresh one per cache hit would stop `EventCountSnapshotTriggerDefinition` from ever
 * reaching its threshold and silently disable snapshotting. The live trigger is carried forward and
 * re-attached via [SnapshotTriggerDefinition.reconfigure] instead.
 *
 * [knownStoreSequence] is a LOWER BOUND on the store head, not a staleness distance: the lowest
 * sequence number some command proved exists by failing to insert it. `sequence >= knownStoreSequence`
 * therefore means "not KNOWN to be stale" — never "provably fresh", which no cache can claim without
 * reading the store. The converse is exact: `sequence < knownStoreSequence` means the next append
 * from this state targets a sequence that is already taken and WILL conflict.
 */
internal data class Confirmed<T>(
    val root: T,
    val sequence: Long,
    val deleted: Boolean,
    val trigger: SnapshotTrigger,
    val knownStoreSequence: Long = UNKNOWN_SEQUENCE,
)

/**
 * The monotonic guard every cache write goes through, extracted from the `merge` lambdas so the
 * invariant is one testable function rather than two lambdas that must stay in step.
 *
 * State only ever moves FORWARD, and an unresolved mark survives an advance that does not reach it:
 * a writer landing at sequence 5 while another command already proved 7 exists must not present the
 * entry as fresh.
 */
internal fun <T> mergeConfirmed(old: Confirmed<T>, candidate: Confirmed<T>): Confirmed<T> =
    if (candidate.sequence > old.sequence)
        candidate.copy(knownStoreSequence = maxOf(candidate.knownStoreSequence, old.knownStoreSequence))
    else old
```

Replace both existing `merge` lambda bodies with `::mergeConfirmed`:

```kotlin
confirmed.asMap().merge(id, entry, ::mergeConfirmed)
```

Add next to `cachedSequence`:

```kotlin
/** Testing/observability: the store sequence this entry is known to be missing, or null if uncached. */
fun cachedKnownSequence(id: String): Long? = confirmed.getIfPresent(id)?.knownStoreSequence
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew test --tests "pl.szymanski.wiktor.config.PessimisticCachingRepositoryStaleMarkTest"
```
Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/pl/szymanski/wiktor/config/PessimisticCachingRepository.kt \
        src/test/kotlin/pl/szymanski/wiktor/config/PessimisticCachingRepositoryStaleMarkTest.kt
git commit -m "ES-4-staleMark: carry a known-store-sequence mark on the cache entry

Adds Confirmed.knownStoreSequence and extracts the monotonic merge guard so the
carry-forward invariant is testable. Nothing sets the mark yet.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01J9TFs2xMo1FDx9DqKmhN22"
```

---

## Task 2: Mark on rollback, replacing the eager repair

**Files:**
- Modify: `src/main/kotlin/pl/szymanski/wiktor/config/PessimisticCachingRepository.kt` (`registerCacheHooks`, new `markKnownSequence`; `catchUp`/`repair` become unreferenced but stay for Task 3)
- Test: `src/test/kotlin/pl/szymanski/wiktor/config/PessimisticCachingRepositoryStaleMarkTest.kt`

**Interfaces:**
- Consumes: `Confirmed.knownStoreSequence`, `UNKNOWN_SEQUENCE`, `cachedKnownSequence` (Task 1).
- Produces: counter `inventory.opt.cache.stale.mark`; entries whose `knownStoreSequence` is set after a concurrency rollback.

- [ ] **Step 1: Write the failing tests**

Append to `PessimisticCachingRepositoryStaleMarkTest`:

```kotlin
    @Test
    fun `a ConcurrencyException rollback marks the entry with the sequence it failed to insert`() {
        seedItem()
        loadAndCommit()                       // cache at 0
        foreignAppend(1L, reserved())         // a foreign writer takes sequence 1

        loadAndRollback(ConcurrencyException("simulated 23505"))

        // Loaded at 0, so it tried to insert 1 and lost: sequence 1 is proven to exist.
        assertThat(repository.cachedKnownSequence(itemId)).isEqualTo(1L)
        assertThat(repository.cachedSequence(itemId)).isEqualTo(0L)
        assertThat(counter("inventory.opt.cache.stale.mark")).isEqualTo(1.0)
    }

    @Test
    fun `a wrapped ConcurrencyException is still recognised`() {
        seedItem()
        loadAndCommit()

        loadAndRollback(IllegalStateException("wrapper", ConcurrencyException("simulated 23505")))

        assertThat(repository.cachedKnownSequence(itemId)).isEqualTo(1L)
    }

    @Test
    fun `a non-concurrency rollback leaves the entry unmarked`() {
        seedItem()
        loadAndCommit()

        // A business failure or a DB timeout proves NOTHING about the store head. Marking here
        // would strand the entry above its own sequence forever, costing a SELECT on every load.
        loadAndRollback(IllegalStateException("insufficient stock"))

        assertThat(repository.cachedKnownSequence(itemId)).isEqualTo(UNKNOWN_SEQUENCE)
        assertThat(counter("inventory.opt.cache.stale.mark")).isEqualTo(0.0)
    }

    @Test
    fun `a rollback no longer reads the store`() {
        seedItem()
        loadAndCommit()
        foreignAppend(1L, reserved())

        loadAndRollback(ConcurrencyException("simulated 23505"))

        // The whole point of the change: the losing command records one number and stops.
        assertThat(catchupCount("noop") + catchupCount("applied") + catchupCount("failed")).isZero()
    }

    @Test
    fun `the mark only ever moves up`() {
        seedItem()
        foreignAppend(1L, reserved())
        foreignAppend(2L, reserved())
        loadAndCommit()                       // cold miss -> cache at 2

        // Force a low mark by hand: an entry at 2 that some command proved 3 exists for.
        repository.markForTest(itemId, 3L)
        repository.markForTest(itemId, 2L)

        assertThat(repository.cachedKnownSequence(itemId)).isEqualTo(3L)
    }
```

- [ ] **Step 2: Run to verify they fail**

```bash
JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew test --tests "pl.szymanski.wiktor.config.PessimisticCachingRepositoryStaleMarkTest"
```
Expected: compilation failure on `markForTest`; the four behavioural tests would fail on `cachedKnownSequence` still being `-1`.

- [ ] **Step 3: Implement the mark**

Add the counter beside the existing ones:

```kotlin
    /**
     * Marks recorded: commands that lost a race and told the cache which sequence the winner took.
     * Deliberately NOT in `k6/bench/queries.promql`, which is shared byte-for-byte across the variant
     * branches — this series is Grafana-only.
     */
    private val staleMarkCounter = meterRegistry.counter("inventory.opt.cache.stale.mark")
```

Replace the rollback hook in `registerCacheHooks`:

```kotlin
        // onRollback fires on a failed command — lock-free, typically the lost race for baseSequence+1.
        // It records what the failure PROVED and nothing more; the store read that resolves it happens
        // at the next load, where its result is actually verified before use. See the class KDoc.
        uow.onRollback { rolledBack -> markKnownSequence(aggregate.identifierAsString(), baseSequence + 1, rolledBack) }
```

Add:

```kotlin
    /**
     * Record that [knownSequence] is taken in the store, so the next load of [id] knows this entry is
     * doomed and catches up before handing state to a command.
     *
     * Guarded on [ConcurrencyException] because a mark is PERSISTENT state, unlike the eager repair it
     * replaces. A rollback from a business failure or a DB timeout proves nothing about the store head,
     * and a mark set from one would sit permanently above the entry's own sequence — nothing would ever
     * advance the cache far enough to clear it — costing a store read on every subsequent load.
     * [repair]'s empty-delta clear is the second line of defence; this is the first.
     *
     * `computeIfPresent` gives the same per-key atomicity as [advance]'s merge, and the work inside is a
     * field copy, never I/O. An absent entry needs no mark: the next load misses and reads the store,
     * which is authoritative.
     */
    private fun markKnownSequence(id: String, knownSequence: Long, uow: UnitOfWork<*>) {
        if (!isConcurrencyFailure(uow)) return
        mark(id, knownSequence)
    }

    private fun mark(id: String, knownSequence: Long) {
        staleMarkCounter.increment()
        confirmed.asMap().computeIfPresent(id) { _, old ->
            if (knownSequence > old.knownStoreSequence) old.copy(knownStoreSequence = knownSequence) else old
        }
    }

    /**
     * Whether this rollback was a lost race. `AbstractUnitOfWork.commitAsRoot` calls `setRollbackCause`
     * BEFORE `changePhase(Phase.ROLLBACK)`, so the cause is already on the UnitOfWork when rollback
     * handlers run. The cause chain is walked rather than the top exception tested, matching
     * [ConcurrencyRetryScheduler] — the 23505 arrives wrapped.
     */
    private fun isConcurrencyFailure(uow: UnitOfWork<*>): Boolean {
        val cause: Throwable = uow.executionResult?.exceptionResult ?: return false
        return generateSequence(cause) { it.cause }.any { it is ConcurrencyException }
    }

    /** Testing: record a mark directly, without staging a rollback. */
    internal fun markForTest(id: String, knownSequence: Long) = mark(id, knownSequence)
```

Add imports: `org.axonframework.messaging.unitofwork.UnitOfWork`, `org.axonframework.modelling.command.ConcurrencyException`.

- [ ] **Step 4: Run to verify they pass**

```bash
JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew test --tests "pl.szymanski.wiktor.config.PessimisticCachingRepositoryStaleMarkTest"
```
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "ES-4-staleMark: mark the cache on a lost race instead of repairing it

onRollback now records baseSequence+1 (the sequence the winner proved it holds)
and stops. Guarded on ConcurrencyException: a mark is persistent state, so a
business or infrastructure rollback must not set one. The store read that
resolves the mark moves to the load path in the next commit.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01J9TFs2xMo1FDx9DqKmhN22"
```

---

## Task 3: Check and catch up at load

**Files:**
- Modify: `src/main/kotlin/pl/szymanski/wiktor/config/PessimisticCachingRepository.kt` (`loadFromCacheOrStore`, `catchUp`, `repair`)
- Test: `src/test/kotlin/pl/szymanski/wiktor/config/PessimisticCachingRepositoryStaleMarkTest.kt`

**Interfaces:**
- Consumes: `mergeConfirmed`, `Confirmed.knownStoreSequence`, `mark`/`markForTest` (Tasks 1–2).
- Produces: counter `inventory.opt.cache.stale.hit`; `catchUp(id, current): Confirmed<T>?` returning the entry now in the cache (or null when nothing changed or the repair failed).

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test
    fun `a load on a known-stale entry catches up before handing state to the command`() {
        seedItem()
        loadAndCommit()                       // cache at 0
        foreignAppend(1L, reserved())
        loadAndRollback(ConcurrencyException("simulated 23505"))   // mark = 1
        assertThat(repository.cachedSequence(itemId)).isEqualTo(0L)

        loadAndCommit()                       // the retry

        assertThat(repository.cachedSequence(itemId)).isEqualTo(1L)
        assertThat(repository.cachedKnownSequence(itemId)).isEqualTo(1L)   // 1 >= 1, resolved
        assertThat(counter("inventory.opt.cache.stale.hit")).isEqualTo(1.0)
        assertThat(counter("inventory.opt.catchup")).isEqualTo(1.0)
        assertThat(catchupCount("applied")).isEqualTo(1L)
    }

    @Test
    fun `a load on an unmarked entry never touches the store`() {
        seedItem()
        loadAndCommit()

        loadAndCommit()
        loadAndCommit()

        assertThat(counter("inventory.opt.cache.stale.hit")).isZero()
        assertThat(catchupCount("noop") + catchupCount("applied") + catchupCount("failed")).isZero()
        assertThat(counter("inventory.opt.cache.hit")).isEqualTo(2.0)
    }

    @Test
    fun `catching up spans a multi-event gap in one read`() {
        seedItem()
        loadAndCommit()
        foreignAppend(1L, reserved())
        foreignAppend(2L, reserved())
        foreignAppend(3L, reserved())
        loadAndRollback(ConcurrencyException("simulated 23505"))   // mark = 1

        loadAndCommit()

        // The mark only proved sequence 1, but the read is "everything from 1", so it lands at head.
        assertThat(repository.cachedSequence(itemId)).isEqualTo(3L)
        assertThat(catchupCount("applied")).isEqualTo(1L)
        assertThat(meterRegistry.find("inventory.opt.catchup.events").summary()!!.totalAmount()).isEqualTo(3.0)
    }

    @Test
    fun `a mark that proves wrong is cleared after a single empty read`() {
        seedItem()
        loadAndCommit()
        // A ConcurrencyException with no foreign event behind it: the mark is a lie.
        loadAndRollback(ConcurrencyException("simulated 23505"))
        assertThat(repository.cachedKnownSequence(itemId)).isEqualTo(1L)

        loadAndCommit()                       // pays one empty read, then clears the mark
        assertThat(catchupCount("noop")).isEqualTo(1L)

        loadAndCommit()                       // must NOT pay again
        assertThat(catchupCount("noop")).isEqualTo(1L)
        assertThat(counter("inventory.opt.cache.stale.hit")).isEqualTo(1.0)
    }
```

- [ ] **Step 2: Run to verify they fail**

```bash
JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew test --tests "pl.szymanski.wiktor.config.PessimisticCachingRepositoryStaleMarkTest"
```
Expected: the four new tests FAIL — `inventory.opt.cache.stale.hit` does not exist, `cachedSequence` stays at 0, catch-up counters stay 0.

- [ ] **Step 3: Wire the check into the hit arm**

Add the counter:

```kotlin
    /**
     * Loads that found a known-stale entry and caught up rather than handing a command state whose
     * next append was already guaranteed to collide. The headline number for this branch: doomed
     * commands intercepted. Grafana-only, like [staleMarkCounter].
     */
    private val staleHitCounter = meterRegistry.counter("inventory.opt.cache.stale.hit")
```

Replace the hit arm of `loadFromCacheOrStore` (everything after `hitCounter.increment()`):

```kotlin
        hitCounter.increment()
        // Exact, not heuristic: sequence < knownStoreSequence means the sequence this state would
        // append is already taken, so the command is doomed before it starts. Paying a delta read
        // here buys a command that can succeed, in place of a certain conflict plus 25ms of backoff
        // and one of only five retries.
        val entry = if (cached.sequence < cached.knownStoreSequence) {
            staleHitCounter.increment()
            catchUp(aggregateIdentifier, cached) ?: cached   // repair is best-effort; stale is survivable
        } else {
            cached
        }
        // reconfigure (NOT prepareTrigger): keeps the snapshot event counter running across commands.
        // Without a lock, concurrent commands on one aggregate share this trigger instance and race on
        // its counter. That race is benign — it can only make the snapshot cadence slightly irregular,
        // never produce a wrong snapshot — whereas prepareTrigger would hand out a ZEROED counter per
        // command and stop the threshold from ever being reached, silently disabling snapshotting.
        val trigger = snapshotTriggerDefinition.reconfigure(aggregateType, entry.trigger)
        val copySample = Timer.start(meterRegistry)
        val aggregate = EventSourcedAggregate.reconstruct(
            deepCopy(entry.root), aggregateModel(), entry.sequence, entry.deleted, eventStore, trigger,
        )
        copySample.stop(copyTimer)
        validateOnLoad(aggregate, expectedVersion)
        registerCacheHooks(entry.sequence, aggregate)
        return aggregate
```

Note `registerCacheHooks(entry.sequence, ...)` — the base sequence must be the **caught-up** one, so a later mark records the right number.

- [ ] **Step 4: Rewrite `catchUp` / `repair` to return the entry and self-clear**

```kotlin
    /**
     * Bring a known-stale entry up to date: read only the delta it is missing and replay it onto a
     * copy, then publish the result so every concurrent loser of the same race finds it fresh instead
     * of issuing the same read. Returns the entry now in the cache, or null if nothing moved.
     *
     * Runs on the LOAD path, before the command executes — that is the whole point of the mark. It is
     * still tagged [AggregateLoadPath.REPAIR] because it is repair work, not this command's state load:
     * the tag keeps `state_load_time{phase=events,path=command}` meaning "cold miss" and leaves the
     * repair subtractable from the `{phase=load}` envelope that now legitimately contains it.
     *
     * Best-effort and NON-destructive: on any failure the cache is left untouched (never invalidated),
     * the caller proceeds on stale state, and the append conflicts exactly as it would have anyway. The
     * mark survives, so the next load tries again — unlike the eager repair this replaces, a failed
     * catch-up no longer strands the aggregate.
     */
    private fun catchUp(id: String, current: Confirmed<T>): Confirmed<T>? =
        AggregateLoadPath.on(AggregateLoadPath.REPAIR) { repair(id, current) }

    private fun repair(id: String, current: Confirmed<T>): Confirmed<T>? {
        val sample = Timer.start(meterRegistry)
        var outcome = catchupNoop
        try {
            val delta = eventStore.readEvents(id, current.sequence + 1)
            if (!delta.hasNext()) return clearMark(id, current)
            // Throwaway trigger: this replay is a cache repair, not command execution — it must not
            // schedule a snapshot, nor advance the live counter.
            val aggregate = EventSourcedAggregate.reconstruct(
                deepCopy(current.root), aggregateModel(), current.sequence, current.deleted, eventStore,
                NoSnapshotTriggerDefinition.TRIGGER,
            )
            aggregate.initializeState(delta) // replays the delta onto the pre-seeded root (no re-publish)
            val newSequence = aggregate.version() ?: return null
            if (newSequence <= current.sequence) return null
            // Keep the live trigger; only the state moved forward. The mark is left to mergeConfirmed,
            // which drops it once the sequence reaches it.
            val entry = Confirmed(
                deepCopy(aggregate.aggregateRoot), newSequence, aggregate.isDeleted, current.trigger,
                current.knownStoreSequence,
            )
            catchupCounter.increment()
            catchupEvents.record((newSequence - current.sequence).toDouble())
            outcome = catchupApplied
            return confirmed.asMap().merge(id, entry, ::mergeConfirmed)
        } catch (e: Exception) {
            outcome = catchupFailedTimer
            catchupFailed.increment()
            log.warn("[PES] delta catch-up FAILED for {} at seq {} — the command will run on stale " +
                "state and conflict; the mark survives, so the next load retries the repair", id, current.sequence, e)
            return null
        } finally {
            sample.stop(outcome)
        }
    }

    /**
     * The mark was wrong: the store has nothing past [current]'s sequence. Lower it so this entry does
     * not pay a store read on every subsequent load. Only applied while the entry has not moved, so a
     * newer writer's information is never discarded.
     *
     * A foreign commit landing between the empty read and this clear would wipe a mark that had just
     * become valid. That costs exactly one doomed command, whose own rollback re-marks the entry — the
     * same self-correcting loop the design rests on everywhere else.
     */
    private fun clearMark(id: String, current: Confirmed<T>): Confirmed<T>? =
        confirmed.asMap().computeIfPresent(id) { _, old ->
            if (old.sequence == current.sequence && old.knownStoreSequence > old.sequence)
                old.copy(knownStoreSequence = old.sequence)
            else old
        }
```

- [ ] **Step 5: Run the whole unit class to verify it passes**

```bash
JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew test --tests "pl.szymanski.wiktor.config.PessimisticCachingRepositoryStaleMarkTest"
```
Expected: PASS, 12 tests.

- [ ] **Step 6: Commit**

```bash
git add -A src/main src/test
git commit -m "ES-4-staleMark: resolve the mark at load, before the command runs

A cache hit on an entry whose sequence is below its known store sequence now
catches up first and publishes the result, so concurrent losers of the same race
find it fresh instead of repeating the read. An empty delta clears the mark, so a
mark that proves wrong costs one read rather than one per load.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01J9TFs2xMo1FDx9DqKmhN22"
```

---

## Task 4: Documentation

**Files:**
- Modify: `src/main/kotlin/pl/szymanski/wiktor/config/PessimisticCachingRepository.kt` (class KDoc)
- Modify: `src/main/kotlin/pl/szymanski/wiktor/config/AggregateLoadPath.kt` (KDoc)
- Modify: `CLAUDE.md` (only if it describes the repair strategy — check first)

No test cycle: this task is prose. It is separate because the KDoc in this file is load-bearing documentation that a reviewer should be able to reject on its own.

- [ ] **Step 1: Rewrite the cache-lifecycle section of the class KDoc**

Replace the `Cache lifecycle:` block and the two paragraphs above it. The claims that are now false and must go:
- "**A cache hit CAN be stale, and that is by design.** … The stale read is not a correctness hole: it is caught at append time" — still true for *unknown* staleness, but must no longer imply that a *known*-stale entry is served.
- "onRollback -> incremental catch-up: read just the missing delta" — the rollback no longer reads.

New lifecycle block:

```
 * Cache lifecycle:
 *  - load (hit, unmarked) -> deep-copy the confirmed root, reconstruct at seq N (NO replay),
 *                   re-attaching the cached [SnapshotTrigger] so the snapshot counter survives.
 *  - load (hit, marked)   -> the entry is KNOWN doomed (sequence < knownStoreSequence): read the
 *                   delta, replay onto a copy, publish it, and run the command on that.
 *  - load (miss) -> cold replay via `super` (snapshot + tail), then seed the cache.
 *  - afterCommit -> monotonically advance the cache to the just-persisted state (confirmed only).
 *  - onRollback  -> on a ConcurrencyException ONLY, record that baseSequence+1 exists in the store.
 *                   No store read: the failure is turned into one number, and the read that resolves
 *                   it happens at the next load, where the result is verified before use.
```

And a paragraph replacing the old staleness note:

```
 * **A cache hit can still be stale, but never KNOWABLY stale.** Nothing here detects a commit made on
 * another node: that entry looks fresh, is served, and the conflict is caught at append by the unique
 * constraint exactly as before. What the mark removes is the case the node already knew about — a
 * command that lost a race told the entry which sequence the winner took, and serving that entry again
 * would be handing out a guaranteed failure. `sequence >= knownStoreSequence` is therefore "not known
 * stale", never "fresh"; the store remains the only authority.
```

- [ ] **Step 2: Fix the `REPAIR` KDoc in `AggregateLoadPath.kt`**

The current text says catch-up "runs on the losing command's thread but AFTER its append failed, so it is the cost of the conflict, not the cost of the write." That is now wrong. Replace that bullet:

```
 *  - **[REPAIR]** — [PessimisticCachingRepository] resolving a stale mark: reading the delta a known-
 *    stale cache entry is missing. It runs INSIDE the load, before the command executes, so unlike the
 *    post-rollback repair it replaced it is genuinely on the write path — the `{phase=load}` envelope
 *    legitimately contains it. The tag is what keeps it subtractable, and keeps
 *    `{phase=events,path=command}` meaning "cold miss" rather than pooling the two reads.
```

- [ ] **Step 3: Check and update `CLAUDE.md`**

```bash
grep -n "catchUp\|catch-up\|repair\|rollback" CLAUDE.md
```
If it describes the eager repair, replace that description with the mark. If it does not mention it, make no change.

- [ ] **Step 4: Commit**

```bash
git add -A src/main CLAUDE.md
git commit -m "ES-4-staleMark: document the mark-and-resolve repair strategy

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01J9TFs2xMo1FDx9DqKmhN22"
```

---

## Task 5: Integration coverage and full verification

**Files:**
- Modify: `src/test/kotlin/pl/szymanski/wiktor/InventoryPessimisticConcurrencyTest.kt`

**Interfaces:**
- Consumes: `cachedKnownSequence` (Task 1), `inventory.opt.cache.stale.mark` / `.stale.hit` (Tasks 2–3).

The existing four tests should pass **unchanged** — verify that before editing. In particular `catchUp repairs the cache after a foreign append` still holds end-to-end: the command loads at 1, collides with the forged event at 2, marks, and the retry's load resolves the mark and lands at 3, so `cachedSequence == seqBefore + 2` and `inventory.opt.catchup >= 1` are both still true. Only its inline comment ("rolls back, catchUp pulls in the foreign event") needs correcting.

- [ ] **Step 1: Run the existing integration test unchanged**

```bash
JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew test --tests "pl.szymanski.wiktor.InventoryPessimisticConcurrencyTest"
```
Expected: PASS, 4 tests. **If any fails, stop and diagnose — the change was meant to be behaviour-preserving from the outside.** Requires a running Docker daemon for Testcontainers.

- [ ] **Step 2: Add the failing assertions**

In `catchUp repairs the cache after a foreign append, so the retry succeeds`, correct the comment and assert the mark is observable at the point the design says it exists. Split the single `sendAndWait` so the mark can be inspected between the failure and the retry is **not** possible (the gateway retries internally), so assert the end state plus the new counters instead:

```kotlin
        val markedBefore = meterRegistry.get("inventory.opt.cache.stale.mark").counter().count()
        val staleHitsBefore = meterRegistry.get("inventory.opt.cache.stale.hit").counter().count()

        // Collides at seqBefore+1, rolls back and MARKS the entry with that sequence; the retry's
        // load sees sequence < knownStoreSequence, catches up, and lands at +2.
        gateway.sendAndWait<Any?>(SagaReserveItemCommand(id = itemId, quantity = 1))

        assertThat(meterRegistry.get("inventory.opt.cache.stale.mark").counter().count() - markedBefore)
            .`as`("the lost race recorded a mark").isGreaterThanOrEqualTo(1.0)
        assertThat(meterRegistry.get("inventory.opt.cache.stale.hit").counter().count() - staleHitsBefore)
            .`as`("the retry's load resolved the mark").isGreaterThanOrEqualTo(1.0)
        assertThat(inventoryItemRepository.cachedKnownSequence(itemId))
            .`as`("the mark is resolved once the entry reaches it")
            .isNotNull().isLessThanOrEqualTo(inventoryItemRepository.cachedSequence(itemId))
```

In `concurrent reserves on one item stay consistent with no over-reservation`, add after the existing assertions:

```kotlin
        assertThat(meterRegistry.get("inventory.opt.cache.stale.mark").counter().count())
            .`as`("real contention recorded marks").isGreaterThan(0.0)
        assertThat(meterRegistry.get("inventory.opt.cache.stale.hit").counter().count())
            .`as`("marks were resolved at load, not left to rot").isGreaterThan(0.0)
        assertThat(inventoryItemRepository.cachedKnownSequence(itemId))
            .`as`("no mark outlives the run: the cache settled at head")
            .isLessThanOrEqualTo(inventoryItemRepository.cachedSequence(itemId))
```

Keep the existing `state_load_time{phase=load,path=repair}` null assertion — it must still hold, because `AggregateLoadPath.on(REPAIR)` suppresses the storage engine's `load` phase and the outer `loadTimer` hard-codes `path=command`.

- [ ] **Step 3: Run and verify**

```bash
JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew test --tests "pl.szymanski.wiktor.InventoryPessimisticConcurrencyTest"
```
Expected: PASS, 4 tests, with the new assertions exercised.

- [ ] **Step 4: Run the full suite**

```bash
JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew build
```
Expected: BUILD SUCCESSFUL. `SagaCommandFailureIT` is a live check that the injected-`ConcurrencyException` path still ends orders `REJECTED` — its interceptor throws a *synthetic* conflict with no foreign event behind it, which is exactly the case `clearMark` exists for.

- [ ] **Step 5: Commit**

```bash
git add -A src/test
git commit -m "ES-4-staleMark: assert the mark under real contention and a forged conflict

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01J9TFs2xMo1FDx9DqKmhN22"
```

---

## Verification

**Automated:**
```bash
cd .worktrees/ES-4-staleMark
JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew build
```
12 new unit tests plus the 4 existing integration tests, all green. `git diff ES-4 -- k6/` must be **empty**.

**End-to-end, against the real stack:** use the `verify` skill, or the benchmark harness on `main`. Register `ES-4-staleMark` in the suite's variants list before running. Remember the branch-switch traps: an old API container may still hold `:8080`, and a stale Prometheus config bind-mount needs `--force-recreate`.

**What the A/B against `ES-4` should show**, on the same profile (high `DISTINCT_ITEMS` contention, ideally with `RESERVE_DELAY_MS > 0` to widen the conflict window):

| Metric | Expectation |
|---|---|
| `inventory_optimistic_exhausted_total` | **Lower** — the primary hypothesis. Retries now start from verified state. |
| `saga_completed{outcome="command_failed"}` | **Lower**, for the same reason. |
| `inventory_opt_catchup_total` | Lower or flat — repairs now happen only when needed, not on every rollback. |
| `inventory_opt_catchup_duration{outcome="noop"}` count | **Much lower** — the eager probe is gone; the only noops left are cleared bad marks. |
| `inventory_opt_cache_stale_hit_total` | New series: doomed commands intercepted. |
| `state_load_time{phase="load",path="command"}` p95 | May rise slightly — the repair read now sits inside the load. Subtract `{phase="events",path="repair"}` to compare like for like against ES-4. |
| Throughput | The number the thesis wants. Could go either way; the herd effect below is the risk. |

**Watch for the thundering herd.** `ConcurrencyRetryScheduler` has no jitter, so all losers retry at `+25ms` together. If `inventory_opt_catchup_duration{outcome="applied"}` count comes out close to `inventory_optimistic_retry_total` rather than well below it, the immediate publish is not deduplicating and the losers are each paying their own read. That is a finding, not a bug — record it; adding jitter or single-flight is a separate change.
