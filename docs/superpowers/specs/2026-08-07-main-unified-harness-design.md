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

One copy each, taken from `ES-4` (the current reference):

- `k6/` — `main.js`, `lib/`, `run.sh`, `bench/` (`bench.sh`, `common.sh`, `reset.sh`,
  `dump.py`, `evaluate.py`, `compare.py`, `queries.promql`, `thresholds.json`,
  `wait-healthy.sh`)
- `docker-compose.yml` — unified, family-neutral service names, superset environment
- `docker-compose.bench.yml`
- `monitoring/` — `nginx/nginx.conf`, `prometheus/prometheus.yml`,
  `prometheus/prometheus-replay.yml`, `grafana/provisioning/`

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

## Decisions taken

**The variant branches keep their harness copies.** No commits are made to
`TO-1`..`TO-4` or `ES-1`..`ES-4`. Their `k6/`, `bench.env` and compose files become unused
by the suite but remain on the branches, leaving thesis history untouched.

**`PG_MAX_CONNECTIONS` is 600 for every variant** — the ES value. ES genuinely needs about
350 per replica (Hikari 50 plus Axon 300); 300 would starve it. TO needs about 50 per
replica, so 600 is harmless headroom. The re-baselining this forces on TO is in the safe
direction: more headroom, not less.

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

**Re-baselining.** A single `max_connections` changes the TO stack, so new results are not
comparable to existing `bench-results/` TO runs. Any thesis table mixing pre- and
post-change TO numbers must say so. This is a correctness fix that invalidates prior
numbers.

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
6. `python3 scripts/compare.py bench-results/*_steady_*` renders old and new runs together.
