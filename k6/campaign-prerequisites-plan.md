# Campaign Prerequisites Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the eight prerequisites in §9 of `k6/benchmark-campaign-plan.md` true, so Night 1 of the measurement campaign can start.

**Architecture:** Seven independent changes across two axes. Three are per-branch git work (fix TO histogram bounds, port the harness to ES-1/ES-3). Four are harness code on `ES-4` that must then be replicated to every branch carrying the harness, because `k6/` is byte-identical across branches by design. The final task is a live rehearsal that proves the whole chain works.

**Tech Stack:** Bash (harness orchestration), Python 3 stdlib only (analysis — no third-party imports, ever), Spring Boot YAML config, k6 JavaScript, Docker Compose, PostgreSQL, Prometheus.

## Global Constraints

- **`k6/` and `docker-compose.bench.yml` must stay byte-identical on every branch carrying the harness.** `bench.env` is the only per-branch file. Acceptance: `git diff --stat ES-2 <branch> -- k6 docker-compose.bench.yml` is empty.
- **Python 3 standard library only** in `dump.py`, `evaluate.py`, `compare.py`. No `requests`, no `pandas`, no `yaml`.
- **Never run the harness under sudo.** It makes `bench-results/` root-owned and breaks every later run.
- **Never `git push`.** Commit locally only; the user pushes.
- **`JAVA_HOME` in the environment points at a missing JDK.** Use `JAVA_HOME=$HOME/.jdks/corretto-21.0.10` for any gradle invocation. Use `$HOME/...`, never `~/...` — tilde is not expanded in that position.
- **The shell is fish.** `git show BRANCH:path/to/file` mangles under fish quoting. Use `git -C <repo> show 'BRANCH:path'` from a Python `subprocess` call, or `git show BRANCH -- path`, when scripting across branches.
- **`ALLOW_DIRTY=1` must never be set** for any run whose output enters the thesis.
- Branch names: `TO-1`..`TO-4`, `ES-1`..`ES-4`. `main` has no `k6/`.
- The current branch is `ES-4`. `bench-results/` is untracked; `k6/load-tests-plan.md` is deleted in the working tree. Neither is this plan's business except where Task 2 says so.

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `src/main/resources/application.yaml` (TO-1..4) | Micrometer histogram bounds for `order.e2e.time` | 1 |
| `.gitignore` | Stop `bench-results/` being untracked-and-unignored | 2 |
| `k6/bench/bench.sh` | `IMAGE_FRESH` derivation; `ALLOW_DIRTY` refusal | 3 |
| `k6/bench/queries.promql` | New `container_cpu_seconds` delta query | 4 |
| `k6/bench/dump.py` | `cpu_seconds_per_order` in `derive()` | 4 |
| `k6/bench/compare.py` | `CPUs/order` column; `--aggregate` mode | 4, 6 |
| `bench.env` (ES-1, ES-3) | Per-branch harness identity | 5 |
| `k6/bench/batch.sh` | Night runner: cell list → sequential runs → manifest | 7 |
| `k6/bench/cells/*.txt` | Cell lists, one per campaign night | 7 |

Tasks 3, 4, 6 and 7 all touch `k6/`, which is replicated across branches. **Task 8 is the single replication point** — do all harness work on `ES-4` first, then replicate once. Replicating after each task would mean six cross-branch syncs instead of one.

---

## Task 1: Fix `order.e2e.time` histogram bounds on TO-1..TO-4

Blocking, and independent of everything else. Without it every TO-vs-ES latency comparison is a bucketing artefact once TO saturates: `order.e2e.time` is enabled as a histogram on all four TO branches but `maximum-expected-value` is declared for `publish.lag` only, so it inherits Micrometer's 30 s Timer default and every sample above 30 s collapses into `+Inf`.

**Files:**
- Modify: `src/main/resources/application.yaml` on each of `TO-1`, `TO-2`, `TO-3`, `TO-4` (the `management.metrics.distribution` block, around lines 33-44)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing consumed by later tasks in code. Task 9's rehearsal depends on TO-3 carrying this fix.

- [ ] **Step 1: Write the audit script that will verify the fix**

This script is the test. Save it as `k6/bench/audit-parity.py` — it stays in the repo, because the parity property has to be re-checkable before every campaign night, not just today.

```python
#!/usr/bin/env python3
"""Assert cross-branch metric parity. Exit 1 if any branch would produce
incomparable measurements.

    python3 k6/bench/audit-parity.py

order.e2e.time histogram bounds must be identical on every branch: Micrometer's
default Timer maximum is 30s, so a branch without explicit bounds collapses every
sample above 30s into +Inf and histogram_quantile reports ~30s -- making a
saturated variant look FASTER than a healthy one.

Python 3 stdlib only, and it shells out to git rather than checking branches out,
so it is safe to run with a dirty working tree.
"""
import re
import subprocess
import sys

BRANCHES = ["TO-1", "TO-2", "TO-3", "TO-4", "ES-1", "ES-2", "ES-3", "ES-4"]
YAML_PATH = "src/main/resources/application.yaml"
REQUIRED = {"minimum-expected-value": "1ms", "maximum-expected-value": "10m"}


def show(branch, path):
    """Read a file from a branch without checking it out.

    Uses subprocess with an argument list rather than a shell string: the
    repository's default shell is fish, which mangles `BRANCH:path` arguments.
    """
    proc = subprocess.run(
        ["git", "show", f"{branch}:{path}"],
        capture_output=True, text=True,
    )
    return proc.stdout if proc.returncode == 0 else ""


def yaml_block(text, key):
    """Return the mapping nested directly under `key:` as a flat dict.

    A real YAML parser would be better, but PyYAML is not stdlib and the harness
    must not acquire dependencies. This handles the one shape we need: a key on
    its own line followed by more-indented `name: value` pairs.
    """
    match = re.search(r"^([ ]*)" + re.escape(key) + r":[ ]*$", text, re.M)
    if not match:
        return {}
    indent = len(match.group(1))
    out = {}
    for line in text[match.end():].split("\n"):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if len(line) - len(line.lstrip()) <= indent:
            break
        name, _, value = line.strip().partition(":")
        out[name] = value.strip()
    return out


def main():
    failures = []
    rows = []
    for branch in BRANCHES:
        text = show(branch, YAML_PATH)
        if not text:
            failures.append(f"{branch}: {YAML_PATH} not found")
            continue
        found = {}
        for key, want in REQUIRED.items():
            got = yaml_block(text, key).get("order.e2e.time", "MISSING")
            found[key] = got
            if got != want:
                failures.append(
                    f"{branch}: {key}.order.e2e.time is {got!r}, must be {want!r}"
                )
        hist = yaml_block(text, "percentiles-histogram").get("order.e2e.time", "MISSING")
        if hist != "true":
            failures.append(f"{branch}: percentiles-histogram.order.e2e.time is {hist!r}")
        rows.append((branch, hist, found["minimum-expected-value"],
                     found["maximum-expected-value"]))

    print(f"{'branch':8} {'histogram':10} {'min':9} {'max':9}")
    for branch, hist, mn, mx in rows:
        print(f"{branch:8} {hist:10} {mn:9} {mx:9}")

    if failures:
        print("\nPARITY FAILURES:", file=sys.stderr)
        for failure in failures:
            print(f"  x {failure}", file=sys.stderr)
        return 1
    print("\nparity OK: order.e2e.time bounds identical on all branches")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: Run it to confirm it fails on all four TO branches**

```bash
python3 k6/bench/audit-parity.py; echo "exit=$?"
```

Expected: `exit=1`, with eight failure lines — `minimum-expected-value` and `maximum-expected-value` both MISSING on each of TO-1, TO-2, TO-3, TO-4. ES-1..ES-4 print `1ms` / `10m` and produce no failures.

- [ ] **Step 3: Commit the audit script on ES-4**

```bash
git add k6/bench/audit-parity.py
git commit -m "Add cross-branch metric parity audit

order.e2e.time bounds must be identical on every branch or histogram_quantile
reports ~30s for any saturated variant, making it look faster than a healthy one."
```

- [ ] **Step 4: Apply the fix to TO-1**

```bash
git checkout TO-1
```

In `src/main/resources/application.yaml`, replace this block:

```yaml
      maximum-expected-value:
        publish.lag: 10m
```

with:

```yaml
      # order.e2e.time bounds are pinned explicitly and must stay identical on every
      # variant branch. Micrometer's default Timer max is 30s; without these, every
      # sample above 30s collapses into +Inf and histogram_quantile reports ~30s --
      # making a saturated variant look FASTER than a healthy one, which silently
      # invalidated a full round of TO-vs-ES latency comparisons.
      minimum-expected-value:
        order.e2e.time: 1ms
      maximum-expected-value:
        publish.lag: 10m
        order.e2e.time: 10m
```

Indentation is six spaces for the keys and eight for their entries — it sits under `management.metrics.distribution`, alongside the existing `percentiles-histogram` block. Do not reformat anything else in the file.

- [ ] **Step 5: Verify the YAML still parses and the app still boots**

```bash
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 ./gradlew --quiet compileKotlin
```

Expected: BUILD SUCCESSFUL. A malformed `distribution` block fails Spring's binding at startup rather than at compile time, so also confirm the block's shape:

```bash
python3 -c "
import re
t = open('src/main/resources/application.yaml').read()
i = t.index('distribution:')
print(t[i:i+900])
"
```

Expected: `minimum-expected-value:` containing `order.e2e.time: 1ms`, and `maximum-expected-value:` containing both `publish.lag: 10m` and `order.e2e.time: 10m`.

- [ ] **Step 6: Commit on TO-1**

```bash
git add src/main/resources/application.yaml
git commit -m "Pin order.e2e.time histogram bounds

Micrometer's default Timer maximum is 30s. order.e2e.time was enabled as a
histogram here but only publish.lag had explicit bounds, so every sample above
30s collapsed into +Inf and histogram_quantile reported ~30s -- making a
saturated TO run look faster than a healthy ES one.

Bounds now match the ES branches exactly: 1ms to 10m."
```

- [ ] **Step 7: Apply the identical fix to TO-2, TO-3 and TO-4**

The `maximum-expected-value: publish.lag: 10m` block is byte-identical on all four TO
branches, so this is the same edit three more times. For each branch:

```bash
git checkout TO-2    # then TO-3, then TO-4
```

In `src/main/resources/application.yaml`, replace:

```yaml
      maximum-expected-value:
        publish.lag: 10m
```

with:

```yaml
      # order.e2e.time bounds are pinned explicitly and must stay identical on every
      # variant branch. Micrometer's default Timer max is 30s; without these, every
      # sample above 30s collapses into +Inf and histogram_quantile reports ~30s --
      # making a saturated variant look FASTER than a healthy one, which silently
      # invalidated a full round of TO-vs-ES latency comparisons.
      minimum-expected-value:
        order.e2e.time: 1ms
      maximum-expected-value:
        publish.lag: 10m
        order.e2e.time: 10m
```

Then verify and commit on each:

```bash
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 ./gradlew --quiet compileKotlin
python3 -c "
import re
t = open('src/main/resources/application.yaml').read()
i = t.index('distribution:')
print(t[i:i+900])
"
git add src/main/resources/application.yaml
git commit -m "Pin order.e2e.time histogram bounds

Micrometer's default Timer maximum is 30s. order.e2e.time was enabled as a
histogram here but only publish.lag had explicit bounds, so every sample above
30s collapsed into +Inf and histogram_quantile reported ~30s -- making a
saturated TO run look faster than a healthy ES one.

Bounds now match the ES branches exactly: 1ms to 10m."
```

- [ ] **Step 8: Verify parity across all eight branches**

```bash
git checkout ES-4
python3 k6/bench/audit-parity.py; echo "exit=$?"
```

Expected: `exit=0`, all eight rows showing `true` / `1ms` / `10m`, and `parity OK`.

---

## Task 2: Track benchmark results

`bench-results/` is currently untracked *and* absent from `.gitignore` — so every run's artifacts sit in an ambiguous state, and no thesis table can cite a run ID that resolves to anything durable.

**Files:**
- Modify: `.gitignore`
- Create: `bench-results/.gitkeep`

**Interfaces:**
- Consumes: nothing.
- Produces: a tracked `bench-results/` that Task 7's manifest is committed into.

- [ ] **Step 1: Decide what is tracked and confirm the size is sane**

Track the JSON artifacts (`meta.json`, `dump.json`, `verdict.json`, `summary.json`, `profile.json`) and ignore the bulky derivatives (`report.pdf`, `k6.log`). Check what that costs:

```bash
du -sh bench-results
find bench-results -name '*.json' -exec du -ch {} + | tail -1
find bench-results \( -name '*.pdf' -o -name '*.log' \) -exec du -ch {} + | tail -1
```

Expected: the JSON total is a few MB and the PDF/log total dominates. If the JSON total exceeds ~200 MB, stop and reconsider — 361 runs would then be unreasonable to track.

- [ ] **Step 2: Add the rules to `.gitignore`**

Append:

```gitignore
### benchmark artifacts ###
# Run artifacts are TRACKED: every thesis table cites a run ID that must resolve to a
# committed dump.json. Only the bulky derivatives are ignored -- report.pdf re-renders
# from Grafana and k6.log is superseded by summary.json.
bench-results/**/report.pdf
bench-results/**/k6.log
bench-results/**/*/k6.log
```

- [ ] **Step 3: Verify only the intended files would be added**

```bash
git add -An bench-results | head -30
git add -An bench-results | wc -l
git add -An bench-results | grep -cE "\.(pdf|log)'?$"
```

`git add --dry-run` prints `add 'path'` — with a trailing quote — so the pattern must allow
for it. A bare `\.(pdf|log)$` matches nothing regardless of whether the ignore rules work,
and would report success either way.

Expected: the listing shows `.json` files; the final count of `.pdf`/`.log` entries is **0**.
If it is non-zero, the `.gitignore` patterns did not take — check for an earlier rule
un-ignoring them.

- [ ] **Step 4: Commit the existing results**

```bash
git add .gitignore bench-results
git commit -m "Track benchmark run artifacts

Every thesis table cites a run ID; that ID has to resolve to a committed
dump.json or the number is unreproducible. PDFs and k6 logs stay ignored --
the PDF re-renders from Grafana and summary.json supersedes the log."
```

- [ ] **Step 5: Confirm the working tree is clean under `src/`**

```bash
git status --porcelain -- src/ | wc -l
```

Expected: `0`. This is the condition `bench.sh:69` enforces; anything else blocks every run.

---

## Task 3: Fix the `image_fresh` false negative and refuse inherited `ALLOW_DIRTY`

Two guards in `bench.sh`, both currently wrong in ways that cost runs.

`IMAGE_FRESH` (line 94) compares the image's `Created` timestamp against `HEAD`'s commit time. Docker's layer cache preserves the *original* `Created` timestamp when every layer hits cache, so a correctly-rebuilt image reports as stale and `evaluate.py` returns `INVALID`. That cost 3 runs.

Separately, `ALLOW_DIRTY=1` is what produced all 7 `git_clean` failures. `bench.sh` should refuse to honour it unless it is set deliberately for this invocation.

**Files:**
- Modify: `k6/bench/bench.sh:64-94`

**Interfaces:**
- Consumes: nothing.
- Produces: `meta.image_built_after_head` (bool, unchanged name and type — `evaluate.py:127` reads it), now derived from jar content rather than timestamps. `BENCH_ALLOW_DIRTY` env var, read by `batch.sh` in Task 7.

- [ ] **Step 1: Reproduce the false negative**

```bash
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 ./gradlew --quiet bootJar
docker build -q -t inventory-reservation-es:latest .
docker build -q -t inventory-reservation-es:latest .   # second build: all layers cached
docker image inspect inventory-reservation-es:latest --format '{{.Created}}'
git log -1 --format=%cI
```

Expected: the image `Created` timestamp is *older* than the HEAD commit time even though the image was just rebuilt — which is exactly what line 94 misreads as stale.

- [ ] **Step 2: Replace the timestamp comparison with a jar-content comparison**

In `k6/bench/bench.sh`, replace lines 90-94:

```bash
IMAGE_ID="$(docker image inspect "$IMAGE_TAG" --format '{{.Id}}' 2>/dev/null || echo unknown)"
IMAGE_CREATED="$(docker image inspect "$IMAGE_TAG" --format '{{.Created}}' 2>/dev/null || echo unknown)"
HEAD_EPOCH="$(git -C "$REPO_ROOT" log -1 --format=%ct)"
IMAGE_EPOCH="$(date -d "$IMAGE_CREATED" +%s 2>/dev/null || echo 0)"
IMAGE_FRESH=$([ "$IMAGE_EPOCH" -ge "$HEAD_EPOCH" ] && echo true || echo false)
```

with:

```bash
IMAGE_ID="$(docker image inspect "$IMAGE_TAG" --format '{{.Id}}' 2>/dev/null || echo unknown)"
IMAGE_CREATED="$(docker image inspect "$IMAGE_TAG" --format '{{.Created}}' 2>/dev/null || echo unknown)"

# Freshness by CONTENT, not by timestamp. Docker's layer cache preserves the original
# `Created` timestamp when every layer hits cache, so a correctly-rebuilt image reports
# as older than HEAD and evaluate.py fails the run INVALID -- which cost three otherwise
# good runs. Compare the jar the image actually carries against the jar just built from
# the working tree instead: that is the property the check is really about.
HOST_JAR="$REPO_ROOT/build/libs/app.jar"
if [ "${SKIP_BUILD:-0}" = "1" ]; then
    # Nothing was built this invocation, so there is nothing to compare against.
    IMAGE_FRESH=false
    log "image: freshness UNKNOWN (SKIP_BUILD=1) -> reporting false"
elif [ ! -f "$HOST_JAR" ]; then
    IMAGE_FRESH=false
    log "image: $HOST_JAR missing -> cannot verify freshness"
else
    HOST_JAR_SHA="$(sha256sum "$HOST_JAR" | cut -d' ' -f1)"
    IMAGE_JAR_SHA="$(docker run --rm --entrypoint sha256sum "$IMAGE_TAG" /app/app.jar 2>/dev/null | cut -d' ' -f1 || echo unknown)"
    if [ "$HOST_JAR_SHA" = "$IMAGE_JAR_SHA" ]; then
        IMAGE_FRESH=true
    else
        IMAGE_FRESH=false
        log "image: jar MISMATCH host=${HOST_JAR_SHA:0:12} image=${IMAGE_JAR_SHA:0:12}"
    fi
fi
```

- [ ] **Step 3: Confirm the jar path inside the image**

The comparison above assumes the jar lives at `/app/app.jar`. Verify against the actual Dockerfile:

```bash
grep -nE 'COPY|ENTRYPOINT|WORKDIR|\.jar' Dockerfile
docker run --rm --entrypoint ls inventory-reservation-es:latest -la /app
```

If the path differs, correct `/app/app.jar` in Step 2 to match. Do not guess — a wrong path makes `IMAGE_JAR_SHA` permanently `unknown` and pins `IMAGE_FRESH=false`, turning the fix into the same bug with a new cause.

- [ ] **Step 4: Add the `ALLOW_DIRTY` refusal**

Replace lines 69-71:

```bash
if [ "$GIT_DIRTY" != "0" ] && [ "${ALLOW_DIRTY:-0}" != "1" ]; then
    die "src/ has $GIT_DIRTY uncommitted change(s); results would not be reproducible. Commit, or set ALLOW_DIRTY=1."
fi
```

with:

```bash
# ALLOW_DIRTY exists for harness debugging. It produced all seven git_clean INVALID
# verdicts in the pre-campaign runs, because an exported ALLOW_DIRTY=1 silently
# survived from one shell session into every run made from it. Requiring the
# confirmation variable makes the override deliberate per batch rather than sticky.
if [ "${ALLOW_DIRTY:-0}" = "1" ] && [ "${I_KNOW_THIS_RUN_IS_NOT_CITABLE:-0}" != "1" ]; then
    die "ALLOW_DIRTY=1 is set. A dirty run is not reproducible and must never enter the thesis. If this is deliberate harness debugging, also set I_KNOW_THIS_RUN_IS_NOT_CITABLE=1."
fi

if [ "$GIT_DIRTY" != "0" ] && [ "${ALLOW_DIRTY:-0}" != "1" ]; then
    die "src/ has $GIT_DIRTY uncommitted change(s); results would not be reproducible. Commit, or set ALLOW_DIRTY=1 (which also requires I_KNOW_THIS_RUN_IS_NOT_CITABLE=1)."
fi
```

- [ ] **Step 5: Verify both guards**

```bash
# Clean tree, freshly built image -> should reach the stack-up stage
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 ./gradlew --quiet bootJar
docker build -q -t inventory-reservation-es:latest . >/dev/null
bash -n k6/bench/bench.sh && echo "syntax OK"

# The ALLOW_DIRTY refusal fires
ALLOW_DIRTY=1 SKIP_BUILD=1 bash k6/bench/bench.sh 2>&1 | head -3
```

Expected: `syntax OK`, and the second command dies with `ALLOW_DIRTY=1 is set...` before doing any work.

- [ ] **Step 6: Commit**

```bash
git add k6/bench/bench.sh
git commit -m "Derive image freshness from jar content, and make ALLOW_DIRTY deliberate

Docker's layer cache preserves the original Created timestamp, so a correctly
rebuilt image reported as stale and evaluate.py failed the run INVALID -- three
runs lost to this. Compare the jar the image carries against the jar just built
instead.

ALLOW_DIRTY=1 produced all seven git_clean INVALID verdicts: exported once, it
silently survived into every later run from that shell. It now needs a second
confirmation variable per invocation."
```

---

## Task 4: Add CPU-seconds per order

The campaign's resource-cost claim needs CPU-seconds per order. `db_bytes_per_order` already exists (`dump.py:235`, rendered as `B/order` by `compare.py:57`); this is the missing half.

The existing `container_cpu` scalar cannot supply it. It is `sum(rate(container_cpu_usage_seconds_total{name=~"$CRE"}[1m]))` evaluated at the window's *end instant* — a momentary rate, so dividing it by `achieved_rps` charges the whole run at whatever the final minute happened to cost. A `delta`-kind query over the raw counter gives exact CPU-seconds across the window.

**Files:**
- Modify: `k6/bench/queries.promql` (delta section)
- Modify: `k6/bench/dump.py:197-243` (`derive()`)
- Modify: `k6/bench/compare.py:48-58` (`resource` column group)

**Interfaces:**
- Consumes: `dump.derived.orders_accepted` (float), already produced by `derive()`.
- Produces: `dump.scalars.container_cpu_seconds` (float, CPU-seconds over the window) and `dump.derived.cpu_seconds_per_order` (float or `None` when no orders were accepted). Task 6's aggregation reads the latter by path string.

- [ ] **Step 1: Confirm the counter exists and is scraped**

```bash
curl -sG http://localhost:9090/api/v1/query \
  --data-urlencode 'query=sum(container_cpu_usage_seconds_total{name=~".*api-es.*"})' \
  | python3 -m json.tool
```

Expected: `status: success` with one result and a monotonically increasing value. If `result` is empty, cadvisor is not running or `API_CONTAINER_RE` does not match — fix that first, because the new query would silently produce `None` on every run.

- [ ] **Step 2: Add the delta query**

In `k6/bench/queries.promql`, find the delta section (the `kind` column reads `delta`) and add:

```
# Exact CPU-seconds consumed across the window. Distinct from the container_cpu scalar
# below, which is an INSTANTANEOUS 1m rate sampled at the window's end -- fine as a
# "how hot was it at the end" gauge, useless as a per-order cost, because it charges
# the whole run at whatever the last minute happened to cost.
container_cpu_seconds	delta	sum(container_cpu_usage_seconds_total{name=~"$CRE"})
```

Fields are **tab**-separated (`parse_queries` splits on `\t` at `dump.py:125`). Spaces will not work.

- [ ] **Step 3: Verify the query file still parses**

```bash
python3 -c "
import sys; sys.path.insert(0, 'k6/bench')
from dump import parse_queries
entries = parse_queries('k6/bench/queries.promql')
names = [n for n, _, _ in entries]
assert 'container_cpu_seconds' in names, 'query not parsed'
kind = next(k for n, k, _ in entries if n == 'container_cpu_seconds')
assert kind == 'delta', f'kind is {kind!r}, expected delta'
print(f'parsed {len(entries)} queries; container_cpu_seconds kind={kind}')
"
```

Expected: no assertion error, and a count one higher than before the edit.

- [ ] **Step 4: Write the failing test for the derivation**

Create `k6/bench/test_derive.py`:

```python
#!/usr/bin/env python3
"""Tests for dump.derive(). Run: python3 k6/bench/test_derive.py

No pytest: the harness is python3-stdlib-only by design, and adding a test
dependency that the measurement machine might not carry is exactly the kind of
drift the harness is built to avoid.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dump import derive  # noqa: E402


class TestCpuSecondsPerOrder(unittest.TestCase):
    def test_divides_cpu_seconds_by_accepted_orders(self):
        scalars = {"orders_accepted": 1000.0, "container_cpu_seconds": 250.0}
        out = derive(scalars, [0, 100], {})
        self.assertAlmostEqual(out["cpu_seconds_per_order"], 0.25)

    def test_none_when_no_orders_accepted(self):
        scalars = {"orders_accepted": 0.0, "container_cpu_seconds": 250.0}
        out = derive(scalars, [0, 100], {})
        self.assertIsNone(out["cpu_seconds_per_order"])

    def test_none_when_counter_absent(self):
        """A TO branch with no cadvisor match must not crash the dump."""
        scalars = {"orders_accepted": 1000.0}
        out = derive(scalars, [0, 100], {})
        self.assertIsNone(out["cpu_seconds_per_order"])

    def test_db_bytes_per_order_still_works(self):
        """Regression guard: the metric that already existed."""
        scalars = {"orders_accepted": 100.0,
                   "db_size_start": 1000.0, "db_size_end": 6000.0}
        out = derive(scalars, [0, 100], {})
        self.assertAlmostEqual(out["db_bytes_per_order"], 50.0)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 5: Run it to verify it fails**

```bash
python3 k6/bench/test_derive.py -v
```

Expected: 3 failures with `KeyError: 'cpu_seconds_per_order'`, and `test_db_bytes_per_order_still_works` passing.

- [ ] **Step 6: Implement the derivation**

In `k6/bench/dump.py`, inside `derive()`, add after the `db_growth` line (currently line 216):

```python
    cpu_seconds = scalars.get("container_cpu_seconds")
```

and add to the `derived` dict, next to `db_bytes_per_order`:

```python
        # CPU-seconds per order, from the delta of container_cpu_usage_seconds_total
        # across the window -- NOT from the container_cpu scalar, which is an
        # instantaneous 1m rate at the window's end. Paired with db_bytes_per_order,
        # this is the resource-cost half of the TO-vs-ES comparison: what each
        # architecture spends in CPU and in storage to retire one order.
        "cpu_seconds_per_order": (
            round(num(cpu_seconds) / accepted, 6)
            if accepted and isinstance(cpu_seconds, (int, float)) else None
        ),
```

- [ ] **Step 7: Run the tests to verify they pass**

```bash
python3 k6/bench/test_derive.py -v
```

Expected: 4 tests, all OK.

- [ ] **Step 8: Add the column to `compare.py`**

In the `"resource"` group of `COLUMNS`, after the `("B/order", ...)` entry:

```python
        ("CPUs/order", "dump.derived.cpu_seconds_per_order", ".4f"),
```

- [ ] **Step 9: Verify the column renders against a real run**

```bash
python3 k6/bench/compare.py --cols resource bench-results/ES-2_steady_20260804T083139Z
```

Expected: the table includes a `CPUs/order` column. It shows `-` for this run — the run predates the query, so its `dump.json` has no `container_cpu_seconds`. That `-` is the correct behaviour and confirms the missing-data path works.

- [ ] **Step 10: Commit**

```bash
git add k6/bench/queries.promql k6/bench/dump.py k6/bench/compare.py k6/bench/test_derive.py
git commit -m "Add CPU-seconds per order

Pairs with the existing db_bytes_per_order to give the resource-cost half of the
TO-vs-ES comparison: what each architecture spends in CPU and storage per order.

Needs a new delta query rather than reusing the container_cpu scalar -- that one
is an instantaneous 1m rate sampled at the window's end, so dividing it by
achieved_rps would charge the whole run at whatever the last minute cost."
```

---

## Task 5: Port the harness to ES-1 and ES-3

Both branches lack `k6/bench/`, `bench.env` and `docker-compose.bench.yml`; `common.sh` hard-fails there. Without the port, ES-1-vs-ES-2 (the snapshot hypothesis) and ES-3-vs-ES-4 (the lock hypothesis) are untestable.

Their `application.yaml` is already correct — the Task 1 audit confirmed `1ms`/`10m`, `total-segments: 60` and `SQLStateResolver` on all four ES branches. This task is harness files only.

**Files:**
- Create on `ES-1` and `ES-3`: `k6/` (all of it), `docker-compose.bench.yml`, `bench.env`

**Interfaces:**
- Consumes: the `k6/` tree as it exists on `ES-4` *after* Tasks 3, 4, 6 and 7 — so this task runs after them. See Task 8.
- Produces: two more branches on which `bench.sh` runs.

- [ ] **Step 1: Confirm exactly what is missing**

```bash
python3 - <<'PY'
import subprocess
def tree(branch):
    out = subprocess.run(["git", "ls-tree", "-r", "--name-only", branch],
                         capture_output=True, text=True).stdout.split("\n")
    return {p for p in out if p.startswith(("k6/", "docker-compose", "bench.env"))}
ref = tree("ES-4")
for b in ("ES-1", "ES-3"):
    missing = sorted(ref - tree(b))
    print(f"{b}: {len(missing)} missing")
    for m in missing:
        print("   ", m)
PY
```

Expected: both branches missing the same ~18 paths — `bench.env`, `docker-compose.bench.yml`, everything under `k6/bench/` and `k6/lib/`, plus `k6/main.js` and `k6/README.md`.

- [ ] **Step 2: Copy the harness onto ES-1**

```bash
git checkout ES-1
git checkout ES-4 -- k6 docker-compose.bench.yml
```

This stages the whole tree from ES-4. Do not copy `bench.env` — it is the one per-branch file and is written by hand in the next step.

- [ ] **Step 3: Write `bench.env` for ES-1**

Create `bench.env` at the repo root:

```bash
# The ONLY per-branch file in the benchmark harness.
# Everything under k6/ and docker-compose.bench.yml must stay byte-identical on every
# branch carrying the harness. The acceptance test, using ES-2 as the reference:
#     git diff --stat ES-2 <branch> -- k6 docker-compose.bench.yml     # must be empty
#
# Branch: ES-1  (Axon 4.11.2, JDBC event store on PostgreSQL, full replay --
# no snapshots and no aggregate cache. The naive baseline that makes every other
# ES number interpretable.)

VARIANT=ES-1
VARIANT_FAMILY=ES

# docker compose service names (differ between the TO and ES families)
API_SVC=api-es
DB_SVC=postgres-es

# Prometheus job label for this branch's API target, and the cadvisor container-name
# regex. The regex must stay UNANCHORED: the API service is scaled with deploy.replicas
# and so carries no container_name, which means cadvisor sees `<project>-api-es-1`, `-2`,
# ... rather than a bare `api-es`. queries.promql matches it with an anchored name=~"$CRE".
#
# EXPECTED_REPLICAS is NOT set here. common.sh derives it from REPLICAS in .env, which is
# the file docker compose actually acts on -- one knob, not two.
PROM_JOB=inventory-es
API_CONTAINER_RE=.*api-es.*

DB_NAME=inventory
DB_USER=inventory

# Health endpoint on the published port, which on every branch is nginx in front of the
# api replicas. At REPLICAS>1 a healthy response only proves that ONE replica answered --
# reset.sh asserts the full container count separately.
HEALTH_URL=http://localhost:8080/actuator/health

# Image bench.sh rebuilds and tags before each run.
IMAGE_TAG=inventory-reservation-es:latest
```

- [ ] **Step 4: Verify the service and job names against this branch's own compose file**

`bench.env` above assumes `api-es` / `postgres-es` / `inventory-es`. Confirm rather than trust:

```bash
grep -nE '^\s{2}[a-z-]+:' docker-compose.yml | head -20
grep -n "job_name" prometheus/prometheus.yml 2>/dev/null || \
  grep -rn "job_name" --include=*.yml . | head
```

Expected: services named `api-es` and `postgres-es`, and a Prometheus `job_name: inventory-es`. If any differ, correct `bench.env` — a wrong `PROM_JOB` makes every query return empty and the run reports zeros rather than failing.

- [ ] **Step 5: Verify byte-identity and that `common.sh` loads**

```bash
git add -A
git diff --cached --stat ES-4 -- k6 docker-compose.bench.yml
bash -c '. k6/bench/common.sh && echo "VARIANT=$VARIANT PROM_JOB=$PROM_JOB REPLICAS=$EXPECTED_REPLICAS"'
```

Expected: the diff is **empty**, and `common.sh` prints `VARIANT=ES-1 PROM_JOB=inventory-es REPLICAS=1` without the `bench.env not found` fatal.

- [ ] **Step 6: Commit on ES-1**

```bash
git commit -m "Add the benchmark harness

k6/ and docker-compose.bench.yml copied byte-identical from ES-4; bench.env is
the only per-branch file. Unblocks the ES-1-vs-ES-2 snapshot comparison, which
is untestable without a harness on this branch."
```

- [ ] **Step 7: Do the same for ES-3**

```bash
git checkout ES-3
git checkout ES-4 -- k6 docker-compose.bench.yml
```

Create `bench.env` at the repo root. This differs from ES-1's only in the branch comment and
the `VARIANT` line; everything else is identical because both are ES-family branches sharing
the same service names, job label and database:

```bash
# The ONLY per-branch file in the benchmark harness.
# Everything under k6/ and docker-compose.bench.yml must stay byte-identical on every
# branch carrying the harness. The acceptance test, using ES-2 as the reference:
#     git diff --stat ES-2 <branch> -- k6 docker-compose.bench.yml     # must be empty
#
# Branch: ES-3  (Axon 4.11.2, copy-on-write aggregate cache with an OPTIMISTIC lock
# factory. Differs from ES-4 only in the lock strategy, so the two are distinguishable
# only under contention -- see section 4.3 of the campaign plan.)

VARIANT=ES-3
VARIANT_FAMILY=ES

# docker compose service names (differ between the TO and ES families)
API_SVC=api-es
DB_SVC=postgres-es

# Prometheus job label for this branch's API target, and the cadvisor container-name
# regex. The regex must stay UNANCHORED: the API service is scaled with deploy.replicas
# and so carries no container_name, which means cadvisor sees `<project>-api-es-1`, `-2`,
# ... rather than a bare `api-es`. queries.promql matches it with an anchored name=~"$CRE".
#
# EXPECTED_REPLICAS is NOT set here. common.sh derives it from REPLICAS in .env, which is
# the file docker compose actually acts on -- one knob, not two.
PROM_JOB=inventory-es
API_CONTAINER_RE=.*api-es.*

DB_NAME=inventory
DB_USER=inventory

# Health endpoint on the published port, which on every branch is nginx in front of the
# api replicas. At REPLICAS>1 a healthy response only proves that ONE replica answered --
# reset.sh asserts the full container count separately.
HEALTH_URL=http://localhost:8080/actuator/health

# Image bench.sh rebuilds and tags before each run.
IMAGE_TAG=inventory-reservation-es:latest
```

Verify the service and job names against this branch's own compose and Prometheus config,
exactly as in Step 4, then:

```bash
git add -A
git diff --cached --stat ES-4 -- k6 docker-compose.bench.yml
bash -c '. k6/bench/common.sh && echo "VARIANT=$VARIANT PROM_JOB=$PROM_JOB REPLICAS=$EXPECTED_REPLICAS"'
git commit -m "Add the benchmark harness

k6/ and docker-compose.bench.yml copied byte-identical from ES-4; bench.env is
the only per-branch file. Unblocks the ES-3-vs-ES-4 lock-strategy comparison,
which is untestable without a harness on this branch."
```

Expected: the diff is empty and `common.sh` prints `VARIANT=ES-3 PROM_JOB=inventory-es REPLICAS=1`.

- [ ] **Step 8: Verify parity across every branch carrying the harness**

```bash
git checkout ES-4
for b in TO-1 TO-2 TO-3 TO-4 ES-1 ES-2 ES-3; do
    printf '%-6s ' "$b"
    if [ -z "$(git diff --stat ES-4 "$b" -- k6 docker-compose.bench.yml)" ]; then
        echo "identical"
    else
        echo "DIVERGED"
    fi
done
```

Expected: at this point `ES-1` and `ES-3` report `identical`; the six other branches report `DIVERGED` because Tasks 3, 4, 6 and 7 have not yet been replicated to them. Task 8 fixes that.

---

## Task 6: Add `--aggregate` to `compare.py`

The campaign runs 3 seeds per cell. `compare.py` renders one row per run directory, so the 96-run rate-ladder grid renders as 96 rows instead of 32 cells. Aggregation groups by cell and reports median with observed range.

**Files:**
- Modify: `k6/bench/compare.py`
- Create: `k6/bench/test_compare.py`

**Interfaces:**
- Consumes: `run["meta"]["config"]` keys `rate`, `distinctItems`, `itemsPerOrder`, `payloadBytes`; `run["meta"]["variant"]`, `["scenario"]`, `["expected_replicas"]`; any `dump.derived.*` / `dump.scalars.*` path already in `COLUMNS`.
- Produces: `cell_key(run) -> tuple`, `aggregate(runs) -> list[dict]`, and a `--aggregate` CLI flag. Nothing else consumes these.

- [ ] **Step 1: Write the failing tests**

Create `k6/bench/test_compare.py`:

```python
#!/usr/bin/env python3
"""Tests for compare.py aggregation. Run: python3 k6/bench/test_compare.py"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from compare import aggregate, cell_key, fmt_agg  # noqa: E402


def run(variant, rate, seed_p95, scenario="steady", items=6, lines=4):
    """Minimal run dict shaped like load_run()'s output."""
    return {
        "_dir": f"/tmp/{variant}_{rate}_{seed_p95}",
        "_name": f"{variant}_{scenario}_{seed_p95}",
        "meta": {
            "variant": variant, "variant_family": variant.split("-")[0],
            "scenario": scenario, "expected_replicas": 1,
            "config": {"rate": rate, "distinctItems": items,
                       "itemsPerOrder": lines, "payloadBytes": 0},
        },
        "dump": {"scalars": {"e2e_p95": {"confirmed": seed_p95}},
                 "derived": {"achieved_rps": rate}},
        "verdict": {"verdict": "PASS"},
    }


class TestCellKey(unittest.TestCase):
    def test_same_cell_different_seeds(self):
        a, b = run("ES-2", 60, 0.10), run("ES-2", 60, 0.12)
        self.assertEqual(cell_key(a), cell_key(b))

    def test_rate_separates_cells(self):
        self.assertNotEqual(cell_key(run("ES-2", 60, 0.1)),
                            cell_key(run("ES-2", 120, 0.1)))

    def test_variant_separates_cells(self):
        self.assertNotEqual(cell_key(run("ES-2", 60, 0.1)),
                            cell_key(run("TO-3", 60, 0.1)))

    def test_contention_axis_separates_cells(self):
        self.assertNotEqual(cell_key(run("ES-2", 60, 0.1, items=6)),
                            cell_key(run("ES-2", 60, 0.1, items=1)))


class TestAggregate(unittest.TestCase):
    def test_groups_three_seeds_into_one_cell(self):
        cells = aggregate([run("ES-2", 60, 0.10), run("ES-2", 60, 0.12),
                           run("ES-2", 60, 0.11)])
        self.assertEqual(len(cells), 1)
        self.assertEqual(cells[0]["n"], 3)

    def test_reports_median_not_mean(self):
        """An outlier seed must not drag the reported value."""
        cells = aggregate([run("ES-2", 60, 0.10), run("ES-2", 60, 0.11),
                           run("ES-2", 60, 9.99)])
        stat = cells[0]["stats"]["dump.scalars.e2e_p95.confirmed"]
        self.assertAlmostEqual(stat["median"], 0.11)
        self.assertAlmostEqual(stat["max"], 9.99)
        self.assertAlmostEqual(stat["min"], 0.10)

    def test_even_count_median_averages_middle_two(self):
        cells = aggregate([run("ES-2", 60, 0.10), run("ES-2", 60, 0.20)])
        stat = cells[0]["stats"]["dump.scalars.e2e_p95.confirmed"]
        self.assertAlmostEqual(stat["median"], 0.15)

    def test_separate_cells_stay_separate(self):
        cells = aggregate([run("ES-2", 60, 0.10), run("ES-2", 120, 0.30),
                           run("TO-3", 60, 0.01)])
        self.assertEqual(len(cells), 3)

    def test_missing_value_does_not_crash(self):
        bad = run("ES-2", 60, 0.10)
        bad["dump"]["scalars"]["e2e_p95"] = {}
        cells = aggregate([bad, run("ES-2", 60, 0.12)])
        stat = cells[0]["stats"]["dump.scalars.e2e_p95.confirmed"]
        self.assertEqual(stat["n"], 1)
        self.assertAlmostEqual(stat["median"], 0.12)


class TestFmtAgg(unittest.TestCase):
    def test_single_value_has_no_range(self):
        self.assertEqual(fmt_agg({"median": 0.1, "min": 0.1, "max": 0.1, "n": 1},
                                 ".3f"), "0.100")

    def test_spread_shown_as_bracketed_range(self):
        self.assertEqual(fmt_agg({"median": 0.11, "min": 0.10, "max": 0.12, "n": 3},
                                 ".3f"), "0.110 [0.100-0.120]")

    def test_empty_stat_renders_as_dash(self):
        self.assertEqual(fmt_agg({"median": None, "min": None, "max": None, "n": 0},
                                 ".3f"), "-")


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run to verify it fails**

```bash
python3 k6/bench/test_compare.py -v
```

Expected: `ImportError: cannot import name 'aggregate' from 'compare'`.

- [ ] **Step 3: Implement `cell_key`, `aggregate` and `fmt_agg`**

In `k6/bench/compare.py`, after `sort_key` (currently ending line 180), add:

```python
# --------------------------------------------------------------------------- aggregation
# The campaign runs 3 seeds per matrix cell. Without grouping, a 96-run grid renders as
# 96 rows and the reader has to do the aggregation by eye -- which is exactly where a
# spurious "ES is 8% faster" comes from when one seed happened to run hot.

# Everything that defines a distinct experimental condition. SEED is deliberately absent:
# it is the replication axis, and grouping over it is the point.
CELL_AXES = [
    "meta.variant",
    "meta.scenario",
    "meta.config.rate",
    "meta.config.distinctItems",
    "meta.config.itemsPerOrder",
    "meta.config.payloadBytes",
    "meta.expected_replicas",
]


def cell_key(run):
    return tuple(dig(run, axis) for axis in CELL_AXES)


def median(values):
    if not values:
        return None
    ordered = sorted(values)
    mid = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[mid]
    return (ordered[mid - 1] + ordered[mid]) / 2.0


def aggregate(runs, paths=None):
    """Group runs by cell; return [{key, runs, n, stats: {path: {median,min,max,n}}}].

    Median rather than mean: with n=3 a single saturated or thermally-throttled seed
    would move a mean by more than most of the effects under study.
    """
    if paths is None:
        paths = sorted({
            path
            for group in COLUMNS.values()
            for _, path, spec in group
            if spec not in ("s", "sha")
        })

    cells = {}
    for run in runs:
        cells.setdefault(cell_key(run), []).append(run)

    out = []
    for key, members in cells.items():
        stats = {}
        for path in paths:
            values = []
            for member in members:
                value = dig(member, path)
                if isinstance(value, (int, float)) and not isinstance(value, bool):
                    values.append(float(value))
            stats[path] = {
                "median": median(values),
                "min": min(values) if values else None,
                "max": max(values) if values else None,
                "n": len(values),
            }
        out.append({"key": key, "runs": members, "n": len(members), "stats": stats})

    out.sort(key=lambda cell: sort_key(cell["runs"][0]))
    return out


def fmt_agg(stat, spec):
    """median [min-max], collapsing to a bare median when there is no spread."""
    if not stat or stat.get("median") is None:
        return "-"
    body = fmt(stat["median"], spec)
    if stat["n"] < 2 or stat["min"] == stat["max"]:
        return body
    return f"{body} [{fmt(stat['min'], spec)}-{fmt(stat['max'], spec)}]"
```

- [ ] **Step 4: Run the tests**

```bash
python3 k6/bench/test_compare.py -v
```

Expected: 12 tests, all OK.

- [ ] **Step 5: Wire `--aggregate` into the table builder and CLI**

Add this function after `build_table`:

```python
def build_agg_table(runs, cols, style):
    spec = COLUMNS[cols] if cols != "all" else _all_columns()
    cells = aggregate(runs, paths=[p for _, p, s in spec if s not in ("s", "sha")])

    headers = [h for h, _, _ in spec] + ["n"]
    rows = []
    for cell in cells:
        first = cell["runs"][0]
        row = []
        for _, path, style_spec in spec:
            if style_spec in ("s", "sha"):
                row.append(fmt(dig(first, path), style_spec))
            else:
                row.append(fmt_agg(cell["stats"].get(path), style_spec))
        row.append(str(cell["n"]))
        rows.append(row)
    return render(rows, headers, style)
```

In `main()`, add the flag next to `--knee`:

```python
    ap.add_argument("--aggregate", action="store_true",
                    help="group runs by matrix cell; report median [min-max] across seeds")
```

and replace the final `print(...)` with:

```python
    if args.knee:
        print(knee_table(runs, args.style))
    elif args.aggregate:
        print(build_agg_table(runs, args.cols, args.style))
    else:
        print(build_table(runs, args.cols, args.baseline, args.style))
```

- [ ] **Step 6: Verify against real runs**

```bash
python3 k6/bench/compare.py --aggregate bench-results/TO-3_capacity_2026*
python3 k6/bench/compare.py --aggregate --cols latency bench-results/*_steady_*
python3 k6/bench/compare.py bench-results/*_steady_* | head -5
```

Expected: the first collapses the five TO-3 capacity runs into fewer rows with an `n` column and bracketed ranges where seeds differ; the third still renders one row per run, proving the default path is untouched.

- [ ] **Step 7: Commit**

```bash
git add k6/bench/compare.py k6/bench/test_compare.py
git commit -m "Add --aggregate to compare.py

The campaign runs 3 seeds per cell; without grouping a 96-run grid renders as 96
rows and the reader aggregates by eye. Groups on every axis except SEED and
reports median [min-max].

Median rather than mean: at n=3 one thermally-throttled seed moves a mean by more
than most of the effects under study."
```

---

## Task 7: Build the batch runner

361 runs across 8 branches cannot be driven by hand. One script turns a cell list into a night of runs, survives a crash mid-night, and records what happened.

**Files:**
- Create: `k6/bench/batch.sh`
- Create: `k6/bench/cells/night-01-capacity.txt`
- Create: `k6/bench/cells/README.md`

**Interfaces:**
- Consumes: `k6/bench/common.sh` (for `log`/`die`), `k6/bench/bench.sh` (invoked per cell).
- Produces: `bench-results/<batch-id>/manifest.tsv` — tab-separated `cell_id`, `branch`, `status`, `run_dir`, `verdict`, `seconds`. Resumption reads this file.

- [ ] **Step 1: Define the cell-list format**

Create `k6/bench/cells/README.md`:

```markdown
# Cell lists

One line per benchmark run. Blank lines and `#` comments are ignored.

    <branch> <KEY=VALUE> [KEY=VALUE ...]

Fields are whitespace-separated. The first field is the git branch; the rest are
environment knobs passed through to `bench.sh` verbatim. Knob names must be ones
`bench.sh` forwards to k6 — see the `KNOBS` array in `bench.sh`, plus `SCENARIO`.

Example:

    ES-4 SCENARIO=steady RATE=60 DURATION=10m SEED=1337

Ordering matters. `batch.sh` runs lines top to bottom, and cells are grouped by
branch so the image is rebuilt once per branch rather than once per run. Randomise
the order of the branch BLOCKS between nights -- the machine thermally drifts over a
9-hour batch, and a fixed branch order makes that drift correlate with variant,
which is indistinguishable from an architectural effect.
```

- [ ] **Step 2: Write the Night 1 cell list**

Create `k6/bench/cells/night-01-capacity.txt`. This is §5's ladder-validation gate: 8 capacity runs, one seed, branch order shuffled.

```
# Night 1 -- capacity staircase, all 8 variants, seed 1337.
# The ladder-validation gate (campaign plan section 5): if any variant's knee falls
# below 20 rps the grid's lowest rung is saturated for it; below 80 rps the fixed
# rates in sections 4.3 and 4.4 are saturated for it. Do NOT start Night 2 until
# these results have been read.
#
# ~40 min per run, ~5.3 h total.

ES-3 SCENARIO=capacity STEP_COUNT=15 SEED=1337
TO-2 SCENARIO=capacity STEP_COUNT=15 SEED=1337
ES-1 SCENARIO=capacity STEP_COUNT=15 SEED=1337
TO-4 SCENARIO=capacity STEP_COUNT=15 SEED=1337
ES-4 SCENARIO=capacity STEP_COUNT=15 SEED=1337
TO-1 SCENARIO=capacity STEP_COUNT=15 SEED=1337
ES-2 SCENARIO=capacity STEP_COUNT=15 SEED=1337
TO-3 SCENARIO=capacity STEP_COUNT=15 SEED=1337
```

- [ ] **Step 3: Write `batch.sh`**

Create `k6/bench/batch.sh`:

```bash
#!/usr/bin/env bash
# Run a cell list as one batch. The campaign is 361 runs across 8 branches; this is
# what makes that tractable and, more importantly, what makes a night survivable when
# run 14 of 30 dies at 03:00.
#
#   ./k6/bench/batch.sh k6/bench/cells/night-01-capacity.txt
#   BATCH_ID=night-01 ./k6/bench/batch.sh k6/bench/cells/night-01-capacity.txt   # resume
#
# Resumption is by cell id (the line's own index + content hash), recorded in
# manifest.tsv. Re-running the same list with the same BATCH_ID skips every cell already
# marked ok, so a crashed night is resumed with the same command that started it.
set -uo pipefail   # NOT -e: one failed cell must not end the night.

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"

CELLS="${1:?usage: batch.sh <cell-list-file>}"
[ -f "$CELLS" ] || { echo "FATAL: no such cell list: $CELLS" >&2; exit 1; }

BATCH_ID="${BATCH_ID:-$(basename "$CELLS" .txt)_$(date -u +%Y%m%dT%H%M%SZ)}"
BATCH_DIR="$REPO_ROOT/bench-results/$BATCH_ID"
MANIFEST="$BATCH_DIR/manifest.tsv"
mkdir -p "$BATCH_DIR"

blog() { printf '[%s] [batch] %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$BATCH_DIR/batch.log" >&2; }

# ---------------------------------------------------------------- preflight
# ALLOW_DIRTY is the single biggest source of wasted runs in this project's history:
# exported once into a shell, it silently invalidated every run made from it. A batch
# must never inherit it.
if [ "${ALLOW_DIRTY:-0}" != "0" ]; then
    echo "FATAL: ALLOW_DIRTY is set in the environment. Every run in this batch would be" >&2
    echo "       unciteable. Unset it (fish: set -e ALLOW_DIRTY) and start again." >&2
    exit 1
fi

if [ -n "$(git -C "$REPO_ROOT" status --porcelain -- src/)" ]; then
    echo "FATAL: src/ is dirty; commit before starting a batch." >&2
    git -C "$REPO_ROOT" status --short -- src/ >&2
    exit 1
fi

START_BRANCH="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)"
blog "batch $BATCH_ID -> $BATCH_DIR (starting branch $START_BRANCH)"

[ -f "$MANIFEST" ] || printf 'cell_id\tbranch\tstatus\trun_dir\tverdict\tseconds\n' > "$MANIFEST"

done_cell() { cut -f1 "$MANIFEST" | grep -Fxq "$1"; }

# Restore the starting branch however we exit, so a crashed batch does not leave the
# repository parked on some other variant -- which is how stale-runtime-state bugs start.
cleanup() {
    blog "restoring branch $START_BRANCH"
    git -C "$REPO_ROOT" checkout --quiet "$START_BRANCH" 2>/dev/null || true
}
trap cleanup EXIT

# ---------------------------------------------------------------- run
TOTAL=0; OK=0; FAILED=0; SKIPPED=0; INDEX=0

while IFS= read -r line || [ -n "$line" ]; do
    line="${line%%#*}"
    line="$(echo "$line" | xargs)"      # trim
    [ -z "$line" ] && continue
    INDEX=$((INDEX + 1))
    TOTAL=$((TOTAL + 1))

    BRANCH="${line%% *}"
    KNOBS="${line#* }"
    [ "$KNOBS" = "$BRANCH" ] && KNOBS=""
    CELL_ID="$(printf '%03d_%s' "$INDEX" "$(printf '%s' "$line" | sha1sum | cut -c1-8)")"

    if done_cell "$CELL_ID"; then
        blog "[$INDEX] SKIP $CELL_ID (already done)"
        SKIPPED=$((SKIPPED + 1))
        continue
    fi

    blog "[$INDEX/$TOTAL] $CELL_ID  branch=$BRANCH  $KNOBS"

    if ! git -C "$REPO_ROOT" checkout --quiet "$BRANCH" 2>>"$BATCH_DIR/batch.log"; then
        blog "[$INDEX] CHECKOUT FAILED for $BRANCH"
        printf '%s\t%s\tcheckout_failed\t-\t-\t0\n' "$CELL_ID" "$BRANCH" >> "$MANIFEST"
        FAILED=$((FAILED + 1))
        continue
    fi

    CELL_START=$(date +%s)
    # env -i would drop PATH/HOME and break docker; instead pass the knobs explicitly in
    # front of the command, which is also exactly what a human would type by hand.
    if env $KNOBS "$HERE/bench.sh" >>"$BATCH_DIR/batch.log" 2>&1; then
        STATUS=ok; OK=$((OK + 1))
    else
        STATUS=failed; FAILED=$((FAILED + 1))
    fi
    ELAPSED=$(($(date +%s) - CELL_START))

    RUN_DIR="$(ls -1dt "$REPO_ROOT"/bench-results/*_* 2>/dev/null | grep -v "$BATCH_DIR" | head -1)"
    VERDICT="$(python3 -c "
import json, sys
try:
    print(json.load(open('$RUN_DIR/verdict.json'))['verdict'])
except Exception:
    print('-')
" 2>/dev/null)"

    printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$CELL_ID" "$BRANCH" "$STATUS" "$(basename "$RUN_DIR")" "$VERDICT" "$ELAPSED" \
        >> "$MANIFEST"
    blog "[$INDEX] $STATUS verdict=$VERDICT in ${ELAPSED}s -> $(basename "$RUN_DIR")"
done < "$CELLS"

blog "batch done: $OK ok, $FAILED failed, $SKIPPED skipped, of $TOTAL"
blog "manifest: $MANIFEST"
[ "$FAILED" -eq 0 ]
```

- [ ] **Step 4: Make it executable and check syntax**

```bash
chmod +x k6/bench/batch.sh
bash -n k6/bench/batch.sh && echo "syntax OK"
```

Expected: `syntax OK`.

- [ ] **Step 5: Verify the `ALLOW_DIRTY` refusal and the cell parser without running any benchmark**

```bash
ALLOW_DIRTY=1 bash k6/bench/batch.sh k6/bench/cells/night-01-capacity.txt; echo "exit=$?"
```

Expected: exits 1 with the `ALLOW_DIRTY is set` message, before any checkout.

Then dry-run the parser by pointing it at a stub `bench.sh`:

```bash
mkdir -p /tmp/batchtest && cp k6/bench/batch.sh /tmp/batchtest/
printf '#!/usr/bin/env bash\necho "stub bench.sh SCENARIO=$SCENARIO RATE=${RATE:-} SEED=$SEED"\n' > /tmp/batchtest/bench.sh
chmod +x /tmp/batchtest/bench.sh
BATCH_ID=parsecheck bash /tmp/batchtest/batch.sh k6/bench/cells/night-01-capacity.txt 2>&1 | tail -20
```

Expected: eight lines, one per cell, each logging its branch and knobs, and a final `batch done: 8 ok`. Confirm the branch order matches the file (ES-3 first, TO-3 last) and that `SCENARIO=capacity` reached the stub.

- [ ] **Step 6: Verify resumption**

```bash
BATCH_ID=parsecheck bash /tmp/batchtest/batch.sh k6/bench/cells/night-01-capacity.txt 2>&1 | tail -3
```

Expected: `batch done: 0 ok, 0 failed, 8 skipped, of 8` — every cell recognised as already complete.

- [ ] **Step 7: Clean up and commit**

```bash
rm -rf /tmp/batchtest "$PWD/bench-results/parsecheck"
git status --porcelain | head
git add k6/bench/batch.sh k6/bench/cells
git commit -m "Add the batch runner

361 runs across 8 branches is not a by-hand job, and a night that dies at run 14
of 30 must resume rather than restart. Cells are keyed by index and content hash
in manifest.tsv; re-running the same list with the same BATCH_ID skips what is
already done.

Refuses to start with ALLOW_DIRTY set -- exported once into a shell, that flag
produced every git_clean INVALID verdict in this project's history."
```

---

## Task 8: Replicate the harness to every branch

`k6/` must be byte-identical everywhere. Tasks 3, 4, 6 and 7 changed it on `ES-4` only.

**Files:**
- Modify on `TO-1`..`TO-4`, `ES-1`, `ES-2`, `ES-3`: `k6/`, `docker-compose.bench.yml`

**Interfaces:**
- Consumes: the finished `k6/` tree on `ES-4`.
- Produces: eight branches on which `bench.sh` behaves identically.

- [ ] **Step 1: Confirm ES-4 is the complete reference**

```bash
git checkout ES-4
git status --porcelain | head
ls k6/bench/
python3 k6/bench/test_derive.py && python3 k6/bench/test_compare.py
```

Expected: clean tree; `k6/bench/` contains `batch.sh`, `audit-parity.py`, `test_derive.py`, `test_compare.py`, `cells/`; both test files pass.

- [ ] **Step 2: Replicate to each branch**

For each of `TO-1 TO-2 TO-3 TO-4 ES-1 ES-2 ES-3`:

```bash
git checkout <branch>
git checkout ES-4 -- k6 docker-compose.bench.yml
git status --porcelain -- k6 docker-compose.bench.yml
```

`bench.env` is never copied — it is the per-branch file and `git checkout ES-4 -- k6` does not touch it, since it lives at the repo root rather than under `k6/`.

Then commit:

```bash
git add k6 docker-compose.bench.yml
git commit -m "Sync benchmark harness from ES-4

Jar-content image freshness, ALLOW_DIRTY refusal, cpu_seconds_per_order,
compare.py --aggregate, and the batch runner. k6/ is byte-identical on every
branch by design; bench.env remains the only per-branch file."
```

- [ ] **Step 3: Assert byte-identity everywhere**

```bash
git checkout ES-4
FAIL=0
for b in TO-1 TO-2 TO-3 TO-4 ES-1 ES-2 ES-3; do
    printf '%-6s ' "$b"
    if [ -z "$(git diff --stat ES-4 "$b" -- k6 docker-compose.bench.yml)" ]; then
        echo "identical"
    else
        echo "DIVERGED"; FAIL=1
    fi
done
echo "---"; [ "$FAIL" -eq 0 ] && echo "ALL IDENTICAL" || echo "DIVERGENCE FOUND"
```

Expected: seven `identical` lines and `ALL IDENTICAL`. This is the design invariant from `bench.env`'s own header comment; if it fails, stop and reconcile before any measurement.

- [ ] **Step 4: Re-assert metric parity now that all branches have moved**

```bash
python3 k6/bench/audit-parity.py; echo "exit=$?"
```

Expected: `exit=0`.

---

## Task 9: Rehearsal

Two runs that prove the whole chain works before committing thirteen nights to it.

**Files:** none. This task runs the system.

**Interfaces:**
- Consumes: everything above.
- Produces: two `PASS` verdicts, or a diagnosis.

- [ ] **Step 1: Pin the environment**

```bash
cat .env
```

`REPLICAS=1` and `PG_MAX_CONNECTIONS=600` must both be set. If `REPLICAS` is anything else, fix it: at `REPLICAS>1` the rejection rate is a lost-write-race artefact, and `evaluate.py` skips the `saga_command_failed_single_node` validity check entirely.

Close the browser, the IDE, and stop the gradle daemon — k6, the API, Postgres, Prometheus and cadvisor share 12 threads:

```bash
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 ./gradlew --stop
docker stats --no-stream
```

- [ ] **Step 2: Write the rehearsal cell list**

Create `k6/bench/cells/rehearsal.txt`:

```
# Rehearsal (campaign plan V7): one cell per family, n=1. ~35 min.
# Proves the validity protocol works before thirteen nights depend on it.
# Both cells must return PASS. A FAIL is a real SLO miss and worth investigating;
# an INVALID means the harness itself is still broken and the campaign cannot start.

TO-3 SCENARIO=steady RATE=60 DURATION=5m SEED=1337
ES-2 SCENARIO=steady RATE=60 DURATION=5m SEED=1337
```

- [ ] **Step 3: Run it**

```bash
BATCH_ID=rehearsal ./k6/bench/batch.sh k6/bench/cells/rehearsal.txt
```

Expected: `batch done: 2 ok, 0 failed`. Takes ~35 minutes.

- [ ] **Step 4: Check the verdicts and the two guards that were fixed**

```bash
cat bench-results/rehearsal/manifest.tsv
python3 - <<'PY'
import glob, json
for path in sorted(glob.glob("bench-results/*_steady_*/verdict.json"))[-2:]:
    v = json.load(open(path))
    print(f"\n{v['run_id']}: {v['verdict']}")
    for c in v.get("checks", []):
        if c.get("kind") == "validity":
            mark = "ok" if c.get("pass") else "XX"
            print(f"  [{mark}] {c['name']:34} actual={c.get('actual')}")
PY
```

Expected: both `PASS`. Specifically `git_clean` actual `0` and `image_fresh` actual `True` — those are Tasks 1-3 proving out. An `image_fresh` of `False` means the jar path in Task 3 Step 3 is wrong.

- [ ] **Step 5: Confirm the new metric landed and TO's histogram bounds took effect**

```bash
python3 k6/bench/compare.py --cols resource bench-results/TO-3_steady_2026* bench-results/ES-2_steady_2026* | tail -5
```

Expected: `CPUs/order` shows a real number for both runs, not `-`. A `-` means the cadvisor query matched nothing on that branch — check `API_CONTAINER_RE` in its `bench.env`.

Then confirm the TO histogram is no longer clamped at 30 s:

```bash
curl -sG http://localhost:9090/api/v1/query \
  --data-urlencode 'query=max(order_e2e_time_seconds_bucket{job="inventory-to"}) by (le)' \
  | python3 -c "
import json, sys
buckets = json.load(sys.stdin)['data']['result']
edges = sorted(float(b['metric']['le']) for b in buckets if b['metric']['le'] != '+Inf')
print('highest finite bucket edge:', edges[-1] if edges else 'none')
print('expected: ~600 (10m), NOT ~30')
"
```

Expected: the top finite bucket edge is around 600 s. Around 30 s means Task 1 did not reach the running image on this branch.

- [ ] **Step 6: Commit the rehearsal results**

```bash
git add bench-results k6/bench/cells/rehearsal.txt
git commit -m "Rehearsal: TO-3 and ES-2 steady at 60 rps both PASS

Proves the validity protocol end to end -- git_clean 0, image_fresh true,
cpu_seconds_per_order populated, and TO's e2e histogram bounded at 10m rather
than clamped at Micrometer's 30s default. Night 1 can start."
```

- [ ] **Step 7: Gate on the result**

If either run is `INVALID`, do **not** start Night 1. Diagnose from `verdict.json`'s failing validity check:

| check | meaning | first thing to look at |
|---|---|---|
| `git_clean` | dirty `src/` | `git status --porcelain -- src/` |
| `image_fresh` | jar mismatch | the jar path in Task 3 Step 3 |
| `backlog_drained` | orders never finished | API logs; `axon.jdbc.pool.size` vs saga claim |
| `scrape_up` / `no_api_restart` | API died | `docker compose logs api-es`; heap |
| `completion_ratio_inverse` | orders with no terminal event | saga stalls; `max-gap-offset` |
| `targets_scraped` | Prometheus lost a target | `REPLICAS` vs running containers |

A `FAIL` is fine — it is an SLO miss, which is a result, not a broken measurement.

---

## Execution order

Tasks 1 and 2 are independent and can go first in any order. Tasks 3, 4, 6 and 7 are all `k6/` work on `ES-4` and must precede Task 8. Task 5 copies the harness to ES-1/ES-3 and is simplest to do *as part of* Task 8 — but it is listed separately because it also needs a hand-written `bench.env`, which Task 8's mechanical sync does not produce.

```
1 (TO bounds) ─┐
2 (track results) ─┤
3 (bench.sh guards) ─┤
4 (cpu/order) ─┼─> 8 (replicate) ─> 9 (rehearsal) ─> Night 1
6 (--aggregate) ─┤
7 (batch runner) ─┤
5 (ES-1/ES-3 port) ─┘
```

Task 9's rehearsal is the gate. Nothing in the campaign starts until both cells return `PASS`.
