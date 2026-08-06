# `main` as the single benchmark harness

**Date:** 2026-08-07
**Status:** approved, not yet implemented

## Goal

Make `main` self-sufficient at run time. Build the eight variant images once — the only
step that needs branch content — and from then on run every load test and every harness
test from `main` alone, against uniquely tagged images, with one harness that is identical
for all eight variants.

Today `main` orchestrates but does not execute: `run-suite.sh` creates a git worktree per
variant and invokes *that branch's* `k6/bench/bench.sh`, using that branch's
`docker-compose.yml`, `prometheus.yml` and `bench.env`. Eight copies of the harness exist,
kept in sync by a manual `git diff --stat` ritual. This replaces that with one copy.

## Current state, measured

`main`'s `scripts/lib.sh` justifies the per-branch model like this:

> the TO and ES families genuinely differ (service names api-to/api-es, job labels, saga
> queries that exist on one side only), and re-implementing a unified version on `main`
> would mean maintaining a third copy that drifts from both.

Measurement contradicts the substantive half of that claim.

| Component | Reality |
|---|---|
| `k6/` (17 files) + `docker-compose.bench.yml` | Byte-identical on all eight branches |
| `k6/bench/queries.promql` | Byte-identical across TO **and** ES — no ES-only saga queries |
| `k6/bench/reset.sh` | Discovers tables from `pg_tables`; written to be family-neutral |
| `application.yaml` datasource | `${DB_JDBC_URL}` / `${DB_USER}` / `${DB_PASSWORD}` — identical wiring on both families |
| `bench.env` | Differs only in variant identity and the `es`↔`to` token |
| `monitoring/prometheus/prometheus.yml` | Differs only in `job_name` and the `dns_sd_configs` name |
| `monitoring/nginx/nginx.conf` | Differs only in the `set $backend` value |
| `docker-compose.yml` | Mostly the `es`↔`to` swap, plus four real differences (below) |

The genuine `docker-compose.yml` differences, TO versus ES:

1. Service, container and volume names (`postgres-es`/`api-es`/`postgres-es-data` versus
   the `-to` forms) — mechanical.
2. `PG_MAX_CONNECTIONS` default: 600 on ES, 300 on TO.
3. ES sets `AXON_JDBC_POOL_SIZE`; ES-4 additionally sets `CACHE_TTL` and
   `CACHE_MAXIMUM_SIZE`. TO sets neither.
4. ES passes `--web.enable-admin-api` to Prometheus. TO does not.

Everything family-specific is a *name*, and every name is derivable from `variants.env`,
which already carries the variant and its family.

## Design

### `main` owns the harness and chooses the service names

Compose does not interpolate variables into service *keys*: `api-${FAMILY}` is not
expressible. Unification therefore requires uniform service names.

Because `main` owns the compose file, the names are uniform **by construction**: `api` and
`postgres`. The variant branches need no edits, because their compose files stop being
read.

That collapses every per-branch variable into a constant:

| Was, per branch | Becomes, on `main` |
|---|---|
| `API_SVC=api-es` / `api-to` | `API_SVC=api` |
| `DB_SVC=postgres-es` / `postgres-to` | `DB_SVC=postgres` |
| `PROM_JOB=inventory-es` / `inventory-to` | `PROM_JOB=inventory` |
| `API_CONTAINER_RE=.*api-es.*` / `.*api-to.*` | `API_CONTAINER_RE=.*-api-.*` |
| `IMAGE_TAG=inventory-reservation-<variant>:latest` | derived from `variants.env` |
| `VARIANT`, `VARIANT_FAMILY` | derived from `variants.env` |

`API_CONTAINER_RE` stays unanchored and gains hyphen bounds. The API service carries no
`container_name` (it is scaled with `deploy.replicas`), so cadvisor sees `iir-api-1`,
`iir-api-2`, … A bare `api` would be ambiguous; `.*-api-.*` matches those and no sibling
container (`iir-nginx`, `postgres-exporter`, `cadvisor` all fail it). `queries.promql`
applies it as `name=~"$CRE"`, and Prometheus anchors regexes fully, so the leading and
trailing `.*` are required.

**All eight `bench.env` files disappear.** `variants.env` becomes the only registry.

### What `main` gains

**Source of truth is `TO-3` ∪ `ES-4`, not `ES-4` alone.** The campaign plan
(`docs/superpowers/plans/2026-08-06-load-test-campaign-phase0.md`) names **`TO-3` the
canonical harness**, while CLAUDE.md names `ES-4` the reference. For `k6/` the distinction
is now moot — Task 4 of phase 0 converged every branch, and
`git diff --stat TO-3 <branch> -- k6 docker-compose.bench.yml` is empty on all seven others
(verified 2026-08-07). For `scripts/` it is not moot: `TO-3` is a strict superset.

- `k6/` — `main.js`, `lib/`, `run.sh`, `bench/` (`bench.sh`, `common.sh`, `reset.sh`,
  `dump.py`, `evaluate.py`, `compare.py`, `queries.promql`, `thresholds.json`,
  `wait-healthy.sh`)
- `docker-compose.yml` — unified, family-neutral service names, superset environment
- `docker-compose.bench.yml`
- `monitoring/` — `nginx/nginx.conf`, `prometheus/prometheus.yml`,
  `prometheus/prometheus-replay.yml`, `grafana/provisioning/`
- `scripts/` — `dashboards/`, `tests/`, `replay_run.py`, `prom_snapshot.sh`,
  `prom_archive.sh`, `verify_dashboard_metrics.py` (all on both), **plus `prom_restore.sh`
  and `grafana_snapshot.py`, which exist only on `TO-3`**
- `docs/superpowers/specs/2026-08-06-load-test-campaign-design.md` and
  `docs/superpowers/plans/2026-08-06-load-test-campaign-phase0.md` — the campaign's
  authority, currently `TO-3`-only. `main` executes the campaign, so `main` must carry them.

**`scripts/bench_run.sh` is deliberately not carried over.** It is `bench.sh` plus a TSDB
copy that survives `down -v`; `run-suite.sh` already inlines exactly that, and says so in
its own comments. A single-variant run is `scripts/run-suite.sh --only <variant>`.

**`prom_restore.sh` closes a live gap.** `docs/bench-replay.md` is byte-identical on `TO-3`
and `ES-4` and references `prom_restore.sh`, but the script exists only on `TO-3` — the doc
was propagated and the script was not. Sourcing `main` from `ES-4` alone would inherit a
document pointing at a missing script.

**`compare.py` is deduplicated.** `main:scripts/compare.py` is byte-identical to
`ES-4:k6/bench/compare.py` (303 lines each). Once `k6/` moves to `main`, keeping both would
recreate the drift this change exists to remove. `k6/bench/compare.py` is the one that
stays, since the campaign runbook and `run-suite.sh`'s closing hint both name a
`compare.py` path; `scripts/compare.py` becomes a thin shim or is dropped, and the README
and runbook are updated to one path.

The superset environment is safe: Spring Boot ignores an environment variable with no
matching property, so passing `AXON_JDBC_POOL_SIZE`, `CACHE_TTL` and `CACHE_MAXIMUM_SIZE`
to a TO image is a no-op, as is passing them to ES-1/ES-2/ES-3, which have no cache.

`--web.enable-admin-api` is set unconditionally.

### Scripts

**`scripts/build-images.sh` — unchanged.** It stays worktree-based. Building is the only
step that needs branch content, and paying it once is the point of the whole change.

**`scripts/run-suite.sh` — rewritten.** No worktrees, no `cd "$wt"`. For each registry row
it exports `VARIANT` and `IMAGE_TAG` and invokes `main`'s own `k6/bench/bench.sh`. Teardown,
port guards, the TSDB snapshot and the verdict table are unchanged in behaviour. The
"branch has no harness" skip disappears — there is one harness and it is always present.

**`scripts/run-tests.sh` — new.** Runs the harness test suite:

```
python3 -m unittest discover -s scripts/tests -t .
```

`-t .` matters: the tests do `from scripts.dashboards import build`, so the repository root
must be the top-level directory. The suite is stdlib `unittest` — no pytest, no
`conftest.py`, no `requirements.txt`, no Docker, no JDK. Measured on `ES-4`: 35 tests in
0.025 s, exit 0.

It runs **once**, not once per variant. With a single harness there is exactly one suite,
which is what "the same for all variants" means here. It does not call `lib.sh`'s
`require_tools`, which demands a reachable Docker daemon; these tests are hermetic and
requiring Docker would be a lie. It keeps `require_not_root`.

`scripts/tests/` and `scripts/dashboards/` come across from `ES-4` alongside the harness.

### Named workload points (`points.env`)

The harness has **no notion of `W-base`, `W-hot`, `W-fan` or the `C**` cells** — verified by
grep over `k6/` and `scripts/` on `ES-4`, which returns nothing. The names live only in the
campaign spec, the phase-0 plan and the runbook. A point is identified solely by the values
`meta.json` records under `config.distinctItems` / `config.itemsPerOrder` /
`config.payloadBytes` / `config.reserveDelayMs`, and `RUN_LABEL` is free text that appears
only in the run directory name — there is not even a `run_label` field in `meta.json`.

Nothing therefore binds a point's *name* to its *numbers*. A run labelled `W-base` that
actually used `DISTINCT_ITEMS=8` produces artifacts that agree with the wrong label.
Across 24 phase-1 breakpoint runs and 34 phase-2 cell runs, the only guard is the operator
retyping the runbook's numbers 58 times. (`compare.py` does surface `items`, `lines`,
`payloadB` and `reserveMs` as columns, so a mixed table is *visible* — but visibility after
the fact is not prevention.)

Because `main` will own both the harness and the registry, a point can be defined once and
applied identically to all eight variants by construction. `main` gains `points.env`:

```
# <point>  <knobs...>                                    <staircase>
W-base     DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4          STEP_START=40 STEP_INC=40 STEP_COUNT=10
W-hot      DISTINCT_ITEMS=8   ITEMS_PER_ORDER=4          STEP_START=20 STEP_INC=20 STEP_COUNT=12
W-fan      DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16         STEP_START=10 STEP_INC=10 STEP_COUNT=12
C00        PAYLOAD_BYTES=0       RESERVE_DELAY_MS=0
C01        PAYLOAD_BYTES=0       RESERVE_DELAY_MS=25     STEP_START=10 STEP_INC=15 STEP_COUNT=10
C10        PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=0      STEP_START=5  STEP_INC=5  STEP_COUNT=10
C11        PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25     STEP_START=2  STEP_INC=3  STEP_COUNT=10
```

Usage, with the W and C axes composable because phase 2 crosses them (§6.4 runs W-hot and
W-fan at C11):

```bash
POINT=W-hot        scripts/run-suite.sh    # phase 1
POINT=W-base,C11   scripts/run-suite.sh    # phase 2 cell
```

`POINT` sets the knobs **and** `RUN_LABEL` together, so the directory name and the recorded
config cannot disagree. Values come from the campaign design §4.2 and §6.1 and the
runbook's phase-2 staircases.

**Two classes of value, with different override rules.** This split is what keeps the
guarantee strong without breaking the bracketing rule:

- **Identity** — `DISTINCT_ITEMS`, `ITEMS_PER_ORDER`, `PAYLOAD_BYTES`, `RESERVE_DELAY_MS`.
  These *are* the point. If the shell sets one to a value conflicting with the named point,
  `run-suite.sh` **dies**. Silently honouring the override would make the label a lie,
  which is the whole defect being fixed.
- **Calibration** — `STEP_START`, `STEP_INC`, `STEP_COUNT`. The campaign's §4.2 bracketing
  rule *expects* these to be re-tuned and every variant at that point re-run. They are
  defaults only; the shell wins, no error.

**Precedence, and why order matters.** `run-suite.sh` snapshots the knobs already present
in the shell *before* resolving `POINT`, because once the point exports them a shell-set
value is indistinguishable from a point-set one. It then resolves `POINT` against that
snapshot: an identity conflict dies, a calibration override is honoured. `workload.env` is
sourced afterwards and uses the `VAR="${VAR:-value}"` form throughout, so it fills only
what is still unset and can never silently contradict a named point.

`bench.sh` additionally records `run_label` and `point` in `meta.json`, so a point is
machine-readable rather than inferable from the directory name, and `compare.py` gains a
`point` column. Runs made without `POINT` are unaffected — the field is empty and every
existing invocation keeps working.

### Deleted from `main` (32 tracked files)

| Removed | Why |
|---|---|
| `src/` (21) | The legacy prototype. Nothing reads it. |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/` (7) | Existed only to build `src/`. Variant builds run gradle inside the Docker builder stage. |
| `Dockerfile` | `build-images.sh` reads `$wt/Dockerfile`, never `main`'s. |
| `docs/index.html`, `docs/.swagger-codegen-ignore`, `docs/.swagger-codegen/VERSION` (3) | Generated Swagger for the deleted prototype. |

`main`'s existing `docker-compose.yml` and `monitoring/` are **replaced**, not deleted —
the prototype versions give way to the unified harness ones. `main` currently carries four
`monitoring/` files against `ES-4`'s seven; `dashboard.yml` and
`datasources/prometheus.yml` are overwritten, `prometheus/prometheus.yml` is replaced by
the unified one, `nginx/nginx.conf`, `prometheus/prometheus-replay.yml`,
`dashboards/bench-replay.json` and `dashboards/the-dashboard.json` arrive new, and
`dashboards/inventory-to-dashboard.json` is dropped as superseded by `the-dashboard.json`.

`.gitignore` is trimmed: the `build/`, `!gradle/wrapper/gradle-wrapper.jar`, STS and
NetBeans stanzas become dead. `.worktrees/`, `bench-results/`, `__pycache__/`, `.idea`,
`.vscode`, `.claude` and `CLAUDE.md` stay.

`README.md` is rewritten: drop "the `src/` tree here is an early legacy prototype, kept
only for history", document the unified harness, add `run-tests.sh`.

## Relationship to the load-test campaign plan

The campaign design (`2026-08-06-load-test-campaign-design.md`, on `TO-3`) is the latest
and governing plan. Its phase 0 is **complete**, verified 2026-08-07:

| Phase-0 item | State |
|---|---|
| 2a `reserveDelayMs` on all eight | done — `variants.env` records `reserve-delay` for all 8 |
| 2b `additional_bytes` on TO | done — `V6__additional_bytes.sql` on TO-1/2/4, `TO-3` already had it |
| 2c `stress` scenario | done — `profiles.js`, `thresholds.json` and `evaluate.py` on all 8; covered by the `StressWiring` tests |
| 2d `RUN_LABEL` | done — present in `bench.sh` on all 8 |
| 2e harness re-synchronisation | done — the invariant holds on all seven non-`TO-3` branches |

This design **supersedes the §2e cross-branch invariant**. That invariant
("everything under `k6/` byte-identical on all eight; `bench.env` the only per-branch
file") exists to stop the eight copies drifting. One copy on `main` and no `bench.env`
achieves the same end more strongly, so the invariant and its verification command retire
rather than being carried forward. Nothing else in the campaign design is affected:
workload points, staircases, selection rules, per-cell rates and the operational guards are
all properties of the workload, not of where the harness lives.

§8.3 "Branch hygiene" becomes largely obsolete — it exists because running a variant meant
switching branches, which this change removes.

### One tension worth deciding

Campaign §4.2 says a mis-bracketed staircase must be caught early, and §7 groups phase 1 by
workload point "precisely so this is discovered on the first variant" — run 1 of 8, not run
24 of 24. `run-suite.sh` runs all eight unattended, so the earliest a bad staircase can be
seen is after the whole block. `main`'s runbook already silently absorbed this, changing
§2.1 from "apply after every **run**" (TO-3's wording) to "apply after every **block**".

That is a real regression against the plan's stated intent, costing roughly five hours at
W-base when a staircase is wrong. It is not introduced by this design — it arrived with
`run-suite.sh` — but this design is the right moment to settle it. Options: accept the
block-level bracketing as `main`'s runbook already does, or give `run-suite.sh` a mode that
stops after the first variant for a bracketing check before continuing. **Left open; not a
blocker for implementation.**

## Decisions taken

**The variant branches keep their harness copies.** No commits are made to
`TO-1`..`TO-4` or `ES-1`..`ES-4`. Their `k6/`, `bench.env` and compose files become unused
by the suite but remain on the branches, leaving thesis history untouched.

**`PG_MAX_CONNECTIONS` is 600 for every variant** — the ES value. ES genuinely needs about
350 per replica (Hikari 50 plus Axon 300); 300 would starve it. TO needs about 50 per
replica, so 600 is harmless headroom. The re-baselining this forces on TO is in the safe
direction: more headroom, not less.

**Existing `bench-results/` are expendable.** Confirmed by the author on 2026-08-07: no
current run directory needs to remain comparable, so the change is free to alter the stack.
The campaign has not started measuring — phase 0 is complete but phase 1 has not run — so
there is nothing to invalidate. Every number the thesis reports will come from the unified
harness.

## Defects this fixes

**TSDB snapshots are silently broken on all four TO variants.** `scripts/prom_snapshot.sh`
POSTs to `/api/v1/admin/tsdb/snapshot`, but `--web.enable-admin-api` is set on `ES-1`..`ES-4`
and on none of `TO-1`..`TO-4` (verified by grep across all eight). Prometheus returns 404,
`curl -sf` fails, and `run-suite.sh` logs a non-fatal "TSDB snapshot FAILED" — with
snapshotting on by default since commit 34ca40f. Every TO run to date can therefore only
ever replay from `dump.json`, roughly 20 of the merged dashboard's 56 panels.

**`PG_MAX_CONNECTIONS` differs across the families being compared.** ES-4's own compose
comment states that `max_connections` "sizes Postgres' shared memory, so it must not differ
across branches being compared" — and then it differs, 600 against 300. One compose file
enforces the invariant the comment already demands.

**Harness drift becomes structurally impossible.** The `git diff --stat ES-4 <branch> --
k6/bench k6/lib k6/main.js docker-compose.bench.yml` ritual, and the CLAUDE.md paragraph
describing it, are retired.

## Risks and costs

**Re-baselining — accepted, and cheap.** A single `max_connections` changes the TO stack,
so new results are not comparable to existing `bench-results/` TO runs. The author has
confirmed those are expendable and the campaign has not begun measuring, so the cost is
zero in practice. The one rule that survives: never mix pre- and post-change TO numbers in
one table.

**Eight orphaned harness copies.** By decision above, the branches keep `k6/`, `bench.env`
and their compose files. Anyone running `./k6/bench/bench.sh` directly on a branch gets a
*different* stack than `main` produces — different service names, different
`max_connections`, no admin API on TO. That is the silent-divergence trap this change
exists to end, now surviving in a place the suite no longer touches. Mitigation is
documentation only: `main`'s README states it is the single supported entry point.

**`origin/main` diverges and cannot be fast-forwarded.** Local `main` is ahead 7, behind 13.
The 13 remote commits (through merge `ade9035`, PR #1
`TO-1-NewSpring-Normalised-CompTrans`) touch only `src/`, `build.gradle.kts`,
`docker-compose.yml`, the legacy `k6/run.sh` and `k6/reserve-load-test.js`, `load-test.sh`
and `monitoring/` — every one of them a file this plan deletes or replaces, and none of
them `scripts/`, `variants.env`, `workload.env` or the runbook. Local `main` is an
orchestration layer built on an abandoned prototype lineage. Reconciling would produce
modify/delete conflicts across all 39 files whose resolution is "delete anyway". No push or
merge is performed as part of this work; reconciliation is the author's decision.

**One-way door on `bench.env`.** Deriving variant identity from `variants.env` removes the
per-branch override. If a future variant needs a genuinely different stack — a different
image base, an extra sidecar — it needs a per-variant escape hatch that this design does
not provide. Acceptable while all eight variants share one API contract.

## Out of scope

- JVM tests. The variants' `src/test/` suites (`ES-4` has 6 classes, `TO-3` has 3) stay
  runnable per-branch by hand; no runner on `main` invokes them. `main`'s own
  `ApplicationTest.kt` is deleted with the prototype it tests.
- A fast k6 smoke mode. `run-suite.sh` keeps running full benchmark scenarios.
- Deleting harness copies from the variant branches.
- Reconciling with `origin/main`.

## Verification

The change is only trustworthy if it reproduces a known-good run.

1. `scripts/run-tests.sh` — 35 tests pass from `main`.
2. `scripts/build-images.sh` — all eight images build and `bench-results/images.json` is
   written.
3. **Reproduction:** run `ES-4` at `SCENARIO=steady RATE=30 DURATION=3m DISTINCT_ITEMS=6
   ITEMS_PER_ORDER=4` from `main`'s unified harness. Verdict must be PASS with 9/9 validity
   and 7/7 SLO, and throughput and e2e p95/p99 must sit within run-to-run noise of the same
   scenario run the old way. A mismatch means the unified stack is not the stack that
   produced the reference numbers.
4. **TO admin API:** run `TO-1` and confirm `bench-results/<run>/prom-snapshot/` is
   populated — impossible before this change.
5. **Replica derivation:** confirm `EXPECTED_REPLICAS` still comes from `REPLICAS` in `.env`
   and that `reset.sh`'s container-count assertion fires correctly at `REPLICAS=1`.
6. `python3 k6/bench/compare.py bench-results/*_steady_*` renders old and new runs together.
7. **Named points:** `POINT=W-hot scripts/run-suite.sh --only ES-4` produces a run directory
   labelled `W-hot` whose `meta.json` shows `distinctItems=8, itemsPerOrder=4`, and
   `DISTINCT_ITEMS=100 POINT=W-hot scripts/run-suite.sh` exits non-zero with a conflict
   message rather than running.
