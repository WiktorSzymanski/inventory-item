# inventory-item — variant benchmark suite

A Master's thesis benchmark comparing **Traditional Ownership** (`TO-1`..`TO-4`) against
**Event Sourcing** (`ES-1`..`ES-4`) for the same inventory reservation domain. Each variant
lives on its own branch and implements the same HTTP API.

**`main` holds the only harness and no application code at all.** There is no `src/`, no
Gradle build and no `Dockerfile` here — those live on the eight variant branches. What `main`
provides is the entry point for building and running the eight variants as a set, plus the
one shared `k6/` harness they are all measured with.

## Quick start

```bash
scripts/build-images.sh                                   # one image per branch
SCENARIO=steady RATE=60 DURATION=10m scripts/run-suite.sh  # benchmark all eight
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
| `k6/` | **The harness.** One copy, shared by all eight variants. |
| `docker-compose.yml` | The unified stack: services `api` and `postgres`, no family names. |
| `scripts/build-images.sh` | Builds `inventory-reservation-<variant>:latest` from each branch's worktree. **The only script that touches branches.** |
| `scripts/run-suite.sh` | Runs `main`'s harness against each variant's image, in turn. |
| `scripts/run-tests.sh` | Runs the harness test suite. |
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

**Runs are sequential and each starts from a full teardown.** All eight variants publish the
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

**Both aggregate-cost levers are honoured on all eight branches** as of 2026-08-06.

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
