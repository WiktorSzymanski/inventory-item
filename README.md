# inventory-item — variant benchmark suite

A Master's thesis benchmark comparing **Traditional Ownership** (`TO-1`..`TO-4`) against
**Event Sourcing** (`ES-1`, `ES-2`, `ES-4`) for the same inventory reservation domain. Each
variant lives on its own branch and implements the same HTTP API.

**`main` holds the only harness and no application code at all.** There is no `src/`, no
Gradle build and no `Dockerfile` here — those live on the seven variant branches. What `main`
provides is the entry point for building and running the seven variants as a set, plus the
one shared `k6/` harness they are all measured with.

`variants.env` is the registry and the only place the set is defined; every script reads it and
nothing else. Branches that used to be variants and are not any more — `ES-3`, `TO-2-opt` and
four others — are documented in [`docs/retired-variants.md`](docs/retired-variants.md), with what
re-adding each one would take.

## Quick start

```bash
scripts/build-images.sh                                   # one image per branch
SCENARIO=steady RATE=60 DURATION=10m scripts/run-suite.sh  # benchmark all seven
python3 k6/bench/compare.py bench-results/*_steady_*       # the thesis table
```

Narrow it down while iterating:

```bash
scripts/build-images.sh --only ES-4,TO-1
SCENARIO=steady RATE=30 DURATION=3m WARMUP_ITERATIONS=2000 \
  scripts/run-suite.sh --only ES-4,TO-1
```

**Do not shrink a smoke run below a few thousand orders.** `completion_ratio_inverse` is a
validity check with a 0.001 limit, so it needs enough orders that a couple of stragglers at
the window boundary stay under a tenth of a percent. At `RATE=5 DURATION=60s` — about 300
orders — a single late completion is already 0.33%, and the run reports `INVALID` for
reasons that have nothing to do with the variant. Every run at `RATE>=30` with a load phase
of two minutes or more has measured 0.0 to 0.0002 on that check. Shorten the *variant list*
when iterating, not the workload.

## The pieces

| Path | What it does |
|---|---|
| `variants.env` | The registry. `<variant> <branch> <family> <capabilities>`. |
| `points.env` | Named workload points (`W-base`, `C11`, …) binding a label to its knobs. |
| `workload.env` | Optional sticky knobs for a campaign. Ships fully commented out. |
| `k6/` | **The harness.** One copy, shared by every variant. |
| `docker-compose.yml` | The unified stack: services `api` and `postgres`, no family names. |
| `scripts/build-images.sh` | Builds `inventory-reservation-<variant>:latest` from each branch's worktree. **The only script that touches branches.** |
| `scripts/run-suite.sh` | Runs `main`'s harness against each variant's image, in turn. |
| `scripts/run-campaign.sh` | Runs several (scenario, point) steps in turn, each across every variant. Resumable. |
| `scripts/run-tests.sh` | Runs the harness test suite. |
| `scripts/replay_url.sh` | Prints the Grafana URL for an archived run, with its time window preset. |
| `scripts/lib.sh` | Registry, worktree, teardown and point-resolution helpers. |
| `docs/bench-campaign-runbook.md` | The thesis campaign in execution order. |

## How it works, and why

> **One harness, on `main`.** Every variant is measured by the same `k6/`, the same
> `docker-compose.yml` and the same `queries.promql`. This used to be eight copies kept in
> step by hand; the families were thought to differ irreconcilably, but `queries.promql` is
> identical across TO and ES, `reset.sh` discovers its tables from `pg_tables`, and both
> families read the datasource from `${DB_JDBC_URL}`. Everything genuinely family-specific
> was a *name*, and `main` now chooses those names: services are `api` and `postgres`.
>
> The variant branches keep their own `k6/` and `bench.env`. They are **no longer used** —
> running `./k6/bench/bench.sh` on a branch produces a different stack than `main` does.
> `main` is the only supported entry point.

**Building still needs a branch; running does not.** `build-images.sh` is the only script
that touches branches — it creates `.worktrees/<variant>/` (gitignored, on demand) to build
`inventory-reservation-<variant>:latest` from that branch's own `Dockerfile` and `src/`.
`run-suite.sh` never checks a branch out: it runs `main`'s own `k6/bench/bench.sh` against
whichever image `build-images.sh` produced, from `main`'s own working tree. Nothing fetches
or pulls either way — the branches are local, and silently moving one would change what is
being measured without saying so.

**Artifacts land in one place.** `run-suite.sh` runs `main`'s harness from `main`'s own
working tree, so every variant's `bench.sh` invocation writes straight into `main`'s own
`bench-results/`. `ensure_results_link` only has to do anything when `main` itself is
checked out as a worktree, in which case it symlinks that worktree's `bench-results/` to the
primary tree's.

**Images are per variant, not per family.** They used to be per family, so `ES-2` and `ES-4`
overwrote each other and `TO-1`..`TO-4` shared a single image. `scripts/lib.sh`'s
`image_tag()` derives `inventory-reservation-<variant>:latest` from `variants.env`, and
`docker-compose.yml` requires `IMAGE_TAG` with no default, so the harness and a bare
`docker compose up` always agree on which image runs.

`build-images.sh` stamps the commit SHA as a **`RUN` layer** in a second one-instruction
build. That is not cosmetic: `evaluate.py` treats `image_fresh` as a **validity** check, and
a commit touching only docs reuses every cached layer, leaving the image's `Created` older
than `HEAD` and reporting a good run `INVALID`. The `RUN`'s cache key contains the SHA, so a
new commit always produces a new layer — and a new layer is what dates the image now.

**It has to be `RUN`, not `LABEL`.** A metadata-only instruction yields a new image ID but
BuildKit inherits `Created` from the base, so a `LABEL` stamp does not refresh the timestamp
at all — measured: label applied, `Created` unchanged. The `LABEL` in `build-images.sh` rides
along for machine-readable provenance only; it is not what makes the check work.

`image_fresh` compares the image against **the variant branch's** `HEAD`, which `run-suite.sh`
resolves from `.worktrees/<variant>/` and passes to `bench.sh`. Comparing against `main`'s
`HEAD` — as `bench.sh` did on its own, since its `REPO_ROOT` is now `main` — is two unrelated
clocks, and one docs-only commit here reported all eight variants `INVALID`.

**Runs are sequential and each starts from a full teardown.** Every variant publishes the
same host ports, so they cannot overlap. `run-suite.sh` pins one Compose project name (`iir`)
for every variant and runs `down -v --remove-orphans` before each. The `-v` matters more than
it looks: `reset.sh` only truncates tables, so without it a `TO`↔`ES` switch would inherit
the previous run's Postgres volume under a schema that does not match, and the bind-mounted
Prometheus config is never re-read by a running container either. `--remove-orphans` catches
anything left by the pre-unification layout, where TO and ES used different service names.

**`SKIP_BUILD=1` is passed to `bench.sh`.** Otherwise it would rebuild and retag the image
itself, discarding the provenance stamp and running something the suite never recorded.

## Knobs, and one that is not universal

Every knob `bench.sh` understands is passed straight through — `RATE`, `DURATION`,
`DISTINCT_ITEMS`, `ITEMS_PER_ORDER`, `PAYLOAD_BYTES`, `WARMUP_ITERATIONS`, `STEP_*` and the
rest. Set them per invocation, or pin them for a campaign in `workload.env`.

**Both aggregate-cost levers are honoured on every registered branch** as of 2026-08-06.

| Knob | What it costs | Implemented as |
|---|---|---|
| `PAYLOAD_BYTES` | padding on the row/aggregate every reserve rewrites | TO: `additional_bytes` column. ES: aggregate state replayed from the creation event. |
| `RESERVE_DELAY_MS` | a sleep on the reserve path, under the row/aggregate lock | TO: `reserve_delay_ms` column, slept in `reserve()`. ES: aggregate state, slept in the `@CommandHandler`. |

Both are paid **only once a reserve is known to succeed**. Charging them on the out-of-stock
path would make the rejection rate a hidden throughput lever, which would dominate a
`DISTINCT_ITEMS=1` contention sweep. On ES the sleep is in the `@CommandHandler` rather than
the `@EventSourcingHandler`, so a replay or snapshot load does not pay it — otherwise a
per-reserve cost would become a startup cost, which is exactly what the cached and
snapshotting variants exist to measure.

Lower `RATE` / `STEP_START` when you raise the delay: the lock is held for its duration, so
the ceiling is roughly `workers / (ITEMS_PER_ORDER × delay)` on TO and `DISTINCT_ITEMS / delay`
on ES.

```bash
RESERVE_DELAY_MS=25 RATE=10 scripts/run-suite.sh
```

Should a future branch ever lack one of these, the failure would be **silent**: `k6/` is
byte-identical everywhere so k6 sends the field regardless, Spring ignores unknown JSON
properties, and `meta.json` records the *requested* value rather than what the server applied.
`run-suite.sh` guards against exactly that — it warns and names any selected variant missing
the capability, reading `variants.env`'s fourth column, with the "honoured on" list derived
from the registry rather than hardcoded.

Lower `RATE` / `STEP_START` when you raise the delay: the sleep is held while the DB row lock
(TO) or the pessimistic aggregate lock (ES) is held, so the throughput ceiling is roughly
`workers / (ITEMS_PER_ORDER × delay)` on TO and `DISTINCT_ITEMS / delay` on ES. Leave the
rate high and the staircase saturates at step 0 and reads `INVALID`.

## Verdicts

`run-suite.sh` reports each variant's `bench.sh` exit code:

- **PASS** — validity and SLO checks all passed.
- **FAIL** — the system missed an SLO. A real result.
- **INVALID** — the *measurement* was broken (backlog never drained, scrape gap, API
  restarted mid-run, orders that never reached a terminal event). Not the same as slow.

The suite exits non-zero unless every variant passed. `--continue-on-fail` runs everything
regardless and reports at the end.

## Reading a run's results

Every run writes `bench-results/<variant>_<scenario>[_<label>]_<timestamp>/`. Five ways in,
cheapest first.

### 1. The comparison table

```bash
python3 k6/bench/compare.py bench-results/ES-4_steady_*          # one or many runs
python3 k6/bench/compare.py --knee bench-results/*_capacity_*    # staircases and knees
python3 k6/bench/compare.py --cols saga bench-results/ES-*       # ES saga internals
python3 k6/bench/compare.py --cols resource --baseline <run> <run>
```

Pass any number of run directories; each becomes a row. The `point` column shows the named
workload point when one was used, and sits beside `items`/`lines`/`payloadB`/`reserveMs` so
a table accidentally mixing workload points is visible rather than silent.

### 2. The tipping point of a staircase

```bash
python3 k6/bench/tipping_point.py bench-results                   # every capacity run
python3 k6/bench/tipping_point.py --steps bench-results/ES-1_capacity_W-hot_*
python3 k6/bench/tipping_point.py --basis terminal -f csv bench-results
```

Where order processing stopped keeping up with the offered rate, read off the per-step
plateaus. Family-aware by design: ES is judged on `OrderCreatedEvent` against saga
outcomes, TO on admitted orders against order outcomes, so both sides of the comparison
are the same thing on both architectures.

Four numbers per run — `sustained` (highest fully-serviced rate), `tipping` (where it fell
behind and stayed behind), `peak good` and `plateau` (where throughput stopped increasing)
— plus a shape: `tracking`, `plateau`, `collapse`, or `load-shed`. The default counts only
successful outcomes, because TO sheds contention as rejections: on `--basis terminal` it
keeps "terminating" orders at the offered rate right past the point where it stopped
completing any.

This complements `compare.py --knee`, which asks a different question — that knee is an
admission-plus-latency-SLO knee, this one is throughput only and needs no SLO guess.

### 3. `report.pdf`

`bench-results/<run_id>/report.pdf` is a full render of the 53-panel dashboard for that
run's window, produced at the end of the run. Nothing to start — just open it. On an ES run
the TO-family panels render empty by design, and vice versa.

### 4. Grafana, against the archived run

This is the full-fidelity path: the complete Prometheus TSDB, not the ~20 series
`dump.json` extracts.

**The replay stack is self-contained — it brings its own Grafana.** The benchmark stack's
Grafana lives in `docker-compose.yml`, which `run-suite.sh` tears down with `down -v` after
every run, so after a benchmark there is nothing on `:3000` to look at.

```bash
COMPOSE_PROJECT_NAME=iir docker compose -f docker-compose.replay.yml up -d
```

That starts both halves: `prometheus-replay` (`:9091`) and `grafana-replay` (**`:3001`**).
Note the different port — the archive viewer is deliberately kept off `:3000` so it can
never collide with a benchmark that is running, and both may be up at once. Use it
**standalone**: do not add `-f docker-compose.yml`, which demands `IMAGE_TAG` and
contributes nothing here. `COMPOSE_PROJECT_NAME` must match the benchmark stack's so the
two share a network.

Then get the URL for a run — this is the reliable way in:

```bash
scripts/replay_url.sh                    # newest run
scripts/replay_url.sh bench-results/ES-4_steady_20260807T101745Z
scripts/replay_url.sh --open             # and open it in a browser
```

**Opening the dashboard without an explicit time range shows nothing, and it is not
obvious why.** `Inventory — Full Stack` defaults to `from=now-15m&to=now` with a 5-second
refresh, because its first job is watching a run happen. An archived run is a fixed window
in the past, so those defaults point at a stretch of time the archive has no samples for —
and the refresh slides the window further away every 5 seconds. It looks like an empty
archive; the data is fine. `replay_url.sh` reads the window out of the run's own
`meta.json` and pins the refresh off, so the question does not arise.

The manual equivalent, if you would rather: open `http://localhost:3001`, set the **Data
source** dropdown to **Prometheus Replay**, turn auto-refresh **off**, and set an absolute
time range from `meta.json`'s `windows.full` (epoch seconds — load through end of drain;
the drain tail matters, because `order_e2e_time` is recorded when the projection handles
the terminal event, which under load lags the load phase).

The archive volume is `external`, so `docker compose down -v` cannot touch it. With the
benchmark stack down the plain **Prometheus** datasource has nothing to resolve — only
**Prometheus Replay** returns data, which is expected. Query the archive directly at
`http://localhost:9091` if you would rather not use Grafana at all.

Stop it when you are done; it holds `:9091` and `:3001`, neither of which the benchmark
needs:

```bash
COMPOSE_PROJECT_NAME=iir docker compose -f docker-compose.replay.yml down
```

### 5. The raw artifacts

| File | What it holds |
|---|---|
| `meta.json` | resolved config, timeline, measurement windows, provenance (variant branch + commit, image id, `image_built_after_head`) |
| `verdict.json` | every validity and SLO check with its actual value and limit |
| `dump.json` | the extracted Prometheus series the tables are built from |
| `summary.json` | k6's own client-side summary — **admission latency only**, see below |
| `k6.log`, `seed/`, `warmup/` | per-phase k6 output |
| `prom-snapshot/` | the full TSDB block for this run |

**`summary.json` is not end-to-end latency.** `POST /inventory/orders` returns 202 after
persisting only `OrderCreatedEvent`, so k6 measures admission — frequently three orders of
magnitude below the truth. Real latency is `order_e2e_time`, which reaches you through
`dump.json` and the `e2e p50/p95/p99` columns.

## Preserving raw metrics

`down -v` destroys the `prometheus-data` volume, so unaided only `dump.json`'s ~20 extracted
series and `report.pdf` would survive a run. TSDB preservation guards against exactly that,
and it is **on by default** (`SNAPSHOT_TSDB=1`, `ARCHIVE_TSDB=1`) — every run copies its full
TSDB into `bench-results/<run_id>/prom-snapshot/` before teardown, and also merges it into
the external `bench-replay-data` volume for Grafana replay. Pass `--no-snapshot-tsdb` to skip
both, or `--no-archive-tsdb` to keep the host-side snapshot but skip the replay-volume merge.
`SNAPSHOT_TSDB=0` / `ARCHIVE_TSDB=0` work the same way as environment variables.

## Per-branch details

Each variant branch has its own `CLAUDE.md` describing its architecture, and `k6/README.md`
describing the harness, scenarios and knobs. `run-suite.sh` passes every `bench.sh` knob
through unchanged — it decides which variants run and in what order, nothing more.
