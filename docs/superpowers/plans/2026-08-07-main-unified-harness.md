# `main` as the single benchmark harness — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `main` self-sufficient at run time — build the eight variant images once, then run every load test and harness test from `main` alone, with one harness shared identically by all eight variants.

**Architecture:** `main` stops orchestrating other branches' harnesses and starts owning the only harness. The whole `k6/` tree, the compose files and `monitoring/` move onto `main` in one copy. Because `main` now writes the compose file, it chooses family-neutral service names (`api`, `postgres`), which is what makes one file serve both families — Compose cannot interpolate service *keys*, so uniform names are the only route to a single file. Every per-branch value then collapses into a constant or a `variants.env` lookup, and all eight `bench.env` files disappear. Only `scripts/build-images.sh` still touches branches, via git worktrees, because building is the one step that genuinely needs branch content.

**Tech Stack:** Bash (harness orchestration), Python 3 stdlib `unittest` (harness tests — no pytest, no external deps), Docker Compose, k6 1.1.0 (pinned), Prometheus, Grafana.

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-08-07-main-unified-harness-design.md`. Governing campaign plan: `docs/superpowers/specs/2026-08-06-load-test-campaign-design.md` (copied onto `main` in Task 2).
- **All work happens on `main`.** No commits to `TO-1`..`TO-4` or `ES-1`..`ES-4`. Their harness copies are deliberately left in place and become unused.
- **Never push.** Commit locally only. The user pushes.
- **Never run the harness under `sudo`.** It refuses, and a root-owned `bench-results/` breaks every later run.
- **No JDK and no gradle are involved on `main`.** Images are built inside Docker by `build-images.sh`. Do not add a gradle build to `main`.
- **Harness tests:** `python3 -m unittest discover -s scripts/tests -t .` from the repository root. The `-t .` is required — tests do `from scripts.dashboards import build`, so the root must be the top-level directory. Baseline is **35 tests, OK**.
- **Canonical harness source is `TO-3` ∪ `ES-4`.** For `k6/` the two are byte-identical (verified: `git diff --stat TO-3 <branch> -- k6 docker-compose.bench.yml` is empty on all seven others). For `scripts/`, `TO-3` is a strict superset.
- **Service names are `api` and `postgres`** everywhere on `main`. Never `api-es` / `api-to`.
- **`PG_MAX_CONNECTIONS` default is 600** for every variant.
- **`API_CONTAINER_RE` is `.*-api-.*`** — unanchored with hyphen bounds. Prometheus anchors regexes fully, so the leading and trailing `.*` are required; a bare `api` would be ambiguous.
- **Existing `bench-results/` are expendable.** No run directory needs to stay comparable.
- **Work in the `main` worktree** at `.worktrees/main/`, not the repository root (which is checked out on `ES-4`).

## File Structure

| Path | Responsibility | Task |
|---|---|---|
| `k6/` (17 files) | The harness itself. Copied verbatim, then three files edited. | 2, 4, 6, 7 |
| `k6/bench/common.sh` | Per-run config. Loses `bench.env`, derives constants + `VARIANT` from the environment. | 4 |
| `k6/bench/bench.sh` | Orchestrator. Gains `run_label` / `point` in `meta.json`. | 6 |
| `k6/bench/compare.py` | Comparison tables. Gains a `point` column. The only `compare.py`. | 7 |
| `docker-compose.yml` | Unified stack, family-neutral names, superset env. Replaces the prototype's. | 3 |
| `docker-compose.bench.yml` | Bench overlay. Verbatim. | 2 |
| `monitoring/` | nginx, Prometheus, Grafana provisioning. Unified. | 2, 3 |
| `points.env` | Named workload points. New. | 5 |
| `scripts/lib.sh` | Registry, worktrees (build only), teardown, **point resolution**. | 5, 8 |
| `scripts/run-suite.sh` | Runs `main`'s harness per variant. Rewritten — no run-time worktrees. | 8 |
| `scripts/run-tests.sh` | Runs the harness test suite once. New. | 9 |
| `scripts/build-images.sh` | Unchanged. Still worktree-based. | — |
| `scripts/compare.py` | **Deleted** — byte-identical duplicate of `k6/bench/compare.py`. | 9 |
| `scripts/tests/`, `scripts/dashboards/`, `prom_*.sh`, `replay_run.py`, `grafana_snapshot.py`, `verify_dashboard_metrics.py` | Harness tooling. Copied (union of TO-3 and ES-4). | 2 |
| `README.md`, `docs/bench-campaign-runbook.md` | Docs. Updated to one harness, one `compare.py` path. | 10 |
| `src/`, `gradle*`, `Dockerfile`, `docs/index.html`, `docs/.swagger-codegen*` | **Deleted** (32 files). | 1 |

---

### Task 1: Delete the prototype application from `main`

**Files:**
- Delete: `src/` (21 files), `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`, `Dockerfile`, `docs/index.html`, `docs/.swagger-codegen-ignore`, `docs/.swagger-codegen/VERSION`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: nothing.
- Produces: a `main` with no application code. Later tasks assume `src/` and the gradle build are gone.

None of these are read by any script: `build-images.sh` reads `$wt/Dockerfile` from a worktree, and `lib.sh`'s `dc_for` reads `$wt/docker-compose.yml`. `main`'s copies are dead.

- [ ] **Step 1: Confirm nothing on `main` references the files about to be deleted**

Run:
```bash
cd .worktrees/main
git grep -nE 'gradlew|build\.gradle|settings\.gradle|swagger' -- scripts variants.env workload.env
```
Expected: no output. (`build-images.sh` mentions `src/` and `gradle/` only inside a comment explaining the Dockerfile's COPY layers — that comment is about the *worktree's* Dockerfile and stays correct.)

- [ ] **Step 2: Delete the prototype**

```bash
cd .worktrees/main
git rm -r -q src gradle docs/.swagger-codegen
git rm -q build.gradle.kts settings.gradle.kts gradle.properties gradlew gradlew.bat Dockerfile docs/index.html docs/.swagger-codegen-ignore
```

- [ ] **Step 3: Verify the count is exactly 32**

Run: `git diff --cached --name-only --diff-filter=D | wc -l`
Expected: `32`

- [ ] **Step 4: Trim `.gitignore`**

Replace the Java/gradle/IDE-tooling stanzas. The file becomes exactly:

```gitignore
### IntelliJ IDEA ###
.idea
*.iws
*.iml
*.ipr
out/

### VS Code ###
.vscode/

.claude
.kotlin
CLAUDE.md

# per-variant git worktrees created by scripts/build-images.sh
.worktrees/

# central benchmark artifacts: every variant's run lands here
bench-results/

__pycache__/
```

- [ ] **Step 5: Verify the scripts still parse**

Run:
```bash
cd .worktrees/main
bash -n scripts/lib.sh && bash -n scripts/run-suite.sh && bash -n scripts/build-images.sh && echo PARSE-OK
```
Expected: `PARSE-OK`

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "chore: delete the prototype application from main

main carries no application code anybody benchmarks; src/ was an early
prototype kept only for history, and no script on main ever read it,
the Dockerfile, or the gradle build. Images are built from each variant
branch's own worktree.

.gitignore loses the Java, gradle, STS and NetBeans stanzas, which no
longer describe anything in this tree."
```

---

### Task 2: Import the harness onto `main`

**Files:**
- Create (verbatim from `ES-4`): `k6/README.md`, `k6/main.js`, `k6/run.sh`, `k6/lib/{api,config,profiles,summary,workload}.js`, `k6/bench/{bench.sh,common.sh,compare.py,dump.py,evaluate.py,queries.promql,reset.sh,thresholds.json,wait-healthy.sh}`, `docker-compose.bench.yml`, `monitoring/nginx/nginx.conf`, `monitoring/prometheus/prometheus-replay.yml`, `monitoring/grafana/provisioning/dashboards/{bench-replay.json,the-dashboard.json}`, `scripts/{__init__.py,replay_run.py,prom_snapshot.sh,prom_archive.sh,verify_dashboard_metrics.py}`, `scripts/dashboards/{__init__.py,build.py,spec.py}`, `scripts/tests/{__init__.py,test_bench_sh.py,test_build.py,test_evaluate.py,test_replay_run.py,test_spec.py,fixtures/mini-dump.json}`
- Create (verbatim from `TO-3` — these do **not** exist on `ES-4`): `scripts/prom_restore.sh`, `scripts/grafana_snapshot.py`, `docs/superpowers/specs/2026-08-06-load-test-campaign-design.md`, `docs/superpowers/plans/2026-08-06-load-test-campaign-phase0.md`
- Delete: `monitoring/grafana/provisioning/dashboards/inventory-to-dashboard.json` (superseded by `the-dashboard.json`)
- Overwrite: `monitoring/grafana/provisioning/dashboards/dashboard.yml`, `monitoring/grafana/provisioning/datasources/prometheus.yml` (from `ES-4`)

**Interfaces:**
- Consumes: Task 1's tree.
- Produces: `k6/bench/bench.sh`, `k6/bench/common.sh`, `scripts/tests/` on `main`. Tasks 3–9 modify these in place.

`docker-compose.yml` and `monitoring/prometheus/prometheus.yml` are **not** copied here — Task 3 writes unified versions.

`scripts/bench_run.sh` is deliberately **not** copied: it is `bench.sh` plus a TSDB copy surviving `down -v`, and `run-suite.sh` already inlines exactly that.

- [ ] **Step 1: Copy the harness from `ES-4`**

```bash
cd .worktrees/main
git checkout ES-4 -- k6 docker-compose.bench.yml \
    monitoring/nginx monitoring/prometheus/prometheus-replay.yml \
    monitoring/grafana/provisioning/dashboards/bench-replay.json \
    monitoring/grafana/provisioning/dashboards/the-dashboard.json \
    monitoring/grafana/provisioning/dashboards/dashboard.yml \
    monitoring/grafana/provisioning/datasources/prometheus.yml \
    scripts/__init__.py scripts/replay_run.py scripts/prom_snapshot.sh \
    scripts/prom_archive.sh scripts/verify_dashboard_metrics.py \
    scripts/dashboards scripts/tests
```

- [ ] **Step 2: Copy the two `TO-3`-only scripts and the campaign documents**

`docs/bench-replay.md` (identical on both branches) references `prom_restore.sh`, which exists only on `TO-3` — copying from `ES-4` alone would leave a document pointing at a missing script.

```bash
cd .worktrees/main
git checkout TO-3 -- scripts/prom_restore.sh scripts/grafana_snapshot.py \
    docs/bench-replay.md \
    docs/superpowers/specs/2026-08-06-load-test-campaign-design.md \
    docs/superpowers/plans/2026-08-06-load-test-campaign-phase0.md
```

- [ ] **Step 3: Drop the superseded prototype dashboard**

```bash
cd .worktrees/main
git rm -q monitoring/grafana/provisioning/dashboards/inventory-to-dashboard.json
```

- [ ] **Step 4: Verify every script `docs/bench-replay.md` names now exists**

Run:
```bash
cd .worktrees/main
for s in prom_restore.sh prom_snapshot.sh prom_archive.sh replay_run.py; do
  grep -q "$s" docs/bench-replay.md && { test -f "scripts/$s" && echo "OK   $s" || echo "MISSING $s"; }
done
```
Expected: four `OK` lines, no `MISSING`.

- [ ] **Step 5: Run the harness test suite**

Run:
```bash
cd .worktrees/main
python3 -m unittest discover -s scripts/tests -t .
```
Expected: `Ran 35 tests` … `OK`

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(bench): import the harness onto main

One copy of k6/, the bench compose overlay, monitoring and the harness
tooling, so main can run a benchmark without a variant worktree.

Source is TO-3 union ES-4. For k6/ the two are byte-identical, so either
serves; for scripts/ TO-3 is a strict superset, and prom_restore.sh in
particular is referenced by docs/bench-replay.md on both branches while
existing only on TO-3. grafana_snapshot.py comes across for the same
reason. The campaign design and phase-0 plan move too: main is what
executes the campaign, so main must carry its authority.

bench_run.sh is deliberately left behind — run-suite.sh already inlines
the TSDB copy it exists for. inventory-to-dashboard.json is dropped as
superseded by the-dashboard.json.

docker-compose.yml and prometheus.yml are NOT copied; the next commit
writes unified versions."
```

---

### Task 3: Unified, family-neutral stack

**Files:**
- Create: `docker-compose.yml` (replaces the prototype's — overwrite)
- Create: `monitoring/prometheus/prometheus.yml` (overwrite)
- Modify: `monitoring/nginx/nginx.conf`

**Interfaces:**
- Consumes: Task 2's `docker-compose.bench.yml`, which references services `api`, `postgres`, `k6`, `nginx`, `prometheus`, `grafana`.
- Produces: services named **`api`** and **`postgres`**, volume `postgres-data`, Prometheus job **`inventory`**. Task 4's `common.sh` constants must match these exactly.

- [ ] **Step 1: Start from `ES-4`'s file, then apply the edits below**

Do **not** transcribe the file from scratch — it has ten services, and
`docker-compose.bench.yml` and `bench.sh` both reference `k6`, `grafana-renderer` and
`grafana-reporter`, which a hand-written version silently drops. Copy, then edit:

```bash
cd .worktrees/main
git checkout ES-4 -- docker-compose.yml
```

Then apply exactly these changes, leaving `k6`, `cadvisor`, `grafana`, `grafana-renderer`
and `grafana-reporter` untouched apart from the renames:

1. Rename service `postgres-es` → `postgres`, `api-es` → `api` (both the service key and
   every `depends_on` reference).
2. Rename `container_name: postgres-es` → `postgres`; volume `postgres-es-data` →
   `postgres-data` in both the service's `volumes:` and the top-level `volumes:` block.
3. Point `DATA_SOURCE_NAME` on `postgres-exporter` at `postgres:5432`.
4. Point `DB_JDBC_URL` / `DB_R2DBC_URL` on `api` at `postgres:5432`.
5. Replace the `image:` line on `api` with the mandatory form (below).
6. Update nginx's `depends_on: - api-es` → `- api` and its port comment.

The two services that change substantively must end up exactly as follows.

`postgres`:

```yaml
  postgres:
    image: postgres:16-alpine
    container_name: postgres
    environment:
      POSTGRES_DB:       inventory
      POSTGRES_USER:     inventory
      POSTGRES_PASSWORD: inventory
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U inventory -d inventory"]
      interval: 5s
      timeout: 3s
      retries: 10
    # 600 for EVERY variant, not per family. ES needs ~350 per replica (Hikari 50 +
    # Axon 300); TO needs ~50. max_connections sizes Postgres' shared memory, so a value
    # that differed between the families being compared was itself a confound — which the
    # old ES compose file warned about while the TO one used 300 anyway.
    command: postgres -c max_connections=${PG_MAX_CONNECTIONS:-600}
    volumes:
      - postgres-data:/var/lib/postgresql/data

  postgres-exporter:
    image: prometheuscommunity/postgres-exporter:v0.15.0
    container_name: postgres-exporter
    environment:
      DATA_SOURCE_NAME: "postgresql://inventory:inventory@postgres:5432/inventory?sslmode=disable"
    ports:
      - "9187:9187"
    depends_on:
      postgres:
        condition: service_healthy

  api:
    # No default tag. IMAGE_TAG is always set by the harness from variants.env; a default
    # here would let a bare `docker compose up` silently run whichever variant was baked in.
    image: ${IMAGE_TAG:?IMAGE_TAG must be set — use scripts/run-suite.sh}
    # No container_name and no host port: scaled via deploy.replicas, and both would
    # collide on the second replica. Replicas are reached in-network as `api`
    # (round-robin A records); nginx is the single published entry point on :8080.
    #
    # Scale with REPLICAS in .env only — never also pass --scale, which makes Compose
    # remove middle replicas.
    environment:
      DB_JDBC_URL:         "jdbc:postgresql://postgres:5432/inventory"
      DB_R2DBC_URL:        "r2dbc:postgresql://postgres:5432/inventory"
      DB_USER:             inventory
      DB_PASSWORD:         inventory
      # Superset across families. Spring Boot ignores an environment variable with no
      # matching property, so AXON_* and CACHE_* are inert on TO, and CACHE_* is inert on
      # ES-1/ES-2/ES-3, which have no confirmed-state cache.
      AXON_JDBC_POOL_SIZE: "${AXON_JDBC_POOL_SIZE:-300}"
      API_REPLICAS:        "${REPLICAS:-1}"
      CACHE_TTL:           "${CACHE_TTL:-10m}"
      CACHE_MAXIMUM_SIZE:  "${CACHE_MAXIMUM_SIZE:-10000}"
    expose:
      - "8080"
    deploy:
      replicas: ${REPLICAS:-1}
    depends_on:
      postgres:
        condition: service_healthy

The top-level `volumes:` block becomes:

```yaml
volumes:
  postgres-data:
  prometheus-data:
  grafana-data:
```

And `prometheus`'s `command:` list gains one entry — this is the TO snapshot fix, so do
not omit it:

```yaml
    command:
      - --config.file=/etc/prometheus/prometheus.yml
      - --storage.tsdb.path=/prometheus
      - --web.enable-lifecycle
      # Required by scripts/prom_snapshot.sh, which POSTs to
      # /api/v1/admin/tsdb/snapshot. The TO compose files omitted this, so TSDB
      # preservation silently failed on every TO run.
      - --web.enable-admin-api
```

`ES-4` already carries `--web.enable-admin-api`, so after the copy this line should
already be present — verify rather than assume, because the whole point is that it must
hold for TO variants too, which now share this file.

- [ ] **Step 2: Write the unified `monitoring/prometheus/prometheus.yml`**

Take `ES-4`'s file and change the job label and DNS name. The scrape config becomes:

```yaml
  - job_name: inventory
    metrics_path: /actuator/prometheus
    # DNS discovery, not a static target: the api service is scaled with deploy.replicas,
    # so the number of endpoints behind the name varies. At REPLICAS=1 this resolves to
    # exactly one target. The `instance` label becomes a container IP rather than
    # `api:8080` — harmless, because every expression in queries.promql is sum()-wrapped.
    dns_sd_configs:
      - names: ['api']
        type: A
        port: 8080
        refresh_interval: 5s
```

Leave every other scrape job (`cadvisor`, `postgres-exporter`, `prometheus` itself) exactly as `ES-4` has it.

- [ ] **Step 3: Point nginx at the neutral service name**

In `monitoring/nginx/nginx.conf`, change `set $backend "api-es";` to `set $backend "api";` and update the two comment mentions of `api-es` to `api`.

- [ ] **Step 3b: Create `main`'s `.env`**

`.env` is tracked on `ES-4` and `TO-3` but **absent on `main`**, and it is not gitignored.
It is the single source of truth for the replica count: Compose auto-loads it from the
compose file's directory, and `common.sh` derives `EXPECTED_REPLICAS` from it rather than
duplicating the number, so that `reset.sh`'s container-count assertion checks what actually
runs. Without it every run silently assumes `REPLICAS=1` and `PG_MAX_CONNECTIONS` falls
back to the compose default.

Create `.env` at the repository root:

```dotenv
# Auto-loaded by docker compose, so these are sticky across invocations.
#
# REPLICAS drives BOTH the container count and API_REPLICAS, which on ES branches sets the
# saga per-node claim to ceil(axon.saga.total-segments / replicas). If the two ever
# diverge, segments are left unclaimed and those orders are never processed. Never also
# pass --scale: mixing it with deploy.replicas makes Compose remove middle replicas.
#
# REPLICAS=1 is the measurement-grade configuration. Above 1 the ES rejection rate is an
# artefact of lost write races rather than of stock, so read multi-replica runs as a
# contention study, not as a throughput result.
REPLICAS=1

# ~350 connections per ES replica (Hikari 50 + Axon 300); TO needs ~50. One value for both
# families, because max_connections sizes Postgres' shared memory and must not differ
# across branches being compared. Add ~350 per additional ES replica.
PG_MAX_CONNECTIONS=600
```

- [ ] **Step 4: Verify the compose file is valid and names only neutral services**

Run:
```bash
cd .worktrees/main
IMAGE_TAG=placeholder:latest docker compose -f docker-compose.yml -f docker-compose.bench.yml config --services | sort
```
Expected to include `api`, `postgres`, `nginx`, `prometheus`, `grafana`, `k6`, `cadvisor`, `postgres-exporter`, `grafana-renderer`, `grafana-reporter` — and **no** `api-es`, `api-to`, `postgres-es` or `postgres-to`.

- [ ] **Step 5: Verify `IMAGE_TAG` is mandatory**

Run:
```bash
cd .worktrees/main
docker compose -f docker-compose.yml config >/dev/null 2>&1 && echo "BAD: accepted no IMAGE_TAG" || echo "OK: IMAGE_TAG required"
```
Expected: `OK: IMAGE_TAG required`

- [ ] **Step 6: Verify no family token survives anywhere in the stack config**

Run:
```bash
cd .worktrees/main
grep -rnE 'api-(es|to)|postgres-(es|to)|inventory-(es|to)' docker-compose.yml monitoring/ && echo "FOUND — fix these" || echo "CLEAN"
```
Expected: `CLEAN`

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(bench): one family-neutral stack for all eight variants

Compose cannot interpolate service keys, so api-\${FAMILY} is not
expressible and a single compose file requires uniform service names.
main owns the file, so the names are uniform by construction: api and
postgres. The variant branches need no edit — their compose files simply
stop being read.

Three substantive changes beyond the renaming:

- --web.enable-admin-api is now always on. prom_snapshot.sh POSTs to
  /api/v1/admin/tsdb/snapshot, and the four TO compose files omitted the
  flag, so TSDB preservation has been failing on every TO run since it
  became the default.
- PG_MAX_CONNECTIONS is 600 for everyone. It sizes Postgres' shared
  memory, so 600-on-ES against 300-on-TO was a confound between the very
  families being compared.
- IMAGE_TAG has no default, so a bare 'docker compose up' cannot
  silently run whichever variant was last baked into the file."
```

---

### Task 4: `common.sh` derives config instead of reading `bench.env`

**Files:**
- Modify: `k6/bench/common.sh` (the `bench.env` block, lines 8–15, and the `: "${VAR:?...}"` assertions)
- Test: `scripts/tests/test_common_sh.py` (create)

**Interfaces:**
- Consumes: Task 3's service names (`api`, `postgres`), job label (`inventory`).
- Produces: `common.sh` requires only `VARIANT` and `IMAGE_TAG` from the environment and exports `API_SVC=api`, `DB_SVC=postgres`, `PROM_JOB=inventory`, `API_CONTAINER_RE=.*-api-.*`, `DB_NAME=inventory`, `DB_USER=inventory`, `VARIANT_FAMILY`. Task 8's `run-suite.sh` sets `VARIANT`, `VARIANT_FAMILY` and `IMAGE_TAG`.

- [ ] **Step 1: Write the failing test**

Create `scripts/tests/test_common_sh.py`:

```python
import os
import subprocess
import unittest

HERE = os.path.dirname(__file__)
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
COMMON_SH = os.path.join(ROOT, "k6", "bench", "common.sh")


def source_common(env_overrides, want):
    """Source common.sh with a controlled environment and echo one variable back."""
    env = {k: v for k, v in os.environ.items()
           if k not in ("VARIANT", "VARIANT_FAMILY", "IMAGE_TAG", "API_SVC",
                        "DB_SVC", "PROM_JOB", "API_CONTAINER_RE")}
    env.update(env_overrides)
    return subprocess.run(
        ["bash", "-c", f'. "{COMMON_SH}" >/dev/null 2>&1; printf "%s" "${{{want}}}"'],
        env=env, capture_output=True, text=True)


class DerivedConfig(unittest.TestCase):
    BASE = {"VARIANT": "ES-4", "VARIANT_FAMILY": "ES", "IMAGE_TAG": "x:latest"}

    def test_api_service_is_family_neutral(self):
        self.assertEqual(source_common(self.BASE, "API_SVC").stdout, "api")

    def test_db_service_is_family_neutral(self):
        self.assertEqual(source_common(self.BASE, "DB_SVC").stdout, "postgres")

    def test_prom_job_is_family_neutral(self):
        self.assertEqual(source_common(self.BASE, "PROM_JOB").stdout, "inventory")

    def test_container_regex_is_unanchored_and_hyphen_bounded(self):
        # Prometheus anchors regexes fully, so the leading/trailing .* are required;
        # the hyphens stop it matching a sibling container.
        self.assertEqual(source_common(self.BASE, "API_CONTAINER_RE").stdout, ".*-api-.*")

    def test_variant_is_taken_from_the_environment(self):
        env = dict(self.BASE, VARIANT="TO-1")
        self.assertEqual(source_common(env, "VARIANT").stdout, "TO-1")

    def test_missing_variant_is_fatal(self):
        env = {k: v for k, v in self.BASE.items() if k != "VARIANT"}
        result = subprocess.run(
            ["bash", "-c", f'. "{COMMON_SH}"'],
            env={**os.environ, **env, "VARIANT": ""},
            capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("VARIANT", result.stderr)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd .worktrees/main && python3 -m unittest scripts.tests.test_common_sh -v`
Expected: FAIL — `common.sh` still exits fatally on the missing `bench.env`.

- [ ] **Step 3: Replace the `bench.env` block in `common.sh`**

Replace lines 8–15 (the `if [ -f "$REPO_ROOT/bench.env" ] … fi` block) with:

```bash
# There is no bench.env any more. main owns the only harness and names the compose
# services itself, so every value that used to be per-branch is either a constant or comes
# from variants.env via scripts/run-suite.sh, which exports VARIANT, VARIANT_FAMILY and
# IMAGE_TAG before invoking this.
#
# Keeping these as overridable defaults rather than hardcoding them lets a one-off manual
# run point at a differently-named stack without editing the harness.
API_SVC="${API_SVC:-api}"
DB_SVC="${DB_SVC:-postgres}"
PROM_JOB="${PROM_JOB:-inventory}"
# UNANCHORED with hyphen bounds. The api service is scaled with deploy.replicas and so
# carries no container_name, which means cadvisor sees `<project>-api-1`, `-2`, ...
# queries.promql matches it with an anchored name=~"$CRE", and Prometheus anchors regexes
# fully — so the leading and trailing .* are required. A bare `api` would also match
# sibling containers.
API_CONTAINER_RE="${API_CONTAINER_RE:-.*-api-.*}"
DB_NAME="${DB_NAME:-inventory}"
DB_USER="${DB_USER:-inventory}"
VARIANT_FAMILY="${VARIANT_FAMILY:-}"
export API_SVC DB_SVC PROM_JOB API_CONTAINER_RE DB_NAME DB_USER VARIANT_FAMILY
```

Then replace the six assertions with a single one — the rest are now defaulted, so asserting them would be theatre:

```bash
: "${VARIANT:?VARIANT must be set (scripts/run-suite.sh sets it from variants.env)}"
```

- [ ] **Step 4: Run the new test**

Run: `cd .worktrees/main && python3 -m unittest scripts.tests.test_common_sh -v`
Expected: 6 tests, `OK`

- [ ] **Step 5: Run the whole suite for regressions**

Run: `cd .worktrees/main && python3 -m unittest discover -s scripts/tests -t .`
Expected: `Ran 41 tests` … `OK`

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(bench): derive per-run config instead of reading bench.env

bench.env existed because each branch carried its own harness and had to
tell it which services to talk to. With one harness on main that names
the services itself, every value in it is a constant or a variants.env
lookup, so all eight copies are obsolete.

common.sh now needs only VARIANT from the environment. The rest are
overridable defaults, so a one-off manual run can still point at a
differently-named stack."
```

---

### Task 5: Named workload points

**Files:**
- Create: `points.env`
- Modify: `scripts/lib.sh` (append the point-resolution section)
- Test: `scripts/tests/test_points.py` (create)

**Interfaces:**
- Consumes: `scripts/lib.sh`'s `die()` and `MAIN_ROOT`.
- Produces, for Task 8: `snapshot_shell_knobs()` (call first), `resolve_point()` (call second, reads `$POINT`), and the variables `POINT_IDENTITY_KNOBS`, `POINT_CALIBRATION_KNOBS`, `POINT_RESOLVED`. `resolve_point` exports the knobs and sets `RUN_LABEL` when unset.

Values are copied from campaign design §4.2 (W-points) and §6.1 plus the runbook's phase-2 staircases (C-cells).

- [ ] **Step 1: Write the failing test**

Create `scripts/tests/test_points.py`:

```python
import os
import subprocess
import unittest

HERE = os.path.dirname(__file__)
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
LIB_SH = os.path.join(ROOT, "scripts", "lib.sh")

KNOBS = ("POINT", "DISTINCT_ITEMS", "ITEMS_PER_ORDER", "PAYLOAD_BYTES",
         "RESERVE_DELAY_MS", "STEP_START", "STEP_INC", "STEP_COUNT", "RUN_LABEL")


def resolve(env_overrides, report=("DISTINCT_ITEMS",)):
    env = {k: v for k, v in os.environ.items() if k not in KNOBS}
    env.update(env_overrides)
    script = (f'. "{LIB_SH}"\n'
              'snapshot_shell_knobs\n'
              'resolve_point\n'
              + "\n".join(f'printf "%s\\n" "${{{k}:-}}"' for k in report))
    return subprocess.run(["bash", "-c", script], env=env,
                          capture_output=True, text=True)


class PointResolution(unittest.TestCase):
    def test_no_point_changes_nothing(self):
        r = resolve({}, ("DISTINCT_ITEMS", "RUN_LABEL"))
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stdout.split("\n")[:2], ["", ""])

    def test_named_point_sets_identity_knobs(self):
        r = resolve({"POINT": "W-hot"}, ("DISTINCT_ITEMS", "ITEMS_PER_ORDER"))
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stdout.split("\n")[:2], ["8", "4"])

    def test_named_point_sets_the_run_label(self):
        r = resolve({"POINT": "W-fan"}, ("RUN_LABEL",))
        self.assertEqual(r.stdout.strip(), "W-fan")

    def test_points_compose(self):
        r = resolve({"POINT": "W-base,C11"},
                    ("DISTINCT_ITEMS", "PAYLOAD_BYTES", "RESERVE_DELAY_MS", "RUN_LABEL"))
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stdout.split("\n")[:4],
                         ["100", "1048576", "25", "W-base-C11"])

    def test_conflicting_identity_knob_is_fatal(self):
        # The whole point: honouring this override would make the label a lie.
        r = resolve({"POINT": "W-base", "DISTINCT_ITEMS": "8"})
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("DISTINCT_ITEMS", r.stderr)

    def test_matching_identity_knob_is_accepted(self):
        r = resolve({"POINT": "W-base", "DISTINCT_ITEMS": "100"})
        self.assertEqual(r.returncode, 0)

    def test_calibration_knob_may_be_overridden(self):
        # Campaign 4.2's bracketing rule expects staircases to be re-tuned.
        r = resolve({"POINT": "W-base", "STEP_INC": "80"}, ("STEP_INC", "STEP_START"))
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stdout.split("\n")[:2], ["80", "40"])

    def test_unknown_point_is_fatal(self):
        r = resolve({"POINT": "W-nope"})
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("W-nope", r.stderr)

    def test_explicit_run_label_survives(self):
        r = resolve({"POINT": "W-hot", "RUN_LABEL": "rerun2"}, ("RUN_LABEL",))
        self.assertEqual(r.stdout.strip(), "rerun2")


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd .worktrees/main && python3 -m unittest scripts.tests.test_points -v`
Expected: FAIL — `snapshot_shell_knobs: command not found`

- [ ] **Step 3: Create `points.env`**

```
# Named workload points, so a run's label cannot disagree with its knobs.
#
# The harness itself has no notion of W-base or C11 — a point is identified only by the
# values meta.json records under config.distinctItems / itemsPerOrder / payloadBytes /
# reserveDelayMs. This file is what binds those numbers to a name, once, for all eight
# variants.
#
#   POINT=W-hot        scripts/run-suite.sh     # phase 1
#   POINT=W-base,C11   scripts/run-suite.sh     # phase 2 cell
#
# Two classes of value, with different override rules:
#
#   IDENTITY     DISTINCT_ITEMS ITEMS_PER_ORDER PAYLOAD_BYTES RESERVE_DELAY_MS
#                These ARE the point. A conflicting value in the environment is FATAL —
#                honouring it is exactly what would make the label lie.
#
#   CALIBRATION  STEP_START STEP_INC STEP_COUNT
#                Defaults only; the environment wins silently. The campaign's bracketing
#                rule (design 4.2) expects staircases to be re-tuned and every variant at
#                that point re-run, so locking these would fight the plan.
#
# Sources: campaign design 4.2 (W staircases), 3 (W knobs), 6.1 (cells); the runbook's
# phase-2 staircases (C cells).
#
# <point>  <knobs...>

W-base   DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4    STEP_START=40 STEP_INC=40 STEP_COUNT=10
W-hot    DISTINCT_ITEMS=8   ITEMS_PER_ORDER=4    STEP_START=20 STEP_INC=20 STEP_COUNT=12
W-fan    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16   STEP_START=10 STEP_INC=10 STEP_COUNT=12

C00      PAYLOAD_BYTES=0       RESERVE_DELAY_MS=0
C01      PAYLOAD_BYTES=0       RESERVE_DELAY_MS=25   STEP_START=10 STEP_INC=15 STEP_COUNT=10
C10      PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=0    STEP_START=5  STEP_INC=5  STEP_COUNT=10
C11      PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25   STEP_START=2  STEP_INC=3  STEP_COUNT=10
```

- [ ] **Step 4: Append the resolution section to `scripts/lib.sh`**

```bash
# ---------------------------------------------------------------- workload points

POINT_IDENTITY_KNOBS="DISTINCT_ITEMS ITEMS_PER_ORDER PAYLOAD_BYTES RESERVE_DELAY_MS"
POINT_CALIBRATION_KNOBS="STEP_START STEP_INC STEP_COUNT"

# Capture what the SHELL set, before any point expands. Afterwards a point-set knob and a
# shell-set knob are indistinguishable, so the conflict check would have nothing to compare
# against. Must be called before resolve_point.
snapshot_shell_knobs() {
    local k
    for k in $POINT_IDENTITY_KNOBS $POINT_CALIBRATION_KNOBS; do
        eval "__SHELL_$k=\"\${$k:-}\""
    done
}

# Emit "KEY=VALUE" per line for one named point.
read_point() {
    local p="$1" fields
    fields="$(sed -e 's/#.*//' "$MAIN_ROOT/points.env" \
              | awk -v p="$p" '$1 == p { for (i = 2; i <= NF; i++) print $i }')"
    [ -n "$fields" ] || die "unknown point '$p' (known: $(known_points | tr '\n' ' '))"
    printf '%s\n' "$fields"
}

known_points() {
    sed -e 's/#.*//' -e '/^[[:space:]]*$/d' "$MAIN_ROOT/points.env" | awk '{print $1}'
}

# Expand $POINT (comma-separated, composable) into the workload knobs and RUN_LABEL.
resolve_point() {
    [ -n "${POINT:-}" ] || return 0
    local p kv key val prior label=""
    for p in ${POINT//,/ }; do
        label="${label:+$label-}$p"
        while IFS= read -r kv; do
            [ -n "$kv" ] || continue
            key="${kv%%=*}"; val="${kv#*=}"
            eval "prior=\"\${__SHELL_$key:-}\""
            case " $POINT_IDENTITY_KNOBS " in
                *" $key "*)
                    if [ -n "$prior" ] && [ "$prior" != "$val" ]; then
                        die "POINT=$p defines $key=$val but the environment sets $key=$prior. $key is an identity knob: honouring the override would make the run label '$label' describe a workload it did not run. Drop the override, or drop POINT and set every knob by hand."
                    fi
                    export "$key=$val" ;;
                *)
                    [ -n "$prior" ] || export "$key=$val" ;;
            esac
        done < <(read_point "$p")
    done
    POINT_RESOLVED="$label"
    RUN_LABEL="${RUN_LABEL:-$label}"
    export POINT_RESOLVED RUN_LABEL
}
```

- [ ] **Step 5: Run the point tests**

Run: `cd .worktrees/main && python3 -m unittest scripts.tests.test_points -v`
Expected: 9 tests, `OK`

- [ ] **Step 6: Run the whole suite**

Run: `cd .worktrees/main && python3 -m unittest discover -s scripts/tests -t .`
Expected: `Ran 50 tests` … `OK`

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(bench): named workload points, so a run label cannot lie

The harness has no notion of W-base or the C cells — those names live
only in the campaign markdown, RUN_LABEL is free text that reaches only
the directory name, and a point is identifiable solely by the knob values
meta.json records. Nothing bound a point's name to its numbers across the
campaign's 58 labelled runs.

POINT now sets the knobs and the label together, from one definition
applied identically to all eight variants.

The override rule is split so the guarantee holds without fighting the
campaign: identity knobs conflict-and-die, because honouring the override
is precisely what would make the label lie; staircase knobs are defaults
the shell may override, because design 4.2 expects them to be re-tuned.
Precedence resolves against a snapshot of the shell taken before any
point expands, since afterwards the two sources are indistinguishable."
```

---

### Task 6: `bench.sh` records the label and point

**Files:**
- Modify: `k6/bench/bench.sh` (the `meta.json` writer, around line 240)
- Test: `scripts/tests/test_bench_sh.py` (extend)

**Interfaces:**
- Consumes: `POINT_RESOLVED` and `RUN_LABEL` from Task 5, exported by Task 8's `run-suite.sh`.
- Produces: `meta.json` keys `run_label` and `point`. Task 7's `compare.py` reads `meta.point`.

- [ ] **Step 1: Write the failing test**

Append to `scripts/tests/test_bench_sh.py`:

```python
class MetaRecordsThePoint(unittest.TestCase):
    """meta.json must carry the point as data, not only inside the directory name.

    Without this a point is recoverable only by re-deriving it from four separate
    config values, and compare.py cannot group by it at all.
    """

    def setUp(self):
        with open(BENCH_SH) as fh:
            self.script = fh.read()

    def test_meta_json_has_a_run_label_field(self):
        self.assertIn('"run_label":', self.script)

    def test_meta_json_has_a_point_field(self):
        self.assertIn('"point":', self.script)

    def test_point_field_prefers_the_resolved_value(self):
        # POINT_RESOLVED is normalised ("W-base,C11" -> "W-base-C11"); raw POINT is the
        # fallback for a hand-run that never went through resolve_point.
        self.assertIn('${POINT_RESOLVED:-${POINT:-}}', self.script)
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd .worktrees/main && python3 -m unittest scripts.tests.test_bench_sh -v`
Expected: 3 new tests FAIL, the 5 existing `RunLabel` tests still pass.

- [ ] **Step 3: Add the fields to the `meta.json` writer**

In `k6/bench/bench.sh`, inside the `meta = {` dictionary, immediately after the
`"scenario": "$SCENARIO",` line, insert:

```python
    "run_label": "${RUN_LABEL:-}",
    "point": "${POINT_RESOLVED:-${POINT:-}}",
```

- [ ] **Step 4: Run the tests**

Run: `cd .worktrees/main && python3 -m unittest scripts.tests.test_bench_sh -v`
Expected: 8 tests, `OK`

- [ ] **Step 5: Verify the heredoc still produces valid JSON**

The `meta.json` block is a `python3 - <<PYEOF` heredoc with shell interpolation, so an
unbalanced brace or quote breaks it only at run time. Check it parses:

```bash
cd .worktrees/main
RUN_LABEL=W-hot POINT_RESOLVED=W-hot bash -c '
  sed -n "/^meta = {/,/^}/p" k6/bench/bench.sh > /tmp/meta-block.txt
  grep -c "run_label\|point" /tmp/meta-block.txt'
```
Expected: `2`

- [ ] **Step 6: Run the whole suite**

Run: `cd .worktrees/main && python3 -m unittest discover -s scripts/tests -t .`
Expected: `Ran 53 tests` … `OK`

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(bench): record run_label and point in meta.json

RUN_LABEL reached only the run directory name, and meta.json had no field
for it at all, so a point was recoverable only by re-deriving it from
four separate config values. Recording it makes the point machine-
readable and lets compare.py group by it."
```

---

### Task 7: `compare.py` gains a `point` column

**Files:**
- Modify: `k6/bench/compare.py` (the `COLUMNS["core"]` list, line 20)
- Test: `scripts/tests/test_compare.py` (create)

**Interfaces:**
- Consumes: `meta.point` from Task 6.
- Produces: a `point` column in the core table. No later task depends on it.

- [ ] **Step 1: Write the failing test**

Create `scripts/tests/test_compare.py`:

```python
import importlib.util
import os
import unittest

HERE = os.path.dirname(__file__)
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
COMPARE = os.path.join(ROOT, "k6", "bench", "compare.py")


def load_compare():
    spec = importlib.util.spec_from_file_location("compare_mod", COMPARE)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class CoreColumns(unittest.TestCase):
    def setUp(self):
        self.core = load_compare().COLUMNS["core"]

    def test_core_table_shows_the_point(self):
        self.assertIn("point", [header for header, _, _ in self.core])

    def test_point_reads_the_meta_field_not_the_directory_name(self):
        path = next(p for h, p, _ in self.core if h == "point")
        self.assertEqual(path, "meta.point")

    def test_point_sits_next_to_the_knobs_it_names(self):
        # A point is a name for the items/lines/payloadB/reserveMs group; separating them
        # in the table is what lets a mislabelled run hide.
        headers = [h for h, _, _ in self.core]
        self.assertEqual(headers.index("point") + 1, headers.index("items"))


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd .worktrees/main && python3 -m unittest scripts.tests.test_compare -v`
Expected: 3 tests FAIL — no `point` column.

- [ ] **Step 3: Add the column**

In `k6/bench/compare.py`, in `COLUMNS["core"]`, insert immediately **before** the
`("items", "meta.config.distinctItems", "d"),` line:

```python
        ("point", "meta.point", "s"),
```

- [ ] **Step 4: Run the tests**

Run: `cd .worktrees/main && python3 -m unittest scripts.tests.test_compare -v`
Expected: 3 tests, `OK`

- [ ] **Step 5: Verify it renders against a real run directory**

Run:
```bash
cd .worktrees/main
python3 k6/bench/compare.py bench-results/ES-1_steady_20260806T202418Z
```
Expected: a table with a `point` column, empty for this pre-existing run (it has no
`meta.point`), and every other column populated as before.

- [ ] **Step 6: Run the whole suite**

Run: `cd .worktrees/main && python3 -m unittest discover -s scripts/tests -t .`
Expected: `Ran 56 tests` … `OK`

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(bench): show the workload point in the comparison table

Placed immediately before items/lines/payloadB/reserveMs, the knobs it
names — separating them is what would let a mislabelled run hide. Runs
predating meta.point render an empty cell."
```

---

### Task 8: Rewrite `run-suite.sh` to run `main`'s harness

**Files:**
- Modify: `scripts/run-suite.sh` (the `run_one` function and the preamble)
- Modify: `scripts/lib.sh` (`dc_for` and `teardown` point at `main`, not a worktree)

**Interfaces:**
- Consumes: `snapshot_shell_knobs` / `resolve_point` (Task 5), `common.sh`'s `VARIANT` contract (Task 4), the unified compose file (Task 3).
- Produces: the user-facing entry point. Task 10 documents it.

`build-images.sh` is untouched and keeps using worktrees.

- [ ] **Step 1: Point `dc_for` and `teardown` at `main`**

In `scripts/lib.sh`, replace `dc_for()` and `teardown()` with:

```bash
# Compose invocation for the ONE stack main owns. Previously this took a worktree path,
# because each branch had its own compose file; there is now a single file.
dc_main() {
    docker compose -f "$MAIN_ROOT/docker-compose.yml" \
                   -f "$MAIN_ROOT/docker-compose.bench.yml" "$@"
}

# Full stop between variants. -v is required, not tidiness: reset.sh only truncates
# tables, so without it a TO run would inherit the previous ES run's postgres volume under
# a schema that does not match. --remove-orphans clears anything left by an older layout
# whose service names differed.
teardown() {
    dc_main down -v --remove-orphans --timeout 30 >/dev/null 2>&1 || true
}
```

- [ ] **Step 2: Replace `run_one` in `scripts/run-suite.sh`**

```bash
run_one() {
    local variant="$1" rc=0 run_dir

    # Clean slate before this variant starts, not after the previous one finished — so an
    # aborted earlier suite, or a stack left running by hand, is cleared too.
    teardown

    if [ "$NO_BUILD" = "0" ] && ! image_is_current "$variant"; then
        log "$variant: image missing or behind branch head — building"
        "$HERE/build-images.sh" --only "$variant"
    fi

    log "=== $variant: $SCENARIO ${POINT_RESOLVED:+($POINT_RESOLVED)} ==="

    # main's own harness, against this variant's image. No worktree, no branch switch:
    # the image is the only thing that differs between variants.
    (
        cd "$MAIN_ROOT"
        VARIANT="$variant" \
        VARIANT_FAMILY="$(family_of "$variant")" \
        IMAGE_TAG="$(image_tag "$variant")" \
        SKIP_BUILD=1 \
        ./k6/bench/bench.sh
    ) || rc=$?

    case "$rc" in
        0) VERDICT[$variant]="PASS" ;;
        1) VERDICT[$variant]="FAIL" ;;
        2) VERDICT[$variant]="INVALID" ;;
        *) VERDICT[$variant]="ERROR($rc)" ;;
    esac

    # Newest run directory for this variant. The label sits between scenario and
    # timestamp, so the glob must tolerate it.
    run_dir="$(ls -td "$RESULTS_DIR/${variant}_${SCENARIO}"*  2>/dev/null | head -1 || true)"
    RUNDIR[$variant]="${run_dir:-(none)}"

    # BEFORE teardown: `down -v` destroys prometheus-data and with it every raw series.
    # What survives unaided is dump.json's ~20 extracted series, against the merged
    # dashboard's 56 panels. Non-fatal throughout — the run's own artifacts are already
    # written and valid.
    if [ "$SNAPSHOT_TSDB" = "1" ] && [ -n "$run_dir" ]; then
        local snap_dir="bench-results/$(basename "$run_dir")/prom-snapshot"
        if ! ( cd "$MAIN_ROOT" && ./scripts/prom_snapshot.sh "$(basename "$run_dir")" >/dev/null ); then
            log "$variant: TSDB snapshot FAILED — run artifacts intact, but this run will"
            log "           only ever replay from dump.json (~20 of 56 panels)"
        else
            log "$variant: TSDB -> $snap_dir"
            if [ "$ARCHIVE_TSDB" = "1" ]; then
                ( cd "$MAIN_ROOT" && ./scripts/prom_archive.sh "$snap_dir" >/dev/null ) \
                    || log "$variant: replay-archive merge failed — the host snapshot is intact and can be merged later with: ./scripts/prom_archive.sh $snap_dir"
            fi
        fi
    fi

    log "$variant: ${VERDICT[$variant]}"
    [ "$rc" = "0" ] || [ "$CONTINUE" = "1" ] || return "$rc"
    return 0
}
```

- [ ] **Step 3: Simplify `image_is_current` to take only a variant**

It previously took a worktree path to read the branch head. It must still consult the
branch — that is the whole point of the freshness check — so it resolves the worktree
itself via the existing helper:

```bash
image_is_current() {
    local variant="$1" wt
    wt="$(worktree_path "$variant")"
    [ -d "$wt" ] || return 1          # never built: no worktree yet
    python3 - "$RESULTS_DIR/images.json" "$variant" "$(git -C "$wt" rev-parse HEAD)" \
             "$(image_tag "$variant")" <<'PY'
import json, subprocess, sys
manifest, variant, head, tag = sys.argv[1:5]
try:
    rec = json.load(open(manifest))["images"][variant]
except Exception:
    sys.exit(1)
if rec.get("commit") != head:
    sys.exit(1)
sys.exit(subprocess.call(["docker", "image", "inspect", tag],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
PY
}
```

- [ ] **Step 4: Wire point resolution into the preamble**

In `scripts/run-suite.sh`, immediately after `require_not_root` / `require_tools` /
`assert_ports_free` and **before** `workload.env` is sourced:

```bash
# Order matters. The shell snapshot must be taken before any point expands, because
# afterwards a point-set knob and a shell-set knob are indistinguishable. workload.env is
# sourced afterwards and uses the ${VAR:-value} form throughout, so it fills only what is
# still unset and can never silently contradict a named point.
snapshot_shell_knobs
resolve_point
```

Then delete the now-dead `LAST_WT` variable, the `if [ -n "$LAST_WT" ]; then teardown "$LAST_WT"; fi` lines, and the "branch has no harness" skip block (there is one harness and it is always present).

Replace the final teardown line with a bare `teardown`.

- [ ] **Step 5: Verify the script parses and the dead worktree plumbing is gone**

Run:
```bash
cd .worktrees/main
bash -n scripts/run-suite.sh && echo PARSE-OK
grep -n 'LAST_WT\|dc_for\|ensure_worktree' scripts/run-suite.sh && echo "STALE — remove" || echo "CLEAN"
```
Expected: `PARSE-OK` then `CLEAN`.

- [ ] **Step 6: Verify a conflicting point aborts before any container starts**

Run:
```bash
cd .worktrees/main
DISTINCT_ITEMS=8 POINT=W-base scripts/run-suite.sh --only ES-4 --no-build; echo "exit=$?"
```
Expected: non-zero exit and a message naming `DISTINCT_ITEMS`, with no container started
(`docker ps` shows nothing new).

- [ ] **Step 7: Run the whole suite**

Run: `cd .worktrees/main && python3 -m unittest discover -s scripts/tests -t .`
Expected: `Ran 56 tests` … `OK`

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(bench): run every variant from main's own harness

run-suite.sh no longer creates a worktree per variant or invokes that
branch's bench.sh. It exports VARIANT, VARIANT_FAMILY and IMAGE_TAG and
runs main's harness against the variant's image — which is now the only
thing that differs between variants.

build-images.sh still uses worktrees, because building is the one step
that genuinely needs branch content. That is the point: pay it once, then
never touch a branch again.

POINT is resolved in the preamble, before workload.env is sourced, so a
conflicting identity knob aborts before a single container starts."
```

---

### Task 9: `run-tests.sh`, and one `compare.py`

**Files:**
- Create: `scripts/run-tests.sh`
- Delete: `scripts/compare.py`

**Interfaces:**
- Consumes: `scripts/lib.sh`'s `die`, `log`, `require_not_root`, `MAIN_ROOT`.
- Produces: the test entry point. Task 10 documents it.

`scripts/compare.py` is byte-identical to `k6/bench/compare.py` (303 lines each). Keeping
both would recreate exactly the drift this change removes.

- [ ] **Step 1: Confirm the two files are still identical before deleting either**

Run:
```bash
cd .worktrees/main
diff scripts/compare.py k6/bench/compare.py && echo "IDENTICAL — safe to delete"
```
Expected: `IDENTICAL — safe to delete`

> If they differ, **stop**: Task 7 edited `k6/bench/compare.py`, so a difference here means
> the copy was edited too. Reconcile before continuing.

- [ ] **Step 2: Delete the duplicate**

```bash
cd .worktrees/main
git rm -q scripts/compare.py
```

- [ ] **Step 3: Write `scripts/run-tests.sh`**

```bash
#!/usr/bin/env bash
# Run the harness test suite.
#
#   scripts/run-tests.sh          # all of it
#   scripts/run-tests.sh -v       # verbose
#
# ONE suite, run ONCE — not once per variant. With a single harness on main there is a
# single set of tests, which is what "the same for all variants" means here. These cover
# bench.sh's label and point handling, evaluate.py's validity gates, the dashboard spec and
# build, and replay_run.py's OpenMetrics generation.
#
# Stdlib unittest only: no pytest, no conftest.py, no requirements.txt, no Docker and no
# JDK. `-t .` is required because the tests do `from scripts.dashboards import build`, so
# the repository root must be the top-level directory.
#
# Deliberately does NOT call require_tools, which demands a reachable Docker daemon. These
# tests are hermetic; requiring Docker to run them would be a lie. require_not_root stays,
# because a stray __pycache__ written as root breaks every later run.
#
# This does not run the variants' JVM tests. Those live in each branch's src/test and stay
# a per-branch `./gradlew test`.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib.sh
. "$HERE/lib.sh"

require_not_root
command -v python3 >/dev/null || die "required tool not on PATH: python3"

log "harness tests: python3 -m unittest discover -s scripts/tests -t ."
cd "$MAIN_ROOT"
exec python3 -m unittest discover -s scripts/tests -t . "$@"
```

- [ ] **Step 4: Make it executable**

```bash
cd .worktrees/main && chmod +x scripts/run-tests.sh
```

- [ ] **Step 5: Run it**

Run: `cd .worktrees/main && scripts/run-tests.sh`
Expected: `Ran 56 tests` … `OK`, exit 0.

- [ ] **Step 6: Verify it works from another directory and reports failure honestly**

Run:
```bash
cd /tmp && /home/wiktor/Projects/Magisterka/InventoryItemReservation/.worktrees/main/scripts/run-tests.sh >/dev/null && echo "exit=0 from /tmp"
```
Expected: `exit=0 from /tmp` — `MAIN_ROOT` is resolved from `BASH_SOURCE`, not the cwd.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(bench): scripts/run-tests.sh, and a single compare.py

One harness means one test suite, run once rather than once per variant.
Stdlib unittest only — no pytest, no Docker, no JDK — so it needs no
bootstrap. It skips require_tools deliberately: those tests are hermetic
and demanding a Docker daemon to run them would be a lie.

scripts/compare.py is deleted. It was byte-identical to
k6/bench/compare.py, and keeping both would recreate the drift this whole
change exists to remove."
```

---

### Task 10: Documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/bench-campaign-runbook.md`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Rewrite the README's premise and pieces table**

Delete the paragraph claiming "`main` holds no application code that anyone benchmarks. The
`src/` tree here is an early legacy prototype, kept only for history" — `src/` is gone.
Replace with a statement that `main` holds the only harness and no application code at all.

Replace the pieces table with:

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

- [ ] **Step 2: Correct every `compare.py` path**

Run:
```bash
cd .worktrees/main
grep -rn 'scripts/compare\.py' README.md docs/ scripts/
```
Replace each hit with `k6/bench/compare.py`. Check `run-suite.sh`'s closing hint too.

- [ ] **Step 3: Document the harness-ownership change**

Add a README section replacing the old "Each variant runs from its own git worktree, using
that branch's own harness" explanation:

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

- [ ] **Step 4: Add named points to the runbook**

In `docs/bench-campaign-runbook.md`, replace the per-block explicit knob assignments with
`POINT=`. For example a W-hot breakpoint block becomes:

```bash
SCENARIO=capacity POINT=W-hot scripts/run-suite.sh
```

Add a note under §2.1 (the bracketing rule):

> Staircase knobs (`STEP_START`, `STEP_INC`, `STEP_COUNT`) are *calibration*: `points.env`
> supplies defaults and the shell overrides them silently, exactly so re-bracketing works.
> The identity knobs cannot be overridden — a conflicting value aborts the run rather than
> producing a mislabelled result.

- [ ] **Step 5: Verify no stale claims survive**

Run:
```bash
cd .worktrees/main
grep -rniE "that branch's own harness|third copy|legacy prototype|scripts/compare\.py|bench\.env" README.md docs/bench-campaign-runbook.md \
  && echo "STALE — fix" || echo "CLEAN"
```
Expected: `CLEAN`

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "docs: main owns the harness

The README's premise was that main orchestrates other branches'
harnesses and holds a legacy prototype. Both are now false. Documents
one harness, one compare.py path, named workload points, and the fact
that the branches' surviving harness copies are unsupported."
```

---

### Task 11: End-to-end verification

**Files:** none — this task changes nothing. Its deliverable is evidence.

**Interfaces:**
- Consumes: everything.
- Produces: a PASS run proving the unified stack is the stack that produced the reference numbers.

- [ ] **Step 1: Build all eight images**

Run: `cd .worktrees/main && scripts/build-images.sh`
Expected: eight `inventory-reservation-<variant>:latest` images and a written
`bench-results/images.json`. This is the step that still uses worktrees.

- [ ] **Step 2: Run the harness tests**

Run: `cd .worktrees/main && scripts/run-tests.sh`
Expected: `Ran 56 tests` … `OK`

- [ ] **Step 3: Reproduce a known-good ES-4 run**

The reference from CLAUDE.md is `ES-2` at `REPLICAS=2`; the closest single-node analogue is
this. Run:

```bash
cd .worktrees/main
SCENARIO=steady RATE=30 DURATION=3m DISTINCT_ITEMS=6 ITEMS_PER_ORDER=4 \
  scripts/run-suite.sh --only ES-4 --no-build
```

Expected: verdict **PASS**, 9/9 validity, 7/7 SLO. Confirm in `verdict.json`.

- [ ] **Step 4: Confirm the metrics land in the same place as before**

Check the new run's `dump.json` against a prior `ES-4`/`ES-3-pesimistic` steady run at the
same knobs. Throughput and e2e p95/p99 must sit within run-to-run noise.

**A mismatch here means the unified stack is not the stack that produced the reference
numbers — stop and diagnose before running anything else.** The likeliest causes are the
`API_CONTAINER_RE` change (cadvisor series silently empty) and the `PROM_JOB` rename
(every `sum()` returning nothing). Both show as *missing* series rather than wrong ones, so
check `targets_scraped` and the resource columns specifically.

- [ ] **Step 5: Prove the TO admin-API fix**

Run:
```bash
cd .worktrees/main
SCENARIO=steady RATE=30 DURATION=3m scripts/run-suite.sh --only TO-1 --no-build
ls bench-results/TO-1_steady_*/prom-snapshot/ | head
```
Expected: a populated `prom-snapshot/` directory. This was impossible before — `TO-1`'s
compose file omitted `--web.enable-admin-api`, so `prom_snapshot.sh` got a 404.

- [ ] **Step 6: Prove named points end to end**

Run:
```bash
cd .worktrees/main
SCENARIO=steady RATE=30 DURATION=3m POINT=W-hot scripts/run-suite.sh --only ES-4 --no-build
python3 -c "
import glob, json
d = sorted(glob.glob('bench-results/ES-4_steady_W-hot_*'))[-1]
m = json.load(open(d + '/meta.json'))
print('point      :', m['point'])
print('run_label  :', m['run_label'])
print('items/lines:', m['config']['distinctItems'], m['config']['itemsPerOrder'])
assert m['point'] == 'W-hot'
assert (m['config']['distinctItems'], m['config']['itemsPerOrder']) == (8, 4)
print('OK')
"
```
Expected: `point: W-hot`, `items/lines: 8 4`, `OK`.

- [ ] **Step 7: Confirm `.env` is being read and the replica assertion fires**

Run:
```bash
cd .worktrees/main
grep -n 'REPLICAS\|PG_MAX' .env
python3 -c "
import glob, json
d = sorted(glob.glob('bench-results/ES-4_steady_*'))[-1]
print('expected_replicas:', json.load(open(d + '/meta.json'))['expected_replicas'])
"
```
Expected: `REPLICAS=1`, `PG_MAX_CONNECTIONS=600`, and `expected_replicas: 1`. If
`expected_replicas` is missing or wrong, `.env` is not being loaded from `MAIN_ROOT` and
`reset.sh`'s container-count assertion is checking a number nobody set — it aborts the run
otherwise, so a PASS in Step 3 is already partial evidence.

- [ ] **Step 8: Render a comparison table across old and new runs**

Run: `cd .worktrees/main && python3 k6/bench/compare.py bench-results/*_steady_*`
Expected: a table including the new runs, with `point` populated only for the `W-hot` run.

- [ ] **Step 9: Commit the verification note**

```bash
git add -A
git commit -m "test: verify the unified harness reproduces a known-good run

ES-4 steady PASS from main's harness, TO-1 producing a Prometheus TSDB
snapshot for the first time, and POINT=W-hot writing a run whose
meta.json point matches its knobs."
```

---

## Open item, deliberately not implemented

Campaign design §4.2 and §7 want a mis-bracketed staircase caught on **run 1 of 8**;
`run-suite.sh` runs all eight unattended, so the earliest it can surface is after the whole
block (~5 h at W-base). `main`'s runbook already absorbed this by changing §2.1 from "after
every run" to "after every block".

`points.env`'s identity/calibration split makes re-bracketing cheap and safe, which reduces
the cost but does not remove it. If it should be removed, the fix is a `--first-then-pause`
flag on `run-suite.sh` that runs one variant, prints the knee, and waits for confirmation.
**Not in this plan** — it needs a decision first.

## Self-review notes

- **Spec coverage:** deletions (T1), harness import including the `TO-3` superset and the
  `prom_restore.sh` gap (T2), family-neutral stack + admin API + `PG_MAX_CONNECTIONS`
  (T3), `bench.env` removal (T4), named points with the identity/calibration split and
  shell-snapshot precedence (T5), `meta.json` fields (T6), `compare.py` column (T7),
  `run-suite.sh` rewrite (T8), `run-tests.sh` + `compare.py` dedupe (T9), docs (T10),
  all six spec verification steps plus points (T11). The open bracketing item is carried
  forward as open, matching the spec.
- **Test-count arithmetic:** 35 baseline → +6 (T4) → +9 (T5) → +3 (T6) → +3 (T7) = 56.
- **Naming consistency:** `dc_for` → `dc_main` is renamed in T8 Step 1 and the stale name
  is grepped for in T8 Step 5. `snapshot_shell_knobs` / `resolve_point` / `POINT_RESOLVED`
  are defined in T5 and consumed with identical spelling in T6 and T8.
