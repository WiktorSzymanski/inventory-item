# Load-test campaign design — TO vs ES, 8 variants

Date: 2026-08-06
Status: approved (design), not yet planned or executed

This document defines the complete load-test campaign for the thesis comparison. It
supersedes `k6/load-tests-plan.md`, which was written against a 12-variant branch layout
that no longer exists (the `ES-3-*` A/B branches are gone; the families are now `TO-1..4`
and `ES-1..4`).

---

## 1. Subjects

Eight branches, two families of four:

| Family | Variants |
|---|---|
| TO | `TO-1`, `TO-2`, `TO-3`, `TO-4` |
| ES | `ES-1`, `ES-2`, `ES-3`, `ES-4` |

The campaign runs in two phases. Phase 1 measures all eight and elects one winner per
family. Phase 2 takes those two winners and sweeps the aggregate-cost levers.

---

## 2. Phase 0 — prerequisite changes

All four changes must be committed and propagated to every branch that needs them **before
the first phase-1 run**. Phase 1 and phase 2 must be measured on the same binaries;
introducing a code change between the phases would mean the elected winner is no longer
the variant that was benchmarked.

### 2a. Port `reserveDelayMs` to the six branches missing it

Present today only on `TO-3` and `ES-4`:

```kotlin
// TO-3 / ES-4
data class CreateItemRequest(val id: String, val availableQty: Int,
                             val additionalBytesSize: Int = 0, val reserveDelayMs: Int = 0)
// TO-1, TO-2, TO-4, ES-1, ES-2, ES-3
data class CreateItemRequest(val id: String, val availableQty: Int,
                             val additionalBytesSize: Int = 0)
```

Spring Boot ignores unknown JSON properties by default, so without this port a phase-2 run
with `RESERVE_DELAY_MS=25` on any of the six would complete normally, apply no delay, and
leave nothing in the artifacts to reveal it. That is a silent-no-op trap, not a missing
feature.

Port the shape already on `TO-3`/`ES-4`:

- field on `CreateItemRequest`, carried on `InventoryCreatedEvent`, stored on the aggregate
  / item row at creation;
- `Thread.sleep(reserveDelayMs)` on the reserve path, placed **after** the stock check so
  it is only paid on a successful reserve, and **inside** the held lock — the DB row lock
  on TO, the aggregate lock on ES. Holding the lock through the sleep is the point: it
  models slow domain logic, not slow IO.
- On ES it goes in the `@CommandHandler`, never the `@EventSourcingHandler`. In the latter
  it would be paid on every replay and snapshot load, turning a per-reserve cost into a
  startup cost.

Acceptance: at `RESERVE_DELAY_MS=0` the change is a provable no-op (no sleep call reached,
no measurable delta against the pre-change binary).

### 2b. Add `additional_bytes` to TO's `inventory_state`

`additionalBytesSize` is currently asymmetric between the families:

- **ES** — `additionalBytes` is an aggregate field, rehydrated from `InventoryCreatedEvent`
  on every aggregate load, written into every snapshot row, and deep-copied per command by
  `PessimisticCachingRepository` on `ES-4`. A continuous runtime cost.
- **TO** — `inventory_state` is `(item_id, available_qty, version, updated_at)`; on `TO-3`
  also `reserve_delay_ms`. There is no bytes column. The padding rides once on
  `InventoryCreatedEvent` into the outbox at seed time and never touches the reserve path.

Run unchanged, `PAYLOAD_BYTES` would be an ES-only lever, and TO's payload cells would come
out flat — not because TO absorbs the payload but because TO never carries it.

Change: a Flyway migration adding `additional_bytes` to `inventory_state` on all four TO
branches, plus the field on the `InventoryItem` data class, populated at creation so every
reserve's read-modify-write carries it. At `PAYLOAD_BYTES=0` it is an empty string, so
phase-1 numbers are unaffected.

Expected effect, and the reason the lever is worth having: a 1 MiB column is TOASTed and
rewritten on every reserve, so TO pays it in WAL volume and table bloat where ES pays it in
snapshot size and copy-on-write. Same question, asked of both families.

### 2c. Add a `stress` scenario to the harness

`profiles.js` builds `capacity`, `steady`, `soak`, `spike`, `legacy`. There is no `stress`.
Phase 2 needs sustained load *above* the knee.

- `profiles.js`: add `stress` as a constant-rate profile, delegating to `constantRate` the
  way `steady` and `soak` already do.
- `thresholds.json`: add a `stress` section that nulls the latency SLOs **and stops
  treating a drain timeout as INVALID**. Under deliberate overload an undrained backlog is
  the measurement, not a broken measurement. Without this, every stress run reports INVALID
  and is unusable.
- `evaluate.py`: judge `stress` on whether the backlog is bounded or growing, whether all
  orders reach a terminal state, and `drain_service_rate`.

### 2d. Add `RUN_LABEL` to `bench.sh`

Run directories are `<variant>_<scenario>_<timestamp>`, which cannot distinguish phase-2
cell C01 from C11. A two-line change appending an optional label makes ~73 run directories
navigable. `meta.json` already records the full resolved config, so the label is for
navigation only and is never the source of truth.

### 2e. Re-synchronise the harness across all eight branches

Added 2026-08-06 after auditing the actual branch state. The invariant below is **currently
violated on all seven non-`TO-3` branches**, which means no measurement taken today is
cross-comparable and 2c/2d cannot simply be "propagated":

| Branch | `k6/` vs `TO-3` | `bench.env` |
|---|---|---|
| `TO-1`, `TO-2`, `TO-4` | 8 files differ — missing the `reserveDelayMs` harness plumbing in `config.js`, `api.js`, `main.js` | present |
| `ES-2` | 10 files differ — older `evaluate.py`, `compare.py`, `queries.promql` | present |
| `ES-4` | 5 files differ, insertions only — a strict superset of `TO-3` in three files, plus two stale planning docs | present |
| `ES-1`, `ES-3` | **harness absent** — legacy `k6/reserve-load-test.js`, no `k6/bench/`, no `k6/lib/`, no `docker-compose.bench.yml` | **missing** |

`common.sh` exits `FATAL` without `bench.env`, so `ES-1` and `ES-3` — two of the eight
campaign subjects — cannot be benchmarked at all today. Both do carry the application
instrumentation the harness queries (`order_e2e_time`, `order_projection_lag_seconds`), so
the gap there is files, not metrics.

Since neither `TO-3` nor `ES-4` is a superset of the other, the canonical harness is `TO-3`
plus `ES-4`'s three-file additions; every branch is then converged onto that by wholesale
replacement, and `ES-1`/`ES-3` additionally get a `bench.env` and the DNS-discovery
`prometheus.yml` the other ES branches already use.

### Cross-branch invariant

Everything under `k6/` and `docker-compose.bench.yml` stays byte-identical on all eight
branches; `bench.env` is the only per-branch file. After 2c, 2d and 2e:

```bash
git diff --stat TO-3 <branch> -- k6 docker-compose.bench.yml    # must be empty
```

---

## 3. Workload points

Three points. `ITEMS_PER_ORDER <= DISTINCT_ITEMS` is enforced by `k6/lib/config.js`, so the
two axes cannot be a free cross product; these three are all legal.

| Point | `DISTINCT_ITEMS` | `ITEMS_PER_ORDER` | Probes |
|---|---|---|---|
| **W-base** | 100 | 4 | reference — low contention, moderate fan-out |
| **W-hot** | 8 | 4 | contention: 12.5× fewer aggregates, order shape unchanged |
| **W-fan** | 100 | 16 | fan-out: 4× the lines, contention unchanged |

This is a one-factor-at-a-time star around W-base: each of W-hot and W-fan moves exactly
one axis. There is deliberately no high-contention *and* high-fan-out cell, so the campaign
cannot say whether the two effects compound. That was an accepted trade to keep phase 1
inside its time budget.

`QTY_PER_LINE=1` and `SEED_QTY` at its default throughout, so stock is never the binding
constraint and no run drifts into benchmarking the compensation path.

---

## 4. Phase 1 — all eight variants

`PAYLOAD_BYTES=0` and `RESERVE_DELAY_MS=0` for every phase-1 run.

### 4.1 Runs

| Test | Scenario | Points | Variants | Runs |
|---|---|---|---|---|
| Breakpoint | `capacity` | W-base, W-hot, W-fan | all 8 | 24 |
| Soak | `soak`, 45 min | W-base only | all 8 | 8 |

Soak is run at W-base only. Its job is drift detection — heap creep, projection lag,
DB growth per order — and drift is a property of the variant far more than of the workload
point, so soaking the other two points would largely re-confirm the first.

### 4.2 Staircases

One staircase per workload point, **identical across all eight variants** so step
boundaries line up in `compare.py --knee`. Starting values extrapolated from existing runs
in `bench-results/` (`TO-3` at DI=100/IPO=1 peaked at 1000; DI=6/IPO=4 used 20/20/15):

| Point | `STEP_START` | `STEP_INC` | `STEP_COUNT` | peak rate | load time |
|---|---|---|---|---|---|
| W-base | 40 | 40 | 10 | 400 | 22.5 min |
| W-hot | 20 | 20 | 12 | 240 | 27 min |
| W-fan | 10 | 10 | 12 | 120 | 27 min |

Peak is `STEP_START + (STEP_COUNT - 1) × STEP_INC`, per `k6/lib/profiles.js` — the first
step runs at `STEP_START`, not at `STEP_START + STEP_INC`.

`STEP_RAMP_S`, `STEP_PLATEAU_S` and `STEP_TRIM` stay at their defaults (15 s, 120 s, 0.4).

**Bracketing rule.** A staircase that does not bracket the knee yields nothing:

- knee at the last step, or `require_knee` unsatisfied → re-run that point with the peak
  doubled (double `STEP_INC`);
- knee at step 0 → re-run with `STEP_START` halved.

If a staircase is re-calibrated, **every variant already run at that point must be re-run on
the new staircase.** Knees read off different staircases are not comparable. Running phase 1
grouped by workload point (§7) exists precisely so this is discovered on the first variant.

### 4.3 Soak rate

```
RATE = 0.6 × min(knee at W-base over all 8 variants)
```

Rounded to a whole number, fixed once, and recorded in this document as an appendix when
measured. One rate for all eight variants: comparing variants at different rates measures
nothing, and this soak is the headline head-to-head table.

Accepted cost: a variant whose knee is well above the minimum soaks far below its
capability, so its "no drift" result is a claim about *that rate*, not about its capacity.
State it that way in the write-up.

---

## 5. Selection

Per family, in order:

1. **Disqualify** any variant whose W-base soak verdict is not `PASS`. A variant that
   cannot hold the common rate for 45 minutes does not represent its family.
2. **Rank** the survivors by knee at each of W-base, W-hot and W-fan, separately.
3. **Winner** = lowest mean of the three ranks.
4. **Tie** broken by lower `order_e2e` p95 (confirmed outcomes) in the W-base soak.

Ranking across all three points rather than on W-base alone matters because W-base is the
friendliest point in the grid, and phase 2 turns up exactly the contention and fan-out
pressure that a W-base-only ranking would ignore.

If every variant in a family is disqualified at step 1, re-run the failing soaks once; if
they fail again, the lowest-ranked failure mode is itself the family's result and the
best-ranked variant proceeds to phase 2 with that caveat recorded.

---

## 6. Phase 2 — the two winners

Workload held at **W-base** except for the strip in §6.4.

### 6.1 Cells

|  | `RESERVE_DELAY_MS=0` | `RESERVE_DELAY_MS=25` |
|---|---|---|
| `PAYLOAD_BYTES=0` | **C00** reference | **C01** delay only |
| `PAYLOAD_BYTES=1048576` | **C10** payload only | **C11** both |

A 2×2 corner design. The mid-levels originally considered — 0.5 MiB and 10 ms — are **not
run**. Consequence to state in the write-up: a jump between corners shows *that* the system
degrades, not whether the degradation is gradual or cliff-shaped. If the corners turn out to
be far apart and linearity matters, the cheapest recovery is to add the four mid-level
**breakpoint** runs only (2 cells × 2 winners, ~3 h), not to extend the full test matrix.

### 6.2 Runs

**C00's breakpoint is already measured.** For each winner, phase 1's W-base breakpoint *is*
C00 — same config, same binary — so it is reused rather than re-run.

Per winner:

| Test | Cells | Runs |
|---|---|---|
| Breakpoint | C01, C10, C11 (C00 reused) | 3 |
| Soak | C00, C01, C10, C11 | 4 |
| Spike | C00, C01, C10, C11 | 4 |
| Stress | C00, C01, C10, C11 | 4 |
| Breakpoint, workload strip | W-hot and W-fan at C11 | 2 |
| | **per winner** | **17** |
| | **× 2 winners** | **34** |

The C00 **soak** is re-run even though phase 1 soaked W-base, because phase-2 soak rates are
derived per cell (§6.3) and the phase-1 rate was derived from all eight variants including
the losers. The two rates differ.

### 6.3 Rates, derived per cell

Rates are **per cell, not campaign-wide**. At 1 MiB the knee collapses, so a fixed
campaign-wide rate would silently turn every payload cell into an overload test.

For each cell, from that cell's two breakpoints:

```
K = min(knee_TO-winner, knee_ES-winner)      # one value per cell
```

| Test | Settings |
|---|---|
| Soak | `RATE = 0.6 × K`; `SOAK_DURATION=45m` at `P=0`, `15m` at `P=1 MiB` |
| Spike | `SPIKE_BASE = 0.4 × K`, `SPIKE_FACTOR=4` → peak `1.6 × K` |
| Stress | `RATE = 1.25 × K`, `DURATION=10m`, `DRAIN_TIMEOUT=1800` |

The spike peak must exceed the knee or no backlog forms and `drain_service_rate` — the
payoff number for that test — is null.

One `K` per cell keeps the two winners comparable *within* the cell. Accepted cost: if one
winner collapses at C10/C11, the other soaks nearly idle there. That is the price of a
common rate, and it is the same trade already accepted in §4.3.

**Order within a cell:** both breakpoints first (TO winner, then ES winner) → compute `K` →
then soak, spike and stress for both winners.

### 6.4 Workload strip

`W-hot` and `W-fan` breakpoints at C11 only, on each winner. Four runs total. This is the
one place the campaign asks whether contention and fan-out compound with the aggregate-cost
levers; it is deliberately limited to the cheapest test type at the most-stressed cell.

Staircases for the strip must be re-bracketed per §4.2 — the C11 knees will be far below the
phase-1 knees at those points.

---

## 7. Execution order

Strict, because each stage feeds the next:

1. **Phase 0** — all four changes, propagated across the eight branches, cross-branch
   invariant verified.
2. **Phase 1 breakpoints, grouped by workload point** — all 8 variants at W-base, then all 8
   at W-hot, then all 8 at W-fan. Grouping by point rather than by variant means a
   mis-calibrated staircase surfaces on run 1 of 8 instead of run 24 of 24.
3. Compute the global soak `RATE` from the W-base knees.
4. **Phase 1 soaks** — all 8 at W-base.
5. **Selection** — one winner per family.
6. **Phase 2, cell by cell** — C01, C10, C11 breakpoints; then per-cell `K`; then soak,
   spike, stress. C00 breakpoint reused from phase 1.
7. **Phase 2 workload strip** at C11.

---

## 8. Operational guards

### 8.1 Disk, at every `P=1 MiB` run

TO's new `additional_bytes` column is TOASTed and rewritten on every reserve. At 60
orders/s × 4 lines that is roughly 240 MB/s of dead tuples, and `reset.sh`'s `TRUNCATE`
reclaims only *between* runs, never within one.

- Require **≥120 GB free** before starting any `P=1 MiB` run (currently 279 GB free).
- Abort mid-run if free space falls below **40 GB**.

Do **not** tune autovacuum on `inventory_state` to suppress the bloat. Bloat under a large
mutable row is part of what the payload lever measures. Record it as an interpretation
caveat instead.

Self-limiting factor worth noting: because soak/spike/stress rates are derived from that
cell's knee, and the knee at 1 MiB will be low, the payload cells run slowly by
construction.

### 8.2 Warmup at payload cells

`WARMUP_ITERATIONS=5000` is fixed-iteration by design, so every variant enters the measured
window with identical event-store depth, snapshot count and cache state. At 1 MiB it will
exceed `WARMUP_MAX_DURATION=5m` and silently deliver *fewer* iterations — destroying exactly
the property it exists for, with no validity check that would catch it.

For all `P=1 MiB` cells: `WARMUP_ITERATIONS=500`, `WARMUP_MAX_DURATION=20m`, applied
identically to both winners. Confirm the full iteration count completed in
`<run>/warmup/k6.log` before trusting the run.

### 8.3 Branch hygiene

`bench.sh` handles the build and the DB reset but not stale runtime state left by a branch
switch. Between branches:

- `docker compose down -v`;
- confirm nothing else holds `:8080` (a stray nginx from a previous branch fails as a health
  timeout, with no other symptom);
- confirm `image_built_after_head` in `meta.json` — and verify jar contents rather than
  trusting the image timestamp, which Docker's build cache can leave stale on a perfectly
  good image;
- confirm Flyway history matches the branch's migrations.

### 8.4 INVALID runs

Never reported as a result. Re-run once. If the same reason recurs, the reason becomes the
finding — e.g. a backlog that never drains at C11 means that variant cannot sustain that
cell, which is a result, not a failure.

Note the §2c exception: for `stress`, an undrained backlog is expected and must not be
classified INVALID.

### 8.5 Single-shot caveat

No run is repeated. `SEED` fixes the item-selection sequence so workload variance is
controlled, but system variance (JIT, page cache, autovacuum timing) is not, and there is
therefore **no spread estimate and no error bars**.

Consequences, to be honoured in the write-up:

- differences under roughly 10% between variants are reported as "not separated by this
  campaign", not as a ranking;
- if a headline number is surprising, spot-repeat that single run before writing it up.

---

## 9. Budget

| Stage | Runs | Machine time |
|---|---|---|
| Phase 0 — code changes across 8 branches | — | development, not machine time |
| Phase 1 breakpoints | 24 | ~15 h |
| Phase 1 soaks | 8 | ~8 h |
| Phase 2 | 34 | ~20 h |
| Re-run allowance (~10%) | ~7 | ~4 h |
| **Total** | **~73** | **~47 h** |

Per-run costs are taken from timelines in existing `bench-results/*/meta.json`, not
estimated: a 15-step staircase measured 34 min of load, and observed total wall clock per
run has ranged from 19 min to 95 min depending on drain.

At 8 h/day of machine time this is roughly six working days, against a September 2026
submission.

---

## 10. Artifacts and reporting

Each run leaves `bench-results/<variant>_<scenario>_<label>_<timestamp>/` containing
`meta.json` (full resolved config, timeline, windows, provenance), `dump.json`,
`verdict.json`, `report.pdf`, and a Prometheus TSDB snapshot for replay.

Deliverable tables come from `compare.py`:

```bash
python3 k6/bench/compare.py --knee bench-results/*_capacity_*      # staircase + knee
python3 k6/bench/compare.py bench-results/*_soak_*                 # head-to-head
python3 k6/bench/compare.py --cols es bench-results/ES-*           # ES internals
python3 k6/bench/compare.py --cols resource --baseline <run> <run> # resource deltas
```

End-to-end latency is **not** in the k6 output. `POST /inventory/orders` returns 202 after
persisting only `OrderCreatedEvent`, so k6 observes admission latency, typically three
orders of magnitude below reality. Real latency is `order_e2e_time` in `dump.json`.

---

## Appendix A — measured values (to be filled during execution)

| Value | Source | Measured |
|---|---|---|
| Global soak `RATE` (§4.3) | `0.6 × min` W-base knee over 8 variants | — |
| TO winner (§5) | mean rank over 3 points | — |
| ES winner (§5) | mean rank over 3 points | — |
| `K` at C00 (§6.3) | `min` of the two winners' W-base knees | — |
| `K` at C01 | | — |
| `K` at C10 | | — |
| `K` at C11 | | — |
| Final staircases, if re-bracketed (§4.2) | | — |
