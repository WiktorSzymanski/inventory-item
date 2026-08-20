# Retired variants

Branches that are **not** in `variants.env` and therefore not built, run, or benchmarked by
anything on `main`. They are kept here because each one records why it was pulled and what
restoring it would cost — the expensive half of the knowledge, and the half that gets re-learned
the hard way when a retired pair is re-run without its caveats.

`variants.env` is the registry and the only thing the scripts read. Nothing in this file is
machine-readable; adding a row back is a one-line edit there, and every entry below says what has
to be true first.

The live set as of 2026-08-20 is seven: `TO-1`, `TO-2`, `TO-3`, `TO-4`, `ES-1`, `ES-2`, `ES-4`.

## Contents

| Branch | Retired | Exists |
|---|---|---|
| [`TO-2-opt`](#to-2-opt) | 2026-08-20 | origin only |
| [`ES-3`](#es-3) | 2026-08-20 | local + origin |
| [`TO-3-pessimistic`](#to-3-pessimistic) | 2026-08-17 | local + origin |
| [`TO-2-newT` / `TO-3-newT`](#to-2-newt-and-to-3-newt) | 2026-08-19 | local + origin |
| [`ES-4-NullLock-A`](#es-4-nulllock-a) | 2026-08-17 | local only, never pushed |
| [`ES-4-NullLock-mod`](#es-4-nulllock-mod) | never a row | origin only |
| [`ES-4-NullLock-retry`](#es-4-nulllock-retry) | never a row | origin only |


---

## TO-2-opt

`TO-2-opt` was RETIRED FROM THE REGISTRY on 2026-08-20, when the registry was cut to the seven
variants the campaign actually runs. It was a row until then; it is a branch only now, and an
ORIGIN-ONLY one — there is no local `TO-2-opt`.

Two reasons. It was not carrying its weight: `--only TO-2,TO-2-opt` had stopped being a
delivery-trigger A/B (see **Not ported**, below), so the suite was paying a full run per campaign
step for a pair that varied two things. And a suite pass could not build it at all without
fetching the branch first.

**WHAT IT WAS.** The same kind of entry on TO-2 that `TO-3-pessimistic` is on TO-3: the outbox
DELIVERY TRIGGER changed, nothing else. TO-2 submits one executor task per NOTIFY, an open loop
with no backpressure, so a commit burst becomes a delivery burst that drives the order-worker pool
into row contention. TO-2-opt discards the notification payload and treats NOTIFY as a wake-up
only: a burst coalesces into one drain pass that delivers in bounded pages, blocking on each page
before fetching the next — TO-1's closed-loop `drain()` contract with the scheduler tick replaced
by a signal. EVENT_DELIVERY_THREADS, ORDER_WORKER_THREADS, the V2 trigger and the schema are
unchanged, so the pair isolated trigger SHAPE. It ran as `--only TO-2,TO-2-opt`.

It existed because the W-base breakpoint pair put TO-2 at db_write p95 404 ms / conflict_ratio
4.64 / 41% rejected and an undrained 439k backlog, against TO-1's 0.96 ms / 0.37 / 0.25%. TO-1
reaches that only by lagging delivery ten minutes (publish_lag p95 pegged at the 600 s clamp),
i.e. accidental admission control. The question TO-2-opt was built to answer is whether
backpressure alone buys TO-1's write profile at TO-2's delivery latency — so in any archived pair,
read `publish_lag_p95` alongside the contention signals, not instead of them. **The question is
still open**, and TO-2 itself has since fixed the collapse that prompted it (2026-08-19), which is
the other reason the row was not worth keeping: the branch is a snapshot of a problem its parent
no longer has.

One knob was TO-2-opt's alone: EVENT_DELIVERY_BATCH_SIZE (default 1000), publications per drain
page. It was never in the capabilities column, not being a workload knob — k6 never sends it and
no point in points.env sets it.

---

## ES-3

`ES-3` was RETIRED FROM THE REGISTRY on 2026-08-20, in the same cut as `TO-2-opt`. The branch is
untouched and still exists both locally and on origin — only the row is gone.

It is the odd one out of the ES family in two ways at once, and that is what made it expensive to
keep in a suite pass:

1. **It is the last `PessimisticLockFactory` implementation.** ES-1, ES-2 and ES-4 went lock-free
   on 2026-08-20 (see the adoption note in `variants.env`). ES-3 cannot follow: it caches with
   Axon's `WeakReferenceCache` and has no copy-on-write, so without a lock concurrent commands
   would mutate one shared aggregate root. Only ES-4's repository deep-copies per load, which is
   what makes ES-4 safe without one.
2. **It kept the two-lane retry shape** — 82 command + 30 EXECUTING retry — because the retry port
   of 2026-08-20 rode along with the lock-free work it cannot take. The other three are 112 + 0.

So an ES-3 column in any table differs from its neighbours in the lock, the retry topology AND the
lane widths, and no single-variable reading of it is available. `saga_pool_active{pool="retry"}` is
genuinely busy there where it sits near 0 on the other three.

One thing it is still the only source of: evaluate.py's `saga_command_failed_single_node` VALIDITY
check assumes a JVM-local lock makes a 23505 impossible at REPLICAS=1. ES-3 is the only ES variant
that assumption still holds for.

Re-adding a row means accepting a three-variable comparison, or porting the retry topology to it
first — which is possible on its own, the lock and the pools being independent.

---

## TO-3-pessimistic

`TO-3-pessimistic` was RETIRED FROM THE REGISTRY on 2026-08-17 and is a branch only. It is not a
fifth TO design; it is TO-3 with the reserve path changed from optimistic `@Version` retry to
`SELECT … FOR UPDATE` (6 files, all under src/), and it forked from the PRE-merge TO-3.

It was pulled because the pair it existed for stopped being a pair. TO-3 has since absorbed the
retry rebuild and the pool split (see `variants.env`) and TO-3-pessimistic did not, so
`--only TO-3,TO-3-pessimistic` varied the lock, the retry mechanism, the pool topology and the
thread widths at once — four dimensions, presented as a locking A/B. Restoring it to the suite
means porting the TO-3 merge to it first; re-add a row here when that is done.

---

## Not ported: TO-2-opt and TO-3-pessimistic

Both still carry the blocking `@Retryable`, one flat pool and their own widths, where TO-1..TO-4
took the retry rebuild and then the single-pool merge. This is why neither pair is single-variable
any more.

```
  `--only TO-3,TO-3-pessimistic` is no longer a lock A/B — it now varies the lock, the retry
      mechanism, the pool topology and the widths at once. TO-3-pessimistic was retired from the
      registry over exactly this, so that pair cannot be run by the suite at all until it is
      ported and re-added.
  `--only TO-2,TO-2-opt` is no longer a delivery-trigger A/B either — TO-2 has the retry
      rebuild and TO-2-opt does not, so the pair compares two retry mechanisms as well as two
      triggers. It ran that way while TO-2-opt was still a row, so treat any archived
      `TO-2-opt_*` run accordingly.
```
Note both still read ORDER_WORKER_THREADS and HTTP_THREADS. Compose's defaults are 200/99, which
for ORDER_WORKER_THREADS is the same 200 their own yaml asks for, so the retry-mechanism
difference is measured at the SAME width as the merged-pool branches. That is deliberate: a
blocking backoff is a property of the mechanism, and pricing it at a narrower pool than its
comparison would confound the two.

---

## TO-2-newT and TO-3-newT

`TO-2-newT` and `TO-3-newT` were RETIRED FROM THE REGISTRY on 2026-08-19 and are branches only.
They are not a fifth and sixth TO design and they are no longer an A/B with anything: the change
they existed to measure was ported into TO-1, TO-2, TO-3 and TO-4 on 2026-08-18, so each one is
now a snapshot of code its own parent already carries.

WHAT THEY WERE. The reserve path split by transaction boundary. TO-2 and TO-3 used to reserve an
order in one transaction — a command per line, each joining it, each doing SELECT -> reserve() ->
versioned UPDATE -> INSERT reservations -> INSERT event_publication — so line 1's exclusive row
lock was held across every later line's read, its reserveDelayMs sleep and its outbox write:
3N+4 round trips, N of them under the lock. The -newT branches split that into read (one
findAllById for the whole order), modify (reserve() per line against an in-memory working copy)
and write, with only the write transactional and the versioned batch UPDATE of inventory_state
issued LAST, after the outbox rows, the reservations and the order. Plus jittered retry backoff,
which the branches did not have then: `delayMsFor` spreads the shared 25/50/100/200 ms curve
uniformly over `[0.5 x base, 1.5 x base)`, symmetric so the mean and therefore
`order_retry_backoff_time` stay comparable across the family.

WHY THEY ARE GONE. Both properties are TO-1/2/3/4's baseline as of 2026-08-18 (two commits each,
"read and decide outside the transaction, write in one batch" then "jitter the retry backoff"),
so `--only TO-2,TO-2-newT` and `--only TO-3,TO-3-newT` measured noise. Verified by diff, not
assumed: outside src/ the pairs are identical — same application.yaml, docker-compose.yml,
migrations, build.gradle.kts — and inside it the ONLY non-comment difference is that TO-1/2/3/4
carry `InventoryVersionConflictException` (an OptimisticLockingFailureException naming the row
that lost) and have `InventoryBatchWriter.updateAll` return the written rows, where the -newT
branches throw the plain superclass and return Unit. Neither is observable on TO-2 or TO-3: the
retry in `InventoryService.runOrderTask` catches the superclass either way, and the return value
is discarded at the sole call site. Both exist only so TO-4 can share the file byte-for-byte —
it is the one branch that catches the subclass, to evict exactly the stale cache entry, and the
one that binds the return value, to feed its version-guarded post-commit merge. Everything else
in the six touched files is reworded prose.

WHAT MOVED WITH THE SHAPE, and still has to be read on TO-1/2/3/4 rather than on a pair:

1. Reads are no longer serialised by row locks, so conflicts and retries are HIGHER than the
```
   per-line path's while lock hold time is lower. That trade is the design, not a regression.
   Read `conflict_ratio` and the rejected share next to `db_write` latency, never one without
   the other.
```

2. The reserveDelayMs sleep holds neither a row lock NOR a Hikari connection, where the per-line
```
   path held both for its duration. Against 350 connections and 200 worker threads that is a
   second, distinct effect of the same change; a RESERVE_DELAY_MS=0 point isolates effect 1.
```

3. `state_load_time` and `state_persist_time` are one sample per ORDER, where the per-line path
```
   recorded one per LINE. Names and tags are unchanged, so every panel resolves and every
   comparison against a PRE-2026-08-18 run silently compares different quantities — divide the
   older figures by ITEMS_PER_ORDER, or compare the *_count series to see the ratio directly.
   This is now a caveat about run ARCHIVES, not about a sibling variant.
```

4. Spring Data does not instrument `InventoryBatchWriter` (it is JdbcTemplate), so the "Spring
```
   Data repository invocations" panel undercounts DB work on every TO branch now.
```

THE COMPARISON FOR TRANSACTION SHAPE is no longer in the registry. The per-line path survives
only on TO-2-opt, TO-3-pessimistic, TO-3-mod-A and TO-3-oneExec, each of which varies the retry
mechanism and its own thing as well — see [Not ported](#not-ported-to-2-opt-and-to-3-pessimistic).
Isolating the boundary again means
porting the split to one of them, or forking a branch that changes only it.

Re-adding a row here would produce two samples of the same binary. If the branches are wanted as
history, leave them unmerged; nothing in the suite reads them.

---

## ES-4-NullLock-A

`ES-4-NullLock-A` was RETIRED FROM THE REGISTRY on 2026-08-17 and is a branch only — LOCAL ONLY at
that date, never pushed to origin. It forks from the PRE-adoption ES-4-NullLock (today's ES-4)
with the order-saga moved from a
TrackingEventProcessor to a PooledStreamingEventProcessor. The domain write path is byte-identical
— NullLockFactory, copy-on-write repository, cache-fed snapshotter, retry policy — and
total-segments stays 60, so token_entry is compatible in BOTH directions and switching between the
pair needs no token reset.

Two reasons it was pulled, and the second is the one to fix before re-adding a row:

```
  1. `--only ES-4-NullLock,ES-4-NullLock-A` stopped being single-variable, varying processor type
     and lane widths together. Its baseline has since moved twice more — to command 82 / retry 30 /
     Tomcat 99, and then to 112 / 0 as today's ES-4 — widening the gap.
  2. THE DESCRIPTION BELOW IS AHEAD OF THE CODE. It says the envelope change moved the command
     pool to 91, capped Tomcat at 45 and raised axon.jdbc.pool.size to 350. The branch as it
     stands has COMMAND_POOL_SIZE 64, RETRY_POOL_SIZE 23, no `server.tomcat` block at all (so
     Boot's default 200) and never reads HTTP_THREADS. The `12 http + 91 command` arithmetic
     below was never implemented. Reconcile the two before benching it again — a run against
     these notes would be attributed to lane widths the branch does not have.
```

Everything below about TEP vs PSEP mechanics is still accurate and worth keeping; only the lane
arithmetic is aspirational.

TEP spends one thread PER SEGMENT, and that is structural rather than a sizing choice:
forParallelProcessing(n) sets maxThreadCount = n and WorkerLauncher claims a segment only while
availableThreads > 0, so fewer threads means permanently unclaimed segments and orders stranded in
PENDING. Sixty segments cost sixty threads holding up to 120 of the 300-connection Axon pool while
doing no aggregate work: the saga handler updates saga state and SUBMITS to sagaCommandExecutor.
Each TEP worker also opens its OWN stream, and one that falls further behind than
EmbeddedEventStore's 10 000-event shared cache takes a PRIVATE connection with an open ResultSet
until it catches up — worst exactly during a saturation run.

PSEP decouples the two: one Coordinator thread owns a SINGLE event stream and feeds per-segment
WorkPackages onto a shared pool of `axon.saga.worker-threads` (default 64, SAGA_WORKER_THREADS).
```
  ES-4-NullLock    2 x (64 + 23 + 60 + 3) = 300 connections — exactly the pool, no headroom
  ES-4-NullLock-A  2 x (12 + 64 + 31 + 64 + 1 + 3) = 350 — exactly the pool
```

The point of PSEP is NOT that the saga runs on fewer threads. It is that the saga width stops
being pinned to the segment count and becomes a free variable — which is what let it be RAISED
from 12 to 64 once measurement showed the saga lane, not the pool, was the constraint. Its
threads come from capping Tomcat at 12. total-segments moves 60 -> 64 to match the worker width,
so token_entry is no longer interchangeable with ES-4-NullLock: switching needs a token reset
(reset.sh already truncates token_entry every run).

Both of those sums omitted the ACCEPT lane until 2026-08-16, and that was the larger error.
POST /inventory/orders calls sendAndWait, SimpleCommandBus handles on the CALLING thread, so a
Tomcat thread runs the command handler itself and holds two axon-jdbc-pool connections for the
whole request. Against Boot's default 200-thread connector that is up to 400 connections nobody
budgeted, on top of a sum already sized to exactly fill the pool. An overrun does not fail
cleanly: axonDataSource sets connectionTimeout=5000 and ConcurrencyRetryScheduler will not retry
a SQLTransientConnectionException, so a starved command stalls 5s and fails TERMINALLY, reading
as latency and a rejection rate. Suspect it in any pre-2026-08-16 ES saturation run —
`hikaricp_connections_timeout_total{pool="axon-jdbc-pool"}` is the series that shows it.

NO EXPORT NEEDED: docker-compose's AXON_JDBC_POOL_SIZE default is 350 as of 2026-08-16, which is
exactly this branch's demand. The variable still OVERRIDES the branch's application.yaml whatever
it is set to, so a run that exports a smaller value silently wins; the API logs what actually took
effect as `[POOLS]` at startup and warns when the pool is short.

It publishes `saga_pool_{active,queued,size}` for `pool="saga-worker"`, `pool="command"` and
`pool="retry"`, named to match -mod's gauges so one panel covers both. `saga_pool_queued` at 0
while order throughput is flat means that pool was never the cap; `saga_pool_active{pool="retry"}`
pinned at its size means the run measured retry width rather than the persistence model.

It is a change to the ES INFRASTRUCTURE rather than to the domain, so if it wins it has to land on
all four registered ES variants before any cross-variant table is rebuilt — otherwise ES-vs-ES
numbers mix two processor architectures.

---

## ES-4-NullLock-mod

`ES-4-NullLock-mod` is another ORIGIN-ONLY branch, never a registry row. Like -retry it forks from
the PRE-adoption ES-4-NullLock, with the command executors WIDENED and nothing else:
axon.saga.command-pool-size 64 -> 128 and axon.saga.retry-pool-size 4 -> 32. Retry POLICY is
byte-identical (maxRetries=5, initialDelayMs=25, 500ms cap), so a difference cannot be blamed on
the backoff curve. The retry pool matters far more than its 4 threads suggest: Axon's
RetryingCallback re-dispatches INLINE and SimpleCommandBus handles on the calling thread, so a
retried command's aggregate load, reserveDelayMs sleep and append all run on that pool — which on
a lock-free branch is where most contended work ends up.

Read it against archived `ES-4-NullLock_*` runs — not against today's ES-4, which moved to
112 + 0 — and at DISTINCT_ITEMS >= 32, the OPPOSITE of the point the lock-free variants are read
at. NullLockFactory removes the JVM lock, not the event store's
UNIQUE (aggregate_identifier, sequence_number), so one append per aggregate at a time still holds
and the useful width is bounded by the item count; at DISTINCT_ITEMS=1 the extra threads buy
conflicts and this variant should be WORSE than its baseline.

Two operational requirements the suite cannot enforce, both from the widened pools:
```
  - export AXON_JDBC_POOL_SIZE=500. A busy thread holds TWO Axon connections at once (its
    command's transaction, plus the one DataSourceConnectionProvider opens per append), so peak
    demand is 2 x (128 + 32 + 60 + 3) = 446. docker-compose passes AXON_JDBC_POOL_SIZE with a
    default of 350 and that OVERRIDES the branch's application.yaml, so the branch cannot fix this
    for itself. The API logs the arithmetic as `[POOLS]` at startup and warns when it is short.
  - REPLICAS=1 only, unless PG_MAX_CONNECTIONS also rises: 2 x (50 + 500) > 600.
```

---

## ES-4-NullLock-retry

`ES-4-NullLock-retry` is an ORIGIN-ONLY branch and has never been a registry row. It forks from
the PRE-adoption ES-4-NullLock — the two-lane shape, before ES-4 took it — with the RETRY POOL
widened 4 -> 64 and nothing else; the command pool stays 64 and retry policy (5 attempts,
25/50/100/200 ms) is untouched. Four threads
sound like a timer and are not one: Axon's `RetryingCallback` re-dispatches INLINE and
`SimpleCommandBus` handles on the calling thread, so a retried command executes IN FULL on the
retry pool. At 4 that is a 16x narrower waist than the first-attempt path, and on a lock-free
branch — where conflicts are resolved by the event store's unique constraint plus this retry —
most contended work goes through it. It cannot be paired with today's ES-4 at all: ES-4 no longer
HAS an executing retry lane, so the comparison is against archived `ES-4-NullLock_*` runs, at the
DISTINCT_ITEMS=1 hot-key point where the lock-free variants are read.

Needs `AXON_JDBC_POOL_SIZE=400`: a busy thread can hold two Axon connections at once, so peak
demand is 2 x (64 + 64 + 60 + 3) = 382, where ES-4 demands exactly the 350 docker-compose passes.
That is why the registered branch fits it and this one does not; the API logs the arithmetic as
[POOLS] at startup and warns when short. REPLICAS=1 only, unless PG_MAX_CONNECTIONS also rises.
NOTE the command pool here is still 64 against ES-4's 112, so this was never a retry-width-only
A/B — see the ES-4 entry in `variants.env`.

It shares `saga_pool_{active,queued,size}` with ES-4-NullLock-mod and with ES-4 itself.
Read `active`, not `queued`: a ScheduledThreadPoolExecutor's queue also holds attempts still
serving out their backoff.

Distinguish it from `ES-4-NullLock-mod`, which widens BOTH pools (128 command / 32 retry) and so
cannot say which of the two mattered. This branch is the controlled half of that question.
