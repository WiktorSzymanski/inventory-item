# inventory-item — variant benchmark suite

A Master's thesis benchmark comparing **Traditional Ownership** (`TO-1`..`TO-4`) against
**Event Sourcing** (`ES-1`..`ES-4`) for the same inventory reservation domain. Each variant
lives on its own branch and implements the same HTTP API.

**`main` holds no application code that anyone benchmarks.** The `src/` tree here is an
early legacy prototype, kept only for history. What `main` provides is the entry point for
building and running the eight variants as a set.

## Quick start

```bash
scripts/build-images.sh                                   # one image per branch
SCENARIO=steady RATE=60 DURATION=10m scripts/run-suite.sh  # benchmark all eight
python3 scripts/compare.py bench-results/*_steady_*        # the thesis table
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
| `variants.env` | The registry. One line per variant: `<variant> <branch> <family> <capabilities>`. Everything else reads it. |
| `workload.env` | Optional sticky knobs for a campaign. Ships fully commented out; the shell environment always wins. |
| `scripts/build-images.sh` | Builds `inventory-reservation-<variant>:latest` from each branch's worktree, plus `bench-results/images.json`. |
| `scripts/run-suite.sh` | Runs each variant's own `k6/bench/bench.sh` in turn, on a clean stack, collecting results centrally. |
| `scripts/compare.py` | Renders a set of run directories as one comparison table. Copy of the branches' own. |
| `scripts/lib.sh` | Shared worktree / teardown / registry helpers. |
| `docs/bench-campaign-runbook.md` | The 66-run thesis campaign, in execution order. Start here for a full campaign rather than a one-off run. |

## How it works, and why

**Each variant runs from its own git worktree, using that branch's own harness.** `main`
does not carry a unified `docker-compose.yml` or `queries.promql`. The families genuinely
differ — service names (`api-to` vs `api-es`), Prometheus job labels, and saga queries that
exist on the ES side only — and a third unified copy on `main` would drift from both.
Worktrees live in `.worktrees/<variant>/` (gitignored) and are created on demand. Nothing
fetches or checks out: the branches are local, and silently moving one would change what is
being measured without saying so.

**Artifacts are central.** Each worktree's `bench-results/` is a symlink to `main`'s, so
every run lands in one directory regardless of which variant produced it.

**Images are per variant, not per family.** They used to be per family, so `ES-2` and `ES-4`
overwrote each other and `TO-1`..`TO-4` shared a single image. Each branch's `bench.env` now
sets a unique `IMAGE_TAG`, and its `docker-compose.yml` substitutes the same variable, so the
harness and a bare `docker compose up` always agree on which image runs.

`build-images.sh` stamps the commit SHA as a label in a second one-instruction build. That is
not cosmetic: `evaluate.py` treats `image_fresh` as a **validity** check, and a commit
touching only docs reuses every cached layer, leaving the image's `Created` older than `HEAD`
and reporting a good run `INVALID`. The label's cache key is the SHA, so a new commit always
produces a fresh timestamp.

**Runs are sequential and each starts from a full teardown.** All eight variants publish the
same host ports, so they cannot overlap. `run-suite.sh` pins one Compose project name (`iir`)
for every variant and runs `down -v --remove-orphans` before each — which is also what makes
a `TO`↔`ES` switch safe, since the two families' service names differ and the Prometheus
config is a bind mount that a running container never re-reads.

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
- **SKIPPED** — that branch carries no harness.

The suite exits non-zero unless every variant passed or was skipped. `--continue-on-fail`
runs everything regardless and reports at the end.

## Preserving raw metrics

`down -v` destroys the `prometheus-data` volume, so only `dump.json`'s ~20 extracted series
and `report.pdf` survive a run by default. Pass `--snapshot-tsdb` to copy each run's full
TSDB into `bench-results/<run_id>/prom-snapshot/` before teardown.

## Per-branch details

Each variant branch has its own `CLAUDE.md` describing its architecture, and `k6/README.md`
describing the harness, scenarios and knobs. `run-suite.sh` passes every `bench.sh` knob
through unchanged — it decides which variants run and in what order, nothing more.
