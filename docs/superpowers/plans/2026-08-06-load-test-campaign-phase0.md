# Load-Test Campaign Phase 0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring all 8 variant branches to a state where the campaign in `docs/superpowers/specs/2026-08-06-load-test-campaign-design.md` can be executed — one identical benchmark harness everywhere, `RESERVE_DELAY_MS` and `PAYLOAD_BYTES` honoured as real per-reserve costs on every branch, and a `stress` scenario the harness can run and judge.

**Architecture:** Three layers, in strict order. First the harness is re-synchronised — it currently diverges on all 7 non-`TO-3` branches and is absent entirely on `ES-1` and `ES-3`, so nothing measured today is cross-comparable. Then the two harness features (`stress`, `RUN_LABEL`) are authored once on `TO-3` and the whole `k6/` tree is converged onto every branch. Only then do the application changes land, per family: TO stores state in a row and needs migrations, ES rebuilds it from events and needs none.

**Tech Stack:** Kotlin 2.3 / Spring Boot 4.0 / JUnit 5 + MockK (TO), plus Axon 4.11.2 and `axon-test` `AggregateTestFixture` (ES); Flyway on PostgreSQL; k6 (JavaScript); Python 3 `unittest` for the harness.

## Global Constraints

- **Branches:** `TO-1`, `TO-2`, `TO-3`, `TO-4`, `ES-1`, `ES-2`, `ES-3`, `ES-4`. There is no `main`-based development; each variant branch is a deliverable.
- **Never push.** Commit locally only. The user pushes.
- **Canonical harness = `TO-3` after Task 1.** `TO-3` carries the newest dashboard and `reserveDelayMs` harness work; `ES-4` carries three files with ES-specific additions `TO-3` lacks. Task 1 merges the latter into the former, and every other branch is then converged onto the result.
- **Cross-branch invariant (currently violated, restored by Task 4):** `k6/` and `docker-compose.bench.yml` byte-identical on all 8 branches; `bench.env` the only per-branch file. Verify with `git diff --stat TO-3 <branch> -- k6 docker-compose.bench.yml` — must be empty.
- **`JAVA_HOME`** must be `$HOME/.jdks/corretto-21.0.10` — use `$HOME`, never `~`, which is not expanded after `=`. The environment's default points at a missing `corretto-21.0.6`.
- **Never run the harness under `sudo`.** It refuses, and root-owned `bench-results/` breaks every later run.
- **Both knobs must be provable no-ops at 0.** `RESERVE_DELAY_MS=0` reaches no sleep call; `PAYLOAD_BYTES=0` stores an empty string. Phase 1 of the campaign measures every variant at 0 and compares those numbers against phase-2 runs on the same binaries.
- **New TO migration versions are fixed and uniform:** `V5__reserve_delay.sql`, `V6__additional_bytes.sql`. `TO-3` already carries reserve-delay as `V2__reserve_delay.sql` — **do not renumber it.** `TO-2`'s `V2` is its NOTIFY trigger, which is why `V2` is not reused. Flyway tolerates version gaps.
- **Test commands, from the repository root:** `JAVA_HOME=$HOME/.jdks/corretto-21.0.10 ./gradlew test` and `python3 -m unittest discover -s scripts/tests -t .`

---

## Starting State (audited 2026-08-06)

This is what the plan corrects. Do not assume any of it has changed.

| Branch | `k6/` vs `TO-3` | `bench.env` | Notes |
|---|---|---|---|
| `TO-1` | 8 files differ | present | missing the `reserveDelayMs` plumbing in `config.js`, `api.js`, `main.js` |
| `TO-2` | 8 files differ | present | same as `TO-1` |
| `TO-4` | 8 files differ | present | same as `TO-1` |
| `ES-1` | **harness absent** | **missing** | has legacy `k6/reserve-load-test.js`; no `k6/bench/`, no `k6/lib/`, no `docker-compose.bench.yml` |
| `ES-2` | 10 files differ | present | older `evaluate.py`, `compare.py`, `queries.promql` |
| `ES-3` | **harness absent** | **missing** | same as `ES-1` |
| `ES-4` | 5 files differ, insertions only | present | strict superset of `TO-3` in `compare.py`, `evaluate.py`, `queries.promql`; plus two stale planning docs |

`ES-1` and `ES-3` do carry the application instrumentation the harness queries (`order_e2e_time`, `order_projection_lag_seconds`), so the gap there is files, not metrics.

---

## File Structure

**Harness — authored on `TO-3`, converged onto all 8 branches**

| File | Responsibility |
|---|---|
| `k6/bench/compare.py`, `k6/bench/evaluate.py`, `k6/bench/queries.promql` | Modify (Task 1): absorb `ES-4`'s ES-specific additions. |
| `k6/benchmark-campaign-plan.md`, `k6/campaign-prerequisites-plan.md` | Delete (Task 1): superseded planning docs living only on `ES-4`. |
| `k6/lib/profiles.js` | Modify (Task 2): register a `stress` builder. |
| `k6/bench/thresholds.json` | Modify (Task 2): `scenarios.stress` block relaxing the drain gate. |
| `k6/bench/evaluate.py` | Modify (Task 2): make the `backlog_drained` gate per-scenario. |
| `k6/bench/bench.sh` | Modify (Task 3): optional sanitised `RUN_LABEL` in `RUN_NAME`. |
| `scripts/tests/test_evaluate.py` | Create (Task 2). |
| `scripts/tests/test_bench_sh.py` | Create (Task 3). |
| `bench.env` | Create on `ES-1`, `ES-3` (Task 4): the only per-branch file. |
| `monitoring/prometheus/prometheus.yml` | Modify on `ES-1`, `ES-3` (Task 4): DNS service discovery for `api-es`. |

**TO family (`TO-1`, `TO-2`, `TO-3`, `TO-4`)**

| File | Responsibility |
|---|---|
| `src/main/resources/db/migration/V5__reserve_delay.sql` | Create on `TO-1`, `TO-2`, `TO-4` only. |
| `src/main/resources/db/migration/V6__additional_bytes.sql` | Create on all four. |
| `src/main/kotlin/pl/szymanski/wiktor/domain/InventoryItem.kt` | Modify: both fields; sleep inside `reserve()`. |
| `src/main/kotlin/pl/szymanski/wiktor/domain/events.kt` | Modify: `reserveDelayMs` on `InventoryCreatedEvent` (already on `TO-3`). |
| `src/main/kotlin/pl/szymanski/wiktor/service/command/CreateItemCommandHandler.kt` | Modify: knob on `CreateItemCommand`. |
| `src/main/kotlin/pl/szymanski/wiktor/controller/InventoryController.kt` | Modify: knob on `CreateItemRequest`. |
| `src/test/kotlin/pl/szymanski/wiktor/domain/InventoryItemBenchKnobsTest.kt` | Create on all four. |

**ES family (`ES-1`, `ES-2`, `ES-3`)** — `ES-4` already complete

| File | Responsibility |
|---|---|
| `src/main/kotlin/pl/szymanski/wiktor/domain/InventoryItem.kt` | Modify: field, ESH hydration, sleep in the command handler. |
| `src/main/kotlin/pl/szymanski/wiktor/domain/events.kt` | Modify: `reserveDelayMs` on `InventoryCreatedEvent`. |
| `src/main/kotlin/pl/szymanski/wiktor/service/command/CreateItemCommandHandler.kt` | Modify: knob on `CreateItemCommand`. |
| `src/main/kotlin/pl/szymanski/wiktor/controller/InventoryController.kt` | Modify: knob on `CreateItemRequest`. |
| `src/test/kotlin/pl/szymanski/wiktor/domain/InventoryItemReserveDelayTest.kt` | Create on all three. |

**Documentation**

| File | Responsibility |
|---|---|
| `docs/bench-campaign-runbook.md` | Create: the ordered run list with exact commands. |
| `k6/README.md` | Modify: replace the stale §3 phase plan. |
| `k6/load-tests-plan.md` | Delete: plans a 12-variant campaign whose branches no longer exist. |

---

## Task 1: Establish the canonical harness on `TO-3`

`ES-4` carries additions to three harness files that `TO-3` lacks — the ES/saga-specific metric columns. `TO-3` carries newer dashboard and `reserveDelayMs` work. Neither is a superset of the other, so the canonical harness has to be assembled before anything is propagated.

**Files:**
- Modify: `k6/bench/compare.py`, `k6/bench/evaluate.py`, `k6/bench/queries.promql`
- Delete: `k6/benchmark-campaign-plan.md`, `k6/campaign-prerequisites-plan.md` (these exist only on `ES-4`)

**Interfaces:**
- Consumes: nothing.
- Produces: `TO-3`'s `k6/` tree becomes the single canonical harness that Tasks 2, 3 and 4 build on and propagate. After this task, `git diff TO-3 ES-4 -- k6/bench/compare.py k6/bench/evaluate.py k6/bench/queries.promql` is empty.

- [ ] **Step 1: Inspect exactly what `ES-4` adds**

```bash
git checkout TO-3
git diff TO-3 ES-4 -- k6/bench/compare.py k6/bench/evaluate.py k6/bench/queries.promql
```

Expected: insertions only, no deletions — roughly 19 lines in `compare.py`, 22 in `evaluate.py`, 17 in `queries.promql`. Read them. They should be ES-specific metric columns and queries (saga stages, event-store internals). If any hunk *removes* a line that `TO-3` has, stop: the two harnesses have genuinely forked and merging needs a human decision, not a checkout.

- [ ] **Step 2: Take `ES-4`'s version of the three files**

```bash
git checkout ES-4 -- k6/bench/compare.py k6/bench/evaluate.py k6/bench/queries.promql
git diff --stat HEAD
```

Expected: exactly three files modified. This is safe only because Step 1 confirmed insertions-only; `ES-4`'s copies are then supersets of `TO-3`'s.

- [ ] **Step 3: Verify the harness still runs against an archived run**

```bash
python3 k6/bench/evaluate.py \
  --run-dir bench-results/TO-3_capacity_20260805T202853Z \
  --thresholds k6/bench/thresholds.json
python3 k6/bench/compare.py --knee bench-results/TO-3_capacity_*
```

Expected: `evaluate.py` prints a verdict and exits 0, 1 or 2 without a traceback; `compare.py` prints a table. A `KeyError` here means `ES-4`'s additions reference a `dump.json` key that TO runs do not produce — fix by guarding the lookup, not by reverting.

- [ ] **Step 4: Run the Python suite**

Run: `python3 -m unittest discover -s scripts/tests -t . -v`

Expected: PASS, 22 tests.

- [ ] **Step 5: Commit**

```bash
git add k6/bench/compare.py k6/bench/evaluate.py k6/bench/queries.promql
git commit -m "chore(bench): make TO-3 the canonical harness by absorbing ES-4's additions

The harness was supposed to be byte-identical on every branch and is not: it
diverges on all seven non-TO-3 branches and is absent from ES-1 and ES-3.
Re-converging needs one canonical copy, and neither candidate was a superset --
TO-3 had the newer dashboard and reserveDelayMs work, ES-4 had ES-specific
metric columns in compare.py, evaluate.py and queries.promql.

Insertions only, verified against an archived TO run so the added ES columns
degrade cleanly where the metrics do not exist."
```

---

## Task 2: `stress` scenario

Work on `TO-3`, on top of Task 1.

**Files:**
- Modify: `k6/lib/profiles.js` (the `BUILDERS` map, near line 229)
- Modify: `k6/bench/thresholds.json` (the `scenarios` object)
- Modify: `k6/bench/evaluate.py` (`check_validity` signature, the `backlog_drained` check, and the call site in `main`)
- Test: `scripts/tests/test_evaluate.py` (create)

**Interfaces:**
- Consumes: Task 1's canonical `evaluate.py`.
- Produces: `SCENARIO=stress` valid for `k6/bench/bench.sh`. `evaluate.check_validity(checks, meta, dump, summary, cfg, limits)` gains a sixth positional parameter `limits` — the merged per-scenario threshold dict. `thresholds.json` gains `scenarios.stress.require_backlog_drained` (boolean).

- [ ] **Step 1: Write the failing test**

Create `scripts/tests/test_evaluate.py`:

```python
import argparse
import importlib.util
import json
import os
import tempfile
import unittest

HERE = os.path.dirname(__file__)
REPO = os.path.join(HERE, "..", "..")
BENCH = os.path.join(REPO, "k6", "bench")

_spec = importlib.util.spec_from_file_location(
    "evaluate", os.path.join(BENCH, "evaluate.py"))
evaluate = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(evaluate)


class BacklogDrainedGate(unittest.TestCase):
    """The drain gate is what makes every other scenario trustworthy and what makes
    `stress` unrunnable: under deliberate overload the backlog IS the result."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        # check_validity reads profile.json off the module-global ARGS that main() sets.
        evaluate.ARGS = argparse.Namespace(run_dir=self.tmp)

    def run_validity(self, limits):
        checks = evaluate.Checks()
        meta = {"expected_replicas": 1, "git_dirty": 0, "image_built_after_head": True}
        dump = {
            "scalars": {"scrape_up_min": 1, "api_resets": 0, "target_count": 1},
            "derived": {"drained": False, "completion_ratio": 1.0},
        }
        evaluate.check_validity(
            checks, meta, dump, {"metrics": {}}, {"min_completion_ratio": 0.999}, limits)
        return checks

    def failed_names(self, checks):
        return sorted(c["name"] for c in checks.failed("validity"))

    def test_undrained_backlog_is_invalid_by_default(self):
        self.assertIn("backlog_drained", self.failed_names(self.run_validity({})))

    def test_undrained_backlog_is_tolerated_when_the_gate_is_disabled(self):
        self.assertEqual(
            [], self.failed_names(self.run_validity({"require_backlog_drained": False})))

    def test_disabling_the_gate_still_records_the_observation(self):
        checks = self.run_validity({"require_backlog_drained": False})
        entry = next(c for c in checks.items if c["name"] == "backlog_drained")
        self.assertFalse(entry["actual"])
        self.assertTrue(entry["pass"])


class StressWiring(unittest.TestCase):
    def load_thresholds(self):
        with open(os.path.join(BENCH, "thresholds.json")) as fh:
            return json.load(fh)

    def test_thresholds_defines_stress_and_relaxes_the_drain_gate(self):
        stress = self.load_thresholds()["scenarios"]["stress"]
        self.assertIs(False, stress["require_backlog_drained"])
        self.assertIsNone(stress["max_e2e_p95_confirmed_s"])

    def test_only_stress_opts_out_of_the_drain_gate(self):
        """A stray opt-out elsewhere would quietly turn broken measurements into
        reportable ones, which is the failure mode INVALID exists to prevent."""
        for name, block in self.load_thresholds()["scenarios"].items():
            if name == "stress":
                continue
            self.assertNotIn("require_backlog_drained", block,
                             f"{name} opts out of the drain gate")

    def test_profiles_registers_a_stress_builder(self):
        with open(os.path.join(REPO, "k6", "lib", "profiles.js")) as fh:
            self.assertIn("stress:", fh.read())


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `python3 -m unittest scripts.tests.test_evaluate -v`

Expected: FAIL. `BacklogDrainedGate` errors with `TypeError: check_validity() takes 5 positional arguments but 6 were given`; `StressWiring` fails with `KeyError: 'stress'` and an `AssertionError` on `stress:`.

- [ ] **Step 3: Register the `stress` profile**

In `k6/lib/profiles.js`, in the `BUILDERS` map, add `stress` directly after `soak`:

```js
const BUILDERS = {
    seed: seedProfile,
    warmup: warmupProfile,
    capacity,
    steady: () => constantRate('steady', CONFIG.rate, CONFIG.duration),
    soak: () => constantRate('soak', CONFIG.rate, CONFIG.soakDuration),
    // Same shape as `steady`. The difference is entirely in how it is RUN (RATE set above
    // the measured knee) and how it is JUDGED (see thresholds.json). Giving it its own name
    // rather than reusing `steady` is what lets evaluate.py apply overload rules, and what
    // keeps the two apart in bench-results/.
    stress: () => constantRate('stress', CONFIG.rate, CONFIG.duration),
    spike,
    legacy,
    'legacy-vus': legacyVus,
};
```

- [ ] **Step 4: Add the `stress` thresholds block**

In `k6/bench/thresholds.json`, inside `"scenarios"`, add between `"spike"` and `"soak"`:

```json
    "stress": {
      "_comment": [
        "Deliberate overload above the knee. Latency SLOs are meaningless by construction,",
        "and an undrained backlog is the measurement rather than a broken measurement --",
        "so require_backlog_drained is false HERE AND NOWHERE ELSE. What must still hold:",
        "every admitted order reaches a terminal state (completion_ratio), the API does not",
        "restart, and admission does not start refusing connections."
      ],
      "require_backlog_drained": false,
      "max_e2e_p95_confirmed_s": null,
      "max_e2e_p99_confirmed_s": null,
      "max_drain_seconds": null,
      "max_rejected_ratio": null,
      "max_projection_lag_p95_s": null
    },
```

`max_non202_ratio` and `max_opt_exhausted` deliberately keep their defaults: under overload both are genuine findings and should surface as FAIL, which is reportable, rather than being hidden.

- [ ] **Step 5: Make the drain gate configurable**

Change the signature in `k6/bench/evaluate.py`:

```python
def check_validity(checks, meta, dump, summary, cfg, limits):
```

Replace the `backlog_drained` check in the body with:

```python
    # The one validity gate a scenario may opt out of. Under `stress` the run is pushed past
    # the knee on purpose, so a backlog outliving DRAIN_TIMEOUT is the result. Every other
    # scenario keeps it hard: a truncated e2e histogram is unusable, and reporting its
    # optimistic percentile would be worse than reporting nothing.
    if limits.get("require_backlog_drained", True):
        checks.add("backlog_drained", "validity", derived.get("drained"), True,
                   bool(derived.get("drained")))
    else:
        checks.add("backlog_drained", "info", derived.get("drained"), "not required", True)
```

Update the call site in `main()`:

```python
    check_validity(checks, meta, dump, summary, conf.get("validity", {}), limits)
```

`limits` is already built two lines above from `defaults` merged with the per-scenario block, so no reordering is needed. `Checks.failed()` inspects only `"validity"` and `"slo"`, so the `"info"` kind can never change a verdict.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `python3 -m unittest discover -s scripts/tests -t . -v`

Expected: PASS, 28 tests (22 pre-existing + 6 new). Confirm the 22 pre-existing still pass — a signature change is exactly the edit that breaks a neighbouring caller.

- [ ] **Step 7: Re-check against an archived run**

```bash
python3 k6/bench/evaluate.py \
  --run-dir bench-results/TO-3_steady_20260806T001945Z \
  --thresholds k6/bench/thresholds.json
```

Expected: a verdict, no traceback, and `backlog_drained` still enforced (that run is a `steady`, which does not opt out).

- [ ] **Step 8: Commit**

```bash
git add k6/lib/profiles.js k6/bench/thresholds.json k6/bench/evaluate.py scripts/tests/test_evaluate.py
git commit -m "feat(bench): stress scenario, with an opt-out on the backlog-drained gate

Overload runs need a scenario of their own. Reusing steady would have meant one
thresholds block serving both a below-knee measurement and a deliberate
saturation, and evaluate.py hard-coded backlog_drained as a validity gate -- so
every stress run would have reported INVALID on the very backlog it exists to
produce.

require_backlog_drained is honoured for stress only; a test asserts no other
scenario opts out, because a stray false there would quietly turn broken
measurements into reportable ones."
```

---

## Task 3: `RUN_LABEL` in `bench.sh`

Work on `TO-3`, on top of Task 2.

**Files:**
- Modify: `k6/bench/bench.sh` (the `TS=` / `RUN_NAME=` pair, near line 31)
- Test: `scripts/tests/test_bench_sh.py` (create)

**Interfaces:**
- Consumes: nothing from Task 2.
- Produces: `RUN_LABEL` env var. When set, run directories become `<variant>_<scenario>_<label>_<timestamp>`; unset, the existing `<variant>_<scenario>_<timestamp>` is unchanged. The sanitisation block is delimited by the literal marker comments `# >>> run-label` and `# <<< run-label`, which the test extracts and executes — **do not remove or reword those markers.**

- [ ] **Step 1: Write the failing test**

Create `scripts/tests/test_bench_sh.py`:

```python
import os
import subprocess
import unittest

HERE = os.path.dirname(__file__)
BENCH_SH = os.path.join(HERE, "..", "..", "k6", "bench", "bench.sh")


def run_label_block(run_label=None):
    """Execute the real sanitisation block out of bench.sh, so this test binds to the
    shipped code rather than to a copy of it that can drift."""
    with open(BENCH_SH) as fh:
        script = fh.read()
    block = script.split("# >>> run-label")[1].split("# <<< run-label")[0]
    env = dict(os.environ)
    env.pop("RUN_LABEL", None)
    if run_label is not None:
        env["RUN_LABEL"] = run_label
    result = subprocess.run(
        ["bash", "-c", block + '\nprintf "%s" "$LABEL_PART"'],
        env=env, capture_output=True, text=True, check=True)
    return result.stdout


class RunLabel(unittest.TestCase):
    def test_absent_label_leaves_the_run_name_unchanged(self):
        self.assertEqual("", run_label_block())

    def test_empty_label_leaves_the_run_name_unchanged(self):
        self.assertEqual("", run_label_block(""))

    def test_simple_label_is_prefixed_with_an_underscore(self):
        self.assertEqual("_C11", run_label_block("C11"))

    def test_path_and_space_characters_are_replaced(self):
        """The label becomes a directory name and is interpolated into the container-side
        OUT_DIR, so a slash would split the path and a space would split the argument."""
        self.assertEqual("_C11-payload-delay", run_label_block("C11 payload/delay"))

    def test_dots_dashes_and_underscores_survive(self):
        self.assertEqual("_p1.0MiB_d25-ms", run_label_block("p1.0MiB_d25-ms"))


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `python3 -m unittest scripts.tests.test_bench_sh -v`

Expected: FAIL with `IndexError: list index out of range` — the `# >>> run-label` marker does not exist yet.

- [ ] **Step 3: Add the sanitisation block**

In `k6/bench/bench.sh`, replace:

```bash
TS="$(date -u +%Y%m%dT%H%M%SZ)"
RUN_NAME="${VARIANT}_${SCENARIO}_${TS}"
```

with:

```bash
TS="$(date -u +%Y%m%dT%H%M%SZ)"

# >>> run-label
# Optional human label, so runs differing only in PAYLOAD_BYTES / RESERVE_DELAY_MS are
# distinguishable in bench-results/ without opening meta.json. meta.json remains the source
# of truth for the config; this is navigation only.
#
# Sanitised because the label becomes a directory name AND is interpolated into the
# container-side OUT_DIR path: an unescaped slash would split the path, a space would split
# the argument. printf '%s' avoids the trailing newline that echo would feed to tr.
LABEL_PART=""
if [ -n "${RUN_LABEL:-}" ]; then
    LABEL_PART="_$(printf '%s' "$RUN_LABEL" | tr -c 'A-Za-z0-9._-' '-')"
fi
# <<< run-label

RUN_NAME="${VARIANT}_${SCENARIO}${LABEL_PART}_${TS}"
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `python3 -m unittest scripts.tests.test_bench_sh -v`

Expected: PASS, 5 tests.

- [ ] **Step 5: Run the whole Python suite**

Run: `python3 -m unittest discover -s scripts/tests -t . -v`

Expected: PASS, 33 tests.

- [ ] **Step 6: Commit**

```bash
git add k6/bench/bench.sh scripts/tests/test_bench_sh.py
git commit -m "feat(bench): optional RUN_LABEL in the run directory name

Phase 2 runs four payload x delay cells per winner on one branch and one
scenario, which under the old naming produced four directories distinguishable
only by timestamp. meta.json stays the source of truth for the config; the label
is navigation.

Sanitised rather than interpolated raw: the label becomes a directory name and
is spliced into the container-side OUT_DIR, so a slash would split the path."
```

---

## Task 4: Converge the harness onto all 7 other branches

The harness diverges on every other branch and is absent from `ES-1` and `ES-3`. Cherry-picking will not fix that — the branches have drifted in different directions. Convergence is by wholesale replacement from `TO-3`.

**Files (per target branch):**
- Replace: the entire `k6/` tree, `docker-compose.bench.yml`
- Copy: `scripts/grafana_snapshot.py`, `scripts/prom_restore.sh` (missing on `ES-1`, `ES-3`)
- Create: `bench.env` on `ES-1`, `ES-3`
- Modify: `monitoring/prometheus/prometheus.yml` on `ES-1`, `ES-3`
- Delete: `k6/reserve-load-test.js` on `ES-1`, `ES-3` (legacy); `k6/benchmark-campaign-plan.md`, `k6/campaign-prerequisites-plan.md` on `ES-4` (superseded)

**Interfaces:**
- Consumes: the canonical `TO-3` harness produced by Tasks 1-3.
- Produces: `SCENARIO=stress` and `RUN_LABEL` available on all 8 branches; the cross-branch invariant restored; `ES-1` and `ES-3` runnable by `bench.sh` for the first time.

- [ ] **Step 1: Converge the five branches that already have a harness**

```bash
for b in TO-1 TO-2 TO-4 ES-2 ES-4; do
    git checkout "$b" || break
    git rm -rq k6
    git checkout TO-3 -- k6 docker-compose.bench.yml
    git add -A k6 docker-compose.bench.yml
    git commit -q -m "chore(bench): converge k6/ onto the canonical TO-3 harness

The harness was supposed to be byte-identical on every branch and had drifted on
all seven non-TO-3 branches, so no cross-variant comparison drawn from it was
safe. Replaced wholesale rather than merged: the branches drifted in different
directions and only one of them can be canonical.

bench.env stays the sole per-branch file."
    printf '%-6s ' "$b"
    [ -z "$(git diff --stat TO-3 "$b" -- k6 docker-compose.bench.yml)" ] && echo CONVERGED || echo STILL-DIVERGED
done
```

`git rm -rq k6` before the checkout is what removes files that exist on the target but not on `TO-3` — `ES-4`'s two superseded planning docs, in particular. A bare `git checkout TO-3 -- k6` would leave them behind.

Expected: five `CONVERGED` lines.

- [ ] **Step 2: Bootstrap the harness on `ES-1` and `ES-3`**

```bash
for b in ES-1 ES-3; do
    git checkout "$b" || break
    git rm -rq k6
    git checkout TO-3 -- k6 docker-compose.bench.yml \
        scripts/grafana_snapshot.py scripts/prom_restore.sh
    git add -A k6 docker-compose.bench.yml scripts
done
```

This also removes the legacy `k6/reserve-load-test.js`, which the modern harness replaces. Do not commit yet — `bench.env` (Step 3) must land in the same commit, or the branch has a harness that refuses to start.

- [ ] **Step 3: Create `bench.env` on `ES-1` and `ES-3`**

`common.sh` exits `FATAL` without this file; it is why the harness cannot run on these two branches today. On `ES-1`, create `bench.env`:

```bash
# The ONLY per-branch file in the benchmark harness.
# Everything under k6/ and docker-compose.bench.yml must stay byte-identical on every
# branch. The acceptance test, using TO-3 as the reference:
#     git diff --stat TO-3 <branch> -- k6 docker-compose.bench.yml     # must be empty
#
# Branch: ES-1  (Axon 4.11.2, JDBC event store on PostgreSQL, full replay on every
# aggregate load — no snapshots, no cache. The ES baseline.)
#
# VARIANT is what names the run directory and the thesis-table row (bench.sh builds
# RUN_NAME from it, NOT from the git branch), so a stale value here silently files this
# branch's results under another variant.

VARIANT=ES-1
VARIANT_FAMILY=ES

# docker compose service names (differ between the TO and ES families)
API_SVC=api-es
DB_SVC=postgres-es

# Prometheus job label for this branch's API target, and the cadvisor container-name regex.
# The regex must stay UNANCHORED: the API service is scaled with deploy.replicas and so
# carries no container_name, which means cadvisor sees `<project>-api-es-1`, `-2`, ...
# rather than a bare `api-es`. queries.promql matches it with an anchored name=~"$CRE".
#
# EXPECTED_REPLICAS is NOT set here. common.sh derives it from REPLICAS in .env, which is
# the file docker compose actually acts on — one knob, not two.
PROM_JOB=inventory-es
API_CONTAINER_RE=.*api-es.*

DB_NAME=inventory
DB_USER=inventory

# Health endpoint on the published port, which on every branch is nginx in front of the
# api replicas. At REPLICAS>1 a healthy response only proves that ONE replica answered —
# reset.sh asserts the full container count separately.
HEALTH_URL=http://localhost:8080/actuator/health

# Image bench.sh rebuilds and tags before each run.
IMAGE_TAG=inventory-reservation-es:latest
```

On `ES-3`, create the same file with two changes only:

```bash
VARIANT=ES-3
```

and the branch comment:

```bash
# Branch: ES-3  (Axon 4.11.2, snapshots plus a strong-reference aggregate cache —
# WeakReferenceCache would let GC eviction reset the snapshot counter)
```

Every other line is identical. Verify against `ES-2`'s copy: `git show ES-2:bench.env` — only `VARIANT` and the branch comment should differ.

- [ ] **Step 4: Port the Prometheus DNS-discovery config to `ES-1` and `ES-3`**

`ES-1` and `ES-3` still use a static Prometheus target. `queries.promql` and the `targets_scraped` validity check assume DNS discovery. In `monitoring/prometheus/prometheus.yml`, replace the `inventory-es` job's `static_configs` block with:

```yaml
  # DNS discovery, not a static target: the api-es service is scaled with deploy.replicas,
  # so the number of endpoints behind the name varies. At REPLICAS=1 this resolves to
  # exactly one target, identical to the static config it replaces.
  - job_name: inventory-es
    metrics_path: /actuator/prometheus
    dns_sd_configs:
      - names: ['api-es']
        type: A
        port: 8080
```

Confirm the result matches `ES-2`: `git diff ES-2 -- monitoring/prometheus/prometheus.yml` must be empty.

Editing this file is not enough at runtime — it is bind-mounted, so a running Prometheus keeps the old config and `/-/reload` reports success without picking it up. Any container started before this change must be recreated with `docker compose up -d --force-recreate prometheus`.

- [ ] **Step 5: Commit `ES-1` and `ES-3`**

```bash
for b in ES-1 ES-3; do
    git checkout "$b"
    git add -A k6 docker-compose.bench.yml scripts bench.env monitoring/prometheus/prometheus.yml
    git commit -q -m "feat(bench): bring the benchmark harness to this branch

This branch still had the legacy k6/reserve-load-test.js and none of the modern
harness: no k6/bench/, no k6/lib/, no docker-compose.bench.yml, and no bench.env,
without which common.sh exits FATAL. It could not be benchmarked at all, which
makes it unusable as one of the campaign's eight subjects.

The application instrumentation the harness queries -- order_e2e_time and
order_projection_lag_seconds -- was already present, so this is files only.

Prometheus moves to DNS discovery for api-es to match the other ES branches;
queries.promql and the targets_scraped validity check assume it."
done
```

- [ ] **Step 6: Verify the invariant across all 8 branches**

```bash
git checkout TO-3
for b in TO-1 TO-2 TO-4 ES-1 ES-2 ES-3 ES-4; do
    printf '%-6s ' "$b"
    if [ -z "$(git diff --stat TO-3 "$b" -- k6 docker-compose.bench.yml)" ]; then
        echo OK
    else
        echo DIVERGED
        git diff --stat TO-3 "$b" -- k6 docker-compose.bench.yml
    fi
done
```

Expected: `OK` on all seven lines. Any `DIVERGED` must be fixed before proceeding — a harness that differs between branches makes every cross-variant comparison in the campaign indefensible.

- [ ] **Step 7: Verify `bench.env` exists and is well-formed everywhere**

```bash
for b in TO-1 TO-2 TO-3 TO-4 ES-1 ES-2 ES-3 ES-4; do
    printf '%-6s ' "$b"
    git show "$b:bench.env" 2>/dev/null | grep -E '^(VARIANT|API_SVC|DB_SVC|PROM_JOB)=' | tr '\n' ' '
    echo
done
```

Expected: eight lines, each with `VARIANT=` matching its branch name, and `API_SVC`/`DB_SVC`/`PROM_JOB` matching the family (`api-to`/`postgres-to`/`inventory-to` for TO, `api-es`/`postgres-es`/`inventory-es` for ES). A `VARIANT` that does not match its branch silently files that branch's results under another variant.

- [ ] **Step 8: Smoke-test the harness on `ES-1`**

```bash
git checkout ES-1
docker compose down -v
export JAVA_HOME=$HOME/.jdks/corretto-21.0.10
SCENARIO=steady RATE=10 DURATION=60s WARMUP_ITERATIONS=200 \
  DISTINCT_ITEMS=6 ITEMS_PER_ORDER=4 RUN_LABEL=smoke \
  ./k6/bench/bench.sh
```

Expected: the run completes and writes `bench-results/ES-1_steady_smoke_<timestamp>/` containing `meta.json`, `dump.json`, `verdict.json`. The verdict may be anything — this checks that the harness *runs* on a branch that never had it, and that `RUN_LABEL` reaches the directory name. `INVALID` on `image_fresh` is acceptable here and is a known Docker-cache false negative; `FATAL: bench.env not found` or a `targets_scraped` failure is not.

Tear down afterwards: `docker compose down -v`.

---

## Task 5: `additional_bytes` on `TO-3`

`TO-3` already has `reserveDelayMs`, so it needs only the payload column. Doing it here first gives Task 6 a verified pattern.

**Files:**
- Create: `src/main/resources/db/migration/V6__additional_bytes.sql`
- Modify: `src/main/kotlin/pl/szymanski/wiktor/domain/InventoryItem.kt`
- Test: `src/test/kotlin/pl/szymanski/wiktor/domain/InventoryItemBenchKnobsTest.kt` (create)

**Interfaces:**
- Consumes: nothing from Tasks 1-4.
- Produces: `InventoryItem` gains `val additionalBytes: String = ""` between `reserveDelayMs` and `version`. `InventoryItem.create(id, availableQty, correlationId, clock, additionalBytesSize, reserveDelayMs)` keeps its signature and now populates that field. `inventory_state` gains `additional_bytes TEXT NOT NULL DEFAULT ''`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/pl/szymanski/wiktor/domain/InventoryItemBenchKnobsTest.kt`:

```kotlin
package pl.szymanski.wiktor.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.szymanski.wiktor.exception.InsufficientStockException
import java.time.Clock
import java.util.UUID

/**
 * The two benchmark levers. Both must be provable no-ops at 0: phase 1 of the load-test
 * campaign measures every variant with both at 0 and compares those numbers against
 * phase-2 runs on the same binaries.
 */
class InventoryItemBenchKnobsTest {

    private val clock: Clock = Clock.systemUTC()

    private fun item(payload: Int = 0, delayMs: Int = 0, qty: Int = 10) =
        InventoryItem.create("ITEM-1", qty, UUID.randomUUID(), clock, payload, delayMs)

    @Test
    fun `padding is stored on the item row, not only on the creation event`() {
        val (created, event) = item(payload = 1024)
        assertEquals(1024, created.additionalBytes.length)
        assertEquals(1024, event.additionalBytes.length)
    }

    @Test
    fun `padding survives a reserve, so every read-modify-write carries it`() {
        val (created, _) = item(payload = 512)
        val result = created.reserve("RES-1", 1, UUID.randomUUID(), clock)
        assertEquals(512, result.updatedItem.additionalBytes.length)
    }

    @Test
    fun `zero padding stores an empty string`() {
        val (created, event) = item(payload = 0)
        assertEquals("", created.additionalBytes)
        assertEquals("", event.additionalBytes)
    }

    @Test
    fun `a successful reserve sleeps for the configured delay`() {
        val (created, _) = item(delayMs = 50)
        val startedNs = System.nanoTime()
        created.reserve("RES-1", 1, UUID.randomUUID(), clock)
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        assertTrue(elapsedMs >= 45, "expected at least 45ms, slept ${elapsedMs}ms")
    }

    @Test
    fun `a zero delay does not sleep`() {
        val (created, _) = item(delayMs = 0)
        val startedNs = System.nanoTime()
        created.reserve("RES-1", 1, UUID.randomUUID(), clock)
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        assertTrue(elapsedMs < 20, "expected no sleep, took ${elapsedMs}ms")
    }

    @Test
    fun `a rejected reserve does not sleep`() {
        // The delay models expensive domain logic, reached only once the reserve is known to
        // succeed. Paying it on the out-of-stock path would make the rejection rate a hidden
        // throughput lever.
        val (created, _) = item(delayMs = 200, qty = 1)
        val startedNs = System.nanoTime()
        assertThrows<InsufficientStockException> {
            created.reserve("RES-1", 5, UUID.randomUUID(), clock)
        }
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        assertTrue(elapsedMs < 20, "expected no sleep on rejection, took ${elapsedMs}ms")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=$HOME/.jdks/corretto-21.0.10 ./gradlew test --tests '*InventoryItemBenchKnobsTest*'`

Expected: compilation FAILS with `unresolved reference: additionalBytes`. The three delay tests would pass — `TO-3` already implements that half — but the file cannot compile until the payload field exists.

- [ ] **Step 3: Create the migration**

Create `src/main/resources/db/migration/V6__additional_bytes.sql`:

```sql
-- Benchmark lever: per-item padding stored ON THE ROW, so every reserve's read-modify-write
-- carries it.
--
-- Without this column the k6 PAYLOAD_BYTES knob rides only on the seed-time
-- InventoryCreatedEvent and never touches TO's reserve path, while on ES the same bytes sit
-- on the aggregate and are rehydrated on every load, written into every snapshot, and
-- deep-copied per command. The sweep would then measure ES only, and TO's flat line would
-- read as robustness rather than as absence.
--
-- A 1 MiB value is TOASTed, so the main-heap row stays narrow and the bytes are rewritten
-- out-of-line on every update. That cost -- WAL volume and bloat -- is the measurement.
--
-- Version 6 rather than the next free number, so the filename is identical on all four TO
-- branches: TO-2 already uses V2 for its NOTIFY trigger and TO-3 for reserve_delay.
ALTER TABLE inventory_state
    ADD COLUMN additional_bytes TEXT NOT NULL DEFAULT '';
```

- [ ] **Step 4: Add the field**

In `src/main/kotlin/pl/szymanski/wiktor/domain/InventoryItem.kt`, change the data-class header to:

```kotlin
@Table("inventory_state")
data class InventoryItem(
    @Id
    @Column("item_id")
    val id: String,
    val availableQty: Int,
    // Artificial per-reserve cost, in milliseconds, fixed at creation. Stands in for expensive
    // aggregate logic (pricing, eligibility, allocation) so the variants can be compared under
    // something more than a subtraction. Mirrors the ES branch's aggregate field of the same name.
    val reserveDelayMs: Int = 0,
    // Benchmark padding, stored on the row rather than only on the creation event, so every
    // reserve's read-modify-write carries it. The TO counterpart to ES rehydrating and
    // snapshotting the same bytes on every aggregate load.
    val additionalBytes: String = "",
    @Version
    val version: Long = 0L,
) {
```

and the `create` companion's return to:

```kotlin
            return Pair(
                InventoryItem(
                    id = id,
                    availableQty = availableQty,
                    reserveDelayMs = reserveDelayMs,
                    additionalBytes = additionalBytes,
                ),
                InventoryCreatedEvent(id, availableQty, correlationId, clock.instant(), additionalBytes, reserveDelayMs)
            )
```

Leave `reserve()` untouched: it returns `copy(availableQty = availableQty - quantity)`, and a data-class `copy` carries `additionalBytes` across unchanged. The Step 1 test pins that, so a later rewrite to an explicit constructor call cannot silently drop the padding.

- [ ] **Step 5: Run the test to verify it passes**

Run: `JAVA_HOME=$HOME/.jdks/corretto-21.0.10 ./gradlew test --tests '*InventoryItemBenchKnobsTest*'`

Expected: PASS, 6 tests.

- [ ] **Step 6: Run the whole suite**

Run: `JAVA_HOME=$HOME/.jdks/corretto-21.0.10 ./gradlew test`

Expected: PASS. `InventoryServiceRetryTest` and `ApplicationTest` must still pass; `ApplicationTest` boots the context and exercises the new migration.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V6__additional_bytes.sql \
        src/main/kotlin/pl/szymanski/wiktor/domain/InventoryItem.kt \
        src/test/kotlin/pl/szymanski/wiktor/domain/InventoryItemBenchKnobsTest.kt
git commit -m "feat(bench): store additionalBytes on the inventory_state row

PAYLOAD_BYTES was an ES-only lever. On ES the bytes live on the aggregate and are
rehydrated per load, snapshotted, and deep-copied per command; on TO they rode
once on the seed-time InventoryCreatedEvent into the outbox and never touched the
reserve path, because inventory_state had no column for them.

Run unchanged, the campaign's payload sweep would have produced a flat TO line
that reads as robustness rather than as absence. With the column, a 1 MiB value
is TOASTed and rewritten by every reserve -- which is the cost being measured."
```

---

## Task 6: Both knobs on `TO-1`, `TO-2`, `TO-4`

Both land in one task per branch, so no branch is ever left half-instrumented. Repeat every step once per branch, in the order `TO-1`, `TO-2`, `TO-4`.

**Files (per branch):**
- Create: `src/main/resources/db/migration/V5__reserve_delay.sql`, `src/main/resources/db/migration/V6__additional_bytes.sql`
- Modify: `src/main/kotlin/pl/szymanski/wiktor/domain/InventoryItem.kt`, `src/main/kotlin/pl/szymanski/wiktor/domain/events.kt`, `src/main/kotlin/pl/szymanski/wiktor/service/command/CreateItemCommandHandler.kt`, `src/main/kotlin/pl/szymanski/wiktor/controller/InventoryController.kt`
- Test: `src/test/kotlin/pl/szymanski/wiktor/domain/InventoryItemBenchKnobsTest.kt` (create)

**Interfaces:**
- Consumes: nothing — all code needed is inline below.
- Produces, on each branch, signatures byte-identical to `TO-3`'s: `CreateItemRequest(id, availableQty, additionalBytesSize = 0, reserveDelayMs = 0)`; `CreateItemCommand(id, availableQty, correlationId, additionalBytesSize = 0, reserveDelayMs = 0)`; `InventoryCreatedEvent(id, quantity, correlationId, createdAt, additionalBytes = "", reserveDelayMs = 0)`; `InventoryItem(id, availableQty, reserveDelayMs = 0, additionalBytes = "", version = 0L)`; `InventoryItem.create(id, availableQty, correlationId, clock, additionalBytesSize = 0, reserveDelayMs = 0)`.

- [ ] **Step 1: Check out the branch and write the failing test**

```bash
git checkout TO-1     # then TO-2, then TO-4
```

Create `src/test/kotlin/pl/szymanski/wiktor/domain/InventoryItemBenchKnobsTest.kt`:

```kotlin
package pl.szymanski.wiktor.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.szymanski.wiktor.exception.InsufficientStockException
import java.time.Clock
import java.util.UUID

/**
 * The two benchmark levers. Both must be provable no-ops at 0: phase 1 of the load-test
 * campaign measures every variant with both at 0 and compares those numbers against
 * phase-2 runs on the same binaries.
 */
class InventoryItemBenchKnobsTest {

    private val clock: Clock = Clock.systemUTC()

    private fun item(payload: Int = 0, delayMs: Int = 0, qty: Int = 10) =
        InventoryItem.create("ITEM-1", qty, UUID.randomUUID(), clock, payload, delayMs)

    @Test
    fun `padding is stored on the item row, not only on the creation event`() {
        val (created, event) = item(payload = 1024)
        assertEquals(1024, created.additionalBytes.length)
        assertEquals(1024, event.additionalBytes.length)
    }

    @Test
    fun `padding survives a reserve, so every read-modify-write carries it`() {
        val (created, _) = item(payload = 512)
        val result = created.reserve("RES-1", 1, UUID.randomUUID(), clock)
        assertEquals(512, result.updatedItem.additionalBytes.length)
    }

    @Test
    fun `zero padding stores an empty string`() {
        val (created, event) = item(payload = 0)
        assertEquals("", created.additionalBytes)
        assertEquals("", event.additionalBytes)
    }

    @Test
    fun `a successful reserve sleeps for the configured delay`() {
        val (created, _) = item(delayMs = 50)
        val startedNs = System.nanoTime()
        created.reserve("RES-1", 1, UUID.randomUUID(), clock)
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        assertTrue(elapsedMs >= 45, "expected at least 45ms, slept ${elapsedMs}ms")
    }

    @Test
    fun `a zero delay does not sleep`() {
        val (created, _) = item(delayMs = 0)
        val startedNs = System.nanoTime()
        created.reserve("RES-1", 1, UUID.randomUUID(), clock)
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        assertTrue(elapsedMs < 20, "expected no sleep, took ${elapsedMs}ms")
    }

    @Test
    fun `a rejected reserve does not sleep`() {
        // The delay models expensive domain logic, reached only once the reserve is known to
        // succeed. Paying it on the out-of-stock path would make the rejection rate a hidden
        // throughput lever.
        val (created, _) = item(delayMs = 200, qty = 1)
        val startedNs = System.nanoTime()
        assertThrows<InsufficientStockException> {
            created.reserve("RES-1", 5, UUID.randomUUID(), clock)
        }
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        assertTrue(elapsedMs < 20, "expected no sleep on rejection, took ${elapsedMs}ms")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=$HOME/.jdks/corretto-21.0.10 ./gradlew test --tests '*InventoryItemBenchKnobsTest*'`

Expected: compilation FAILS — `InventoryItem.create` takes 5 parameters, not 6, and neither `additionalBytes` nor `reserveDelayMs` exists on `InventoryItem`.

- [ ] **Step 3: Create `V5__reserve_delay.sql`**

```sql
-- Per-item artificial reservation cost. Set at item creation, read on every reserve, and
-- slept through inside the order transaction — the counterpart to additionalBytesSize, which
-- inflates payload size but not the time a reserve takes. It exists so the TO-vs-ES comparison
-- can be run against expensive aggregate logic, not only the near-free arithmetic reserve does today.
--
-- Version 5, not 2: TO-2 already uses V2 for its NOTIFY trigger and TO-3 for this same
-- column. A uniform version for the new file across TO-1/TO-2/TO-4 keeps review sane.
ALTER TABLE inventory_state
    ADD COLUMN reserve_delay_ms INT NOT NULL DEFAULT 0 CHECK (reserve_delay_ms >= 0);
```

- [ ] **Step 4: Create `V6__additional_bytes.sql`**

```sql
-- Benchmark lever: per-item padding stored ON THE ROW, so every reserve's read-modify-write
-- carries it.
--
-- Without this column the k6 PAYLOAD_BYTES knob rides only on the seed-time
-- InventoryCreatedEvent and never touches TO's reserve path, while on ES the same bytes sit
-- on the aggregate and are rehydrated on every load, written into every snapshot, and
-- deep-copied per command. The sweep would then measure ES only, and TO's flat line would
-- read as robustness rather than as absence.
--
-- A 1 MiB value is TOASTed, so the main-heap row stays narrow and the bytes are rewritten
-- out-of-line on every update. That cost -- WAL volume and bloat -- is the measurement.
--
-- Version 6 rather than the next free number, so the filename is identical on all four TO
-- branches: TO-2 already uses V2 for its NOTIFY trigger and TO-3 for reserve_delay.
ALTER TABLE inventory_state
    ADD COLUMN additional_bytes TEXT NOT NULL DEFAULT '';
```

- [ ] **Step 5: Add both fields and the sleep to `InventoryItem`**

Replace the data-class header in `src/main/kotlin/pl/szymanski/wiktor/domain/InventoryItem.kt`:

```kotlin
@Table("inventory_state")
data class InventoryItem(
    @Id
    @Column("item_id")
    val id: String,
    val availableQty: Int,
    // Artificial per-reserve cost, in milliseconds, fixed at creation. Stands in for expensive
    // aggregate logic (pricing, eligibility, allocation) so the variants can be compared under
    // something more than a subtraction. Mirrors the ES branch's aggregate field of the same name.
    val reserveDelayMs: Int = 0,
    // Benchmark padding, stored on the row rather than only on the creation event, so every
    // reserve's read-modify-write carries it. The TO counterpart to ES rehydrating and
    // snapshotting the same bytes on every aggregate load.
    val additionalBytes: String = "",
    @Version
    val version: Long = 0L,
) {
```

In `reserve()`, insert between the `InsufficientStockException` block and the `return ReserveResult(...)`:

```kotlin
        // Paid only once the reserve is known to succeed, and inside the caller's transaction:
        // the inventory_state row lock is already held and stays held for the duration. That is the
        // point — it is what makes this a model of slow aggregate logic rather than of slow IO.
        if (reserveDelayMs > 0) {
            Thread.sleep(reserveDelayMs.toLong())
        }

```

Replace the whole `companion object`:

```kotlin
    companion object {
        fun create(
            id: String,
            availableQty: Int,
            correlationId: UUID,
            clock: Clock,
            additionalBytesSize: Int = 0,
            reserveDelayMs: Int = 0,
        ): Pair<InventoryItem, InventoryCreatedEvent> {
            val additionalBytes = if (additionalBytesSize > 0) "x".repeat(additionalBytesSize) else ""
            return Pair(
                InventoryItem(
                    id = id,
                    availableQty = availableQty,
                    reserveDelayMs = reserveDelayMs,
                    additionalBytes = additionalBytes,
                ),
                InventoryCreatedEvent(id, availableQty, correlationId, clock.instant(), additionalBytes, reserveDelayMs)
            )
        }
    }
```

- [ ] **Step 6: Add `reserveDelayMs` to `InventoryCreatedEvent`**

In `src/main/kotlin/pl/szymanski/wiktor/domain/events.kt`, replace `InventoryCreatedEvent` with:

```kotlin
data class InventoryCreatedEvent(
    val id: String,
    val quantity: Int,
    val correlationId: UUID,
    val createdAt: Instant,
    // Filler to inflate the serialized event payload for benchmarking; mirrors the ES branch's
    // additionalBytes so TO and ES can be load-tested at equal payload sizes.
    val additionalBytes: String = "",
    // Artificial per-reserve cost the item was created with. Carried on the event so the outbox
    // record describes the item fully, and so the payload matches the ES branch's event.
    val reserveDelayMs: Int = 0,
)
```

Leave every other event in the file untouched.

- [ ] **Step 7: Thread the knob through the command handler**

In `src/main/kotlin/pl/szymanski/wiktor/service/command/CreateItemCommandHandler.kt`, replace the command:

```kotlin
data class CreateItemCommand(
    val id: String,
    val availableQty: Int,
    val correlationId: UUID = UUID.randomUUID(),
    val additionalBytesSize: Int = 0,
    val reserveDelayMs: Int = 0,
)
```

and inside `handle`, replace the log line and the `create` call:

```kotlin
        log.info("[CREATE] itemId={} availableQty={} additionalBytesSize={} reserveDelayMs={} correlationId={}", command.id, command.availableQty, command.additionalBytesSize, command.reserveDelayMs, command.correlationId)
        val (item, event) = InventoryItem.create(
            command.id, command.availableQty, command.correlationId, clock, command.additionalBytesSize, command.reserveDelayMs
        )
```

- [ ] **Step 8: Thread the knob through the controller**

In `src/main/kotlin/pl/szymanski/wiktor/controller/InventoryController.kt`, replace the request DTO:

```kotlin
data class CreateItemRequest(val id: String, val availableQty: Int, val additionalBytesSize: Int = 0, val reserveDelayMs: Int = 0)
```

and the body of `createItem`:

```kotlin
    @PostMapping
    fun createItem(@RequestBody request: CreateItemRequest): ResponseEntity<CreateItemResponse> {
        log.info("POST /inventory itemId={} availableQty={} additionalBytesSize={} reserveDelayMs={}", request.id, request.availableQty, request.additionalBytesSize, request.reserveDelayMs)
        val item = inventoryService.createItem(
            CreateItemCommand(
                id = request.id,
                availableQty = request.availableQty,
                correlationId = UUID.randomUUID(),
                additionalBytesSize = request.additionalBytesSize,
                reserveDelayMs = request.reserveDelayMs,
            )
        )
        log.info("POST /inventory success itemId={}", item.id)
        return ResponseEntity.status(HttpStatus.CREATED).body(CreateItemResponse(item.id, item.availableQty))
    }
```

This is the change that closes the silent-no-op trap: until `CreateItemRequest` carries the field, Spring Boot drops `reserveDelayMs` from the JSON body without error.

- [ ] **Step 9: Run the tests**

Run: `JAVA_HOME=$HOME/.jdks/corretto-21.0.10 ./gradlew test`

Expected: PASS, including the 6 new cases.

On `TO-4` specifically, watch `InventoryServiceRetryTest`: `TO-4` carries a Caffeine write-path cache whose post-commit version-guarded merge holds `InventoryItem` instances. Adding two fields with defaults is source-compatible, and cached copies now carry the padding — which is correct, and part of what the payload sweep measures on that variant.

- [ ] **Step 10: Commit**

```bash
git add src/main/resources/db/migration/V5__reserve_delay.sql \
        src/main/resources/db/migration/V6__additional_bytes.sql \
        src/main/kotlin/pl/szymanski/wiktor/domain/InventoryItem.kt \
        src/main/kotlin/pl/szymanski/wiktor/domain/events.kt \
        src/main/kotlin/pl/szymanski/wiktor/service/command/CreateItemCommandHandler.kt \
        src/main/kotlin/pl/szymanski/wiktor/controller/InventoryController.kt \
        src/test/kotlin/pl/szymanski/wiktor/domain/InventoryItemBenchKnobsTest.kt
git commit -m "feat(bench): honour reserveDelayMs and additionalBytes on the reserve path

Both knobs existed in the k6 harness and neither reached this branch. Spring Boot
drops unknown JSON properties by default, so RESERVE_DELAY_MS=25 was accepted,
ignored, and left nothing in the artifacts to reveal it -- a run that looks clean
and measures the wrong thing.

The sleep sits after the stock check and inside the held row lock, so it models
slow domain logic rather than slow IO, and a rejected reserve never pays it.
Signatures match TO-3 exactly."
```

- [ ] **Step 11: Repeat for the next branch, then verify family agreement**

After `TO-1`, repeat Steps 1-10 for `TO-2`, then `TO-4`. Then:

```bash
for b in TO-1 TO-2 TO-4; do
    printf '%-6s' "$b"
    git diff --stat TO-3 "$b" -- \
        src/main/kotlin/pl/szymanski/wiktor/domain/InventoryItem.kt \
        src/main/kotlin/pl/szymanski/wiktor/domain/events.kt
    echo
done
```

Expected: no diff on either file for any branch. Those two files should now be identical across the TO family — the variants differ in their publishing machinery, not in the aggregate.

---

## Task 7: `reserveDelayMs` on `ES-1`, `ES-2`, `ES-3`

`ES-4` already implements this and is the reference. No migration: ES aggregate state is rebuilt from events, not stored in a table. Repeat every step once per branch, in the order `ES-1`, `ES-2`, `ES-3`.

**Files (per branch):**
- Modify: `src/main/kotlin/pl/szymanski/wiktor/domain/InventoryItem.kt`, `src/main/kotlin/pl/szymanski/wiktor/domain/events.kt`, `src/main/kotlin/pl/szymanski/wiktor/service/command/CreateItemCommandHandler.kt`, `src/main/kotlin/pl/szymanski/wiktor/controller/InventoryController.kt`
- Test: `src/test/kotlin/pl/szymanski/wiktor/domain/InventoryItemReserveDelayTest.kt` (create)

**Interfaces:**
- Consumes: nothing — all code needed is inline below.
- Produces, on each branch: `CreateItemRequest(id, availableQty, additionalBytesSize = 0, reserveDelayMs = 0)`; `CreateItemCommand(id, availableQty, additionalBytesSize = 0, reserveDelayMs = 0, correlationId = UUID.randomUUID())` — `reserveDelayMs` sits **before** `correlationId`, matching `ES-4`; `InventoryCreatedEvent(id, correlationId, quantity, additionalBytes = "", reserveDelayMs = 0)`.

- [ ] **Step 1: Check out the branch and write the failing test**

```bash
git checkout ES-1     # then ES-2, then ES-3
```

Create `src/test/kotlin/pl/szymanski/wiktor/domain/InventoryItemReserveDelayTest.kt`:

```kotlin
package pl.szymanski.wiktor.domain

import org.axonframework.test.aggregate.AggregateTestFixture
import org.axonframework.test.aggregate.FixtureConfiguration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.szymanski.wiktor.service.command.CreateItemCommand
import pl.szymanski.wiktor.service.command.SagaReserveItemCommand
import java.util.UUID

/**
 * The reserve-delay lever. It must be a provable no-op at 0: phase 1 of the load-test
 * campaign measures every variant at 0 and compares those numbers against phase-2 runs on
 * the same binaries.
 */
class InventoryItemReserveDelayTest {

    private lateinit var fixture: FixtureConfiguration<InventoryItem>
    private val itemId = "ITEM-1"
    private val correlationId: UUID = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        fixture = AggregateTestFixture(InventoryItem::class.java)
    }

    @Test
    fun `the create command carries the delay onto the created event`() {
        fixture.givenNoPriorActivity()
            .`when`(
                CreateItemCommand(
                    id = itemId,
                    availableQty = 10,
                    additionalBytesSize = 0,
                    reserveDelayMs = 25,
                    correlationId = correlationId,
                )
            )
            .expectEvents(InventoryCreatedEvent(itemId, correlationId, 10, "", 25))
    }

    @Test
    fun `a successful reserve sleeps for the configured delay`() {
        val startedNs = System.nanoTime()
        fixture.given(InventoryCreatedEvent(itemId, correlationId, 10, "", 50))
            .`when`(SagaReserveItemCommand(itemId, 1, correlationId))
            .expectEvents(InventoryReservedEvent(itemId, correlationId, 1))
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        assertTrue(elapsedMs >= 45, "expected at least 45ms, took ${elapsedMs}ms")
    }

    @Test
    fun `a zero delay does not sleep`() {
        val startedNs = System.nanoTime()
        fixture.given(InventoryCreatedEvent(itemId, correlationId, 10, "", 0))
            .`when`(SagaReserveItemCommand(itemId, 1, correlationId))
            .expectEvents(InventoryReservedEvent(itemId, correlationId, 1))
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        assertTrue(elapsedMs < 30, "expected no sleep, took ${elapsedMs}ms")
    }

    @Test
    fun `a rejected reserve does not sleep`() {
        // The delay models expensive domain logic, reached only once the reserve is known to
        // succeed. Paying it on the out-of-stock path would make the rejection rate a hidden
        // throughput lever.
        val startedNs = System.nanoTime()
        fixture.given(InventoryCreatedEvent(itemId, correlationId, 1, "", 200))
            .`when`(SagaReserveItemCommand(itemId, 5, correlationId))
            .expectEvents(
                InventoryReservationFailedEvent(
                    itemId, correlationId, "Insufficient stock: available=1 requested=5")
            )
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        assertTrue(elapsedMs < 30, "expected no sleep on rejection, took ${elapsedMs}ms")
    }

    @Test
    fun `only the command pays the delay, never the replayed events`() {
        // The sleep must live in the @CommandHandler, never the @EventSourcingHandler: in the
        // latter it is paid on every replay and snapshot load, turning a per-reserve cost into
        // a startup cost and corrupting the whole comparison. Four events, one command: the
        // run must cost one sleep, not four.
        val startedNs = System.nanoTime()
        fixture.given(
            InventoryCreatedEvent(itemId, correlationId, 100, "", 200),
            InventoryReservedEvent(itemId, correlationId, 1),
            InventoryReservedEvent(itemId, correlationId, 1),
            InventoryReservedEvent(itemId, correlationId, 1),
        )
            .`when`(SagaReserveItemCommand(itemId, 1, correlationId))
            .expectEvents(InventoryReservedEvent(itemId, correlationId, 1))
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        assertTrue(elapsedMs >= 195, "the command did not pay the delay: took ${elapsedMs}ms")
        assertTrue(elapsedMs < 500, "replay paid the delay too: took ${elapsedMs}ms")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=$HOME/.jdks/corretto-21.0.10 ./gradlew test --tests '*InventoryItemReserveDelayTest*'`

Expected: compilation FAILS — `CreateItemCommand` has no `reserveDelayMs` parameter, and `InventoryCreatedEvent` takes 4 arguments, not 5.

- [ ] **Step 3: Add `reserveDelayMs` to `InventoryCreatedEvent`**

In `src/main/kotlin/pl/szymanski/wiktor/domain/events.kt`:

```kotlin
data class InventoryCreatedEvent(
    override val id: String,
    override val correlationId: UUID,
    val quantity: Int,
    val additionalBytes: String = "",
    // Artificial per-reserve cost the item was created with, replayed onto the aggregate so
    // the cost survives snapshot loads and replays. Mirrors the TO branch's field.
    val reserveDelayMs: Int = 0,
) : InventoryEvent(id, correlationId)
```

- [ ] **Step 4: Add the field and the sleep to the aggregate**

In `src/main/kotlin/pl/szymanski/wiktor/domain/InventoryItem.kt`, add the field after `additionalBytes`:

```kotlin
    private var additionalBytes: String = ""

    // Artificial per-reserve cost, in milliseconds, fixed at creation. Stands in for expensive
    // aggregate logic (pricing, eligibility, allocation) so the variants can be compared under
    // something more than a subtraction. The counterpart to additionalBytes, which inflates the
    // payload but not the time a reserve takes. Mirrors the TO branch's field of the same name.
    private var reserveDelayMs: Int = 0
```

Extend the creation command handler:

```kotlin
    @CommandHandler
    constructor(command: CreateItemCommand) {
        val additionalBytes = if (command.additionalBytesSize > 0) "x".repeat(command.additionalBytesSize) else ""
        AggregateLifecycle.apply(
            InventoryCreatedEvent(command.id, command.correlationId, command.availableQty, additionalBytes, command.reserveDelayMs)
        )
    }
```

Extend the creation event-sourcing handler:

```kotlin
    @EventSourcingHandler
    fun on(event: InventoryCreatedEvent) {
        id = event.id
        availableQty = event.quantity
        additionalBytes = event.additionalBytes
        reserveDelayMs = event.reserveDelayMs
    }
```

Add the sleep to the reserve command handler, **after** the stock check and **before** `apply`:

```kotlin
    @CommandHandler
    fun handle(command: SagaReserveItemCommand) {
        if (command.quantity > availableQty) {
            AggregateLifecycle.apply(
                InventoryReservationFailedEvent(id, command.correlationId, "Insufficient stock: available=$availableQty requested=${command.quantity}")
            )
            return
        }

        // Deliberately in the COMMAND handler rather than the event-sourcing handler: the
        // latter runs on every replay and snapshot load, which would make the delay a startup
        // cost instead of a per-reserve one. The aggregate's lock is held throughout — that is
        // the point, it models slow domain logic.
        if (reserveDelayMs > 0) {
            Thread.sleep(reserveDelayMs.toLong())
        }

        AggregateLifecycle.apply(InventoryReservedEvent(id, command.correlationId, command.quantity))
    }
```

On `ES-2` and `ES-3` the aggregate carries `@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)` for Jackson snapshots. **Leave that annotation exactly as it is** — it is what makes the new private `reserveDelayMs` field serialize into snapshots. Without it the field would silently reset to 0 on every snapshot load and the delay would evaporate part-way through a run.

- [ ] **Step 5: Add the knob to the command**

In `src/main/kotlin/pl/szymanski/wiktor/service/command/CreateItemCommandHandler.kt`:

```kotlin
data class CreateItemCommand(
    @TargetAggregateIdentifier val id: String,
    val availableQty: Int,
    val additionalBytesSize: Int = 0,
    val reserveDelayMs: Int = 0,
    val correlationId: UUID = UUID.randomUUID(),
)
```

Position matters: `reserveDelayMs` goes before `correlationId`, matching `ES-4`, so positional call sites stay consistent across the family.

- [ ] **Step 6: Thread the knob through the controller**

In `src/main/kotlin/pl/szymanski/wiktor/controller/InventoryController.kt`, replace the request DTO:

```kotlin
data class CreateItemRequest(val id: String, val availableQty: Int, val additionalBytesSize: Int = 0, val reserveDelayMs: Int = 0)
```

and the body of `createItem`:

```kotlin
    @PostMapping
    fun createItem(@RequestBody request: CreateItemRequest): ResponseEntity<CreateItemResponse> {
        log.info("POST /inventory itemId={} availableQty={} additionalBytesSize={} reserveDelayMs={}", request.id, request.availableQty, request.additionalBytesSize, request.reserveDelayMs)
        inventoryService.createItem(
            CreateItemCommand(
                id = request.id,
                availableQty = request.availableQty,
                additionalBytesSize = request.additionalBytesSize,
                reserveDelayMs = request.reserveDelayMs,
                correlationId = UUID.randomUUID(),
            )
        )
        log.info("POST /inventory success itemId={}", request.id)
        return ResponseEntity.status(HttpStatus.CREATED).body(CreateItemResponse(request.id, request.availableQty))
    }
```

- [ ] **Step 7: Run the tests**

Run: `JAVA_HOME=$HOME/.jdks/corretto-21.0.10 ./gradlew test`

Expected: PASS, including the 5 new cases.

`SagaCommandFailureIT` uses Testcontainers and needs a working Docker daemon. If it is skipped in this environment, say so — do not treat a skip as a pass.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/pl/szymanski/wiktor/domain/InventoryItem.kt \
        src/main/kotlin/pl/szymanski/wiktor/domain/events.kt \
        src/main/kotlin/pl/szymanski/wiktor/service/command/CreateItemCommandHandler.kt \
        src/main/kotlin/pl/szymanski/wiktor/controller/InventoryController.kt \
        src/test/kotlin/pl/szymanski/wiktor/domain/InventoryItemReserveDelayTest.kt
git commit -m "feat(bench): honour reserveDelayMs on the reserve command handler

RESERVE_DELAY_MS existed in the k6 harness and reached only ES-4. Spring Boot
drops unknown JSON properties by default, so on this branch the knob was
accepted, ignored, and left nothing in the artifacts to reveal it.

The sleep is in the @CommandHandler, never the @EventSourcingHandler: in the
latter it would be paid on every replay and snapshot load, turning a per-reserve
cost into a startup cost. A test pins that by giving four events and one command
and asserting the run costs one sleep, not four. Signatures match ES-4 exactly."
```

- [ ] **Step 9: Repeat for the next branch, then verify family agreement**

After `ES-1`, repeat Steps 1-8 for `ES-2`, then `ES-3`. Then:

```bash
for b in ES-1 ES-2 ES-3; do
    printf '%-6s' "$b"
    git diff --stat ES-4 "$b" -- \
        src/main/kotlin/pl/szymanski/wiktor/domain/events.kt \
        src/main/kotlin/pl/szymanski/wiktor/service/command/CreateItemCommandHandler.kt
    echo
done
```

Expected: no diff on either file. `InventoryItem.kt` will still differ — `ES-2`/`ES-3` carry snapshot and cache annotations and `ES-4` carries the caching repository — which is exactly the variant axis.

---

## Task 8: End-to-end knob verification

Proves both knobs reach the database and the wall clock on a real stack. A unit test cannot catch a DTO field that Jackson silently drops, which is the specific failure this whole phase exists to prevent.

**Files:** none changed. Produces observed values for Task 9's runbook.

**Interfaces:**
- Consumes: Tasks 4-7 complete on all 8 branches.
- Produces: confirmation that `additionalBytesSize` and `reserveDelayMs` survive the HTTP boundary in both families.

- [ ] **Step 1: Bring up a TO stack**

```bash
git checkout TO-1
docker compose down -v
export JAVA_HOME=$HOME/.jdks/corretto-21.0.10
./gradlew bootJar && docker build -t inventory-reservation-to:latest .
docker compose up -d postgres-to api-to nginx
./k6/bench/wait-healthy.sh http://localhost:8080/actuator/health 180
```

- [ ] **Step 2: Create probe items and assert the row**

```bash
curl -sf -XPOST localhost:8080/inventory -H 'Content-Type: application/json' \
  -d '{"id":"probe-slow","availableQty":1000,"additionalBytesSize":1048576,"reserveDelayMs":25}'
curl -sf -XPOST localhost:8080/inventory -H 'Content-Type: application/json' \
  -d '{"id":"probe-fast","availableQty":1000,"additionalBytesSize":0,"reserveDelayMs":0}'

docker compose exec -T postgres-to psql -U inventory -d inventory -c \
  "SELECT item_id, length(additional_bytes) AS bytes, reserve_delay_ms
     FROM inventory_state ORDER BY item_id;"
```

Expected:

```
  item_id   |  bytes  | reserve_delay_ms
------------+---------+------------------
 probe-fast |       0 |                0
 probe-slow | 1048576 |               25
```

A `bytes` of `0` or a `reserve_delay_ms` of `0` on `probe-slow` means Jackson dropped the field — the exact silent no-op this phase removes. Stop and fix `CreateItemRequest` on that branch.

- [ ] **Step 3: Confirm the delay is actually paid**

`POST /inventory/orders` returns 202 before the reservation runs, so the delay never appears in the client's wall time. Read it server-side instead:

```bash
for i in 1 2 3 4 5; do
  curl -sf -XPOST localhost:8080/inventory/orders -H 'Content-Type: application/json' \
    -d '{"userId":"u1","items":[{"itemId":"probe-slow","quantity":1}]}'
done
sleep 5
curl -s localhost:8080/actuator/prometheus | grep -E '^order_e2e_time_seconds_(sum|count)'
```

Note `sum / count`. Then reset the comparison by ordering only against `probe-fast`:

```bash
for i in 1 2 3 4 5; do
  curl -sf -XPOST localhost:8080/inventory/orders -H 'Content-Type: application/json' \
    -d '{"userId":"u1","items":[{"itemId":"probe-fast","quantity":1}]}'
done
sleep 5
curl -s localhost:8080/actuator/prometheus | grep -E '^order_e2e_time_seconds_(sum|count)'
```

Expected: the first five orders add at least 0.025 s each to `sum`; the second five add substantially less. If both batches cost the same, the sleep is not on the reserve path.

- [ ] **Step 4: Repeat for an ES branch**

```bash
docker compose down -v
git checkout ES-1
./gradlew bootJar && docker build -t inventory-reservation-es:latest .
docker compose up -d postgres-es api-es nginx
./k6/bench/wait-healthy.sh http://localhost:8080/actuator/health 180

curl -sf -XPOST localhost:8080/inventory -H 'Content-Type: application/json' \
  -d '{"id":"probe-slow","availableQty":1000,"additionalBytesSize":1048576,"reserveDelayMs":25}'
```

ES has no `additional_bytes` column by design — the bytes live on the aggregate — so verify the payload through the event store:

```bash
docker compose exec -T postgres-es psql -U inventory -d inventory -c \
  "SELECT aggregate_identifier, length(payload) AS payload_bytes
     FROM domain_event_entry WHERE aggregate_identifier = 'probe-slow';"
```

Expected: `payload_bytes` above 1000000. Then repeat Step 3's latency comparison.

Service and image names come from `bench.env` (`API_SVC`, `DB_SVC`, `IMAGE_TAG`) and differ between the families — read them from the branch rather than assuming.

- [ ] **Step 5: Tear down**

```bash
docker compose down -v
```

Leaving the stack up is what makes the next branch's run fail as an unexplained health timeout.

- [ ] **Step 6: No commit**

This task changes no files. Record the observed values — they become the expected-output examples in Task 9's runbook.

---

## Task 9: Campaign runbook

Turns the approved spec into the document the operator works from during ~47 h of runs.

**Files:**
- Create: `docs/bench-campaign-runbook.md`
- Modify: `k6/README.md` (§2 knobs table and §3 only)
- Delete: `k6/load-tests-plan.md`

**Interfaces:**
- Consumes: `RUN_LABEL` from Task 3; the workload points, staircases, rate rules and guards from the spec.
- Produces: the operator-facing checklist. No code.

- [ ] **Step 1: Write the runbook**

Create `docs/bench-campaign-runbook.md` containing, in this order:

1. **Preflight**, once per session: `export JAVA_HOME=$HOME/.jdks/corretto-21.0.10`; confirm not root; confirm `bench-results/` is writable; `df -h /` shows ≥120 GB free before any `P=1 MiB` run.
2. **Per-branch-switch checklist**: `docker compose down -v`; confirm nothing else holds `:8080`; after each run confirm `image_built_after_head` in `meta.json`, and spot-check jar contents rather than trusting the image timestamp, which Docker's build cache leaves stale on a perfectly good image.
3. **Phase 1 breakpoints**, grouped by workload point, all 24 commands written out. For example:

```bash
git checkout TO-1
SCENARIO=capacity RUN_LABEL=Wbase \
  DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 \
  STEP_START=40 STEP_INC=40 STEP_COUNT=10 \
  ./k6/bench/bench.sh
```

   W-hot rows use `DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 STEP_START=20 STEP_INC=20 STEP_COUNT=12 RUN_LABEL=Whot`; W-fan rows use `DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 STEP_START=10 STEP_INC=10 STEP_COUNT=12 RUN_LABEL=Wfan`.

4. **The bracketing rule**, as a decision applied after each run: knee at the last step or `require_knee` unsatisfied → double `STEP_INC` and re-run; knee at step 0 → halve `STEP_START` and re-run; **and re-run every variant already measured at that point on the new staircase**, because knees read off different staircases are not comparable.
5. **Rate computation**: `python3 k6/bench/compare.py --knee bench-results/*_capacity_*Wbase*`, then `RATE = 0.6 × min knee`, rounded, recorded in the results table.
6. **Phase 1 soaks**, 8 commands: `SCENARIO=soak RATE=<computed> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 RUN_LABEL=Wbase ./k6/bench/bench.sh`.
7. **Selection worksheet**: the disqualify-then-mean-rank procedure with an empty table to fill.
8. **Phase 2**, cell by cell, with `RUN_LABEL=C00`/`C01`/`C10`/`C11`, and for the `P=1 MiB` cells the mandatory `PAYLOAD_BYTES=1048576 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m`.
9. **Results tables** mirroring Appendix A of the spec, filled as runs complete.

Every command must be copy-pasteable. Do not write "run the breakpoint for each variant" — write out all 24.

- [ ] **Step 2: Fix `k6/README.md`**

Add `stress` to the `SCENARIO` row of the knobs table in §2:

```markdown
| `SCENARIO` | `steady` | `capacity` · `steady` · `soak` · `spike` · `stress` (also `seed`/`warmup` internally) |
```

Replace the whole of §3 ("What to run, and where") with:

```markdown
## 3. What to run, and where

The campaign design lives in
[`../docs/superpowers/specs/2026-08-06-load-test-campaign-design.md`](../docs/superpowers/specs/2026-08-06-load-test-campaign-design.md);
the ordered commands live in
[`../docs/bench-campaign-runbook.md`](../docs/bench-campaign-runbook.md).

There are 8 variants — `TO-1..4` and `ES-1..4`. An earlier version of this section planned
around `ES-3-optimistic`, `ES-3-pesimistic`, `ES-3-WeakRefCache` and
`ES-3-pesimistic-scaling`, which no longer exist as branches.
```

Sections 1, 2 and 4 (preflight, knobs, troubleshooting) are still accurate — leave the rest alone.

- [ ] **Step 3: Delete the superseded plan and verify the links**

```bash
git rm k6/load-tests-plan.md
ls docs/superpowers/specs/2026-08-06-load-test-campaign-design.md docs/bench-campaign-runbook.md
grep -rn "load-tests-plan" k6/ README.md docs/ || echo "no dangling references"
```

Expected: both files listed, and no remaining reference to `load-tests-plan.md`.

- [ ] **Step 4: Commit**

```bash
git add docs/bench-campaign-runbook.md k6/README.md
git commit -m "docs(bench): campaign runbook, and retire the superseded phase plan

k6/README.md section 3 and load-tests-plan.md both planned a 12-variant campaign
around ES-3-optimistic, ES-3-pesimistic, ES-3-WeakRefCache and
ES-3-pesimistic-scaling. Those branches are gone; the families are TO-1..4 and
ES-1..4. The README is the obvious doc to reach for, and its phase plan looked
authoritative while naming subjects that cannot be checked out.

Sections 1, 2 and 4 stay -- preflight, knobs and INVALID diagnoses are still
right; the knobs table gains the new stress scenario."
```

- [ ] **Step 5: Propagate the docs to the other 7 branches**

```bash
DOCS=$(git rev-parse HEAD)
for b in TO-1 TO-2 TO-4 ES-1 ES-2 ES-3 ES-4; do
    git checkout "$b" && git cherry-pick "$DOCS" || { echo "CONFLICT on $b"; break; }
done
git checkout TO-3
```

`k6/README.md` and `k6/load-tests-plan.md` are inside `k6/`, so this commit changes the invariant set and must land everywhere. Re-run the invariant check from Task 4, Step 6 afterwards.

---

## Verification: whole-phase gate

All of these must hold before phase 1 of the campaign starts.

```bash
# 1. Every branch builds and its tests pass
for b in TO-1 TO-2 TO-3 TO-4 ES-1 ES-2 ES-3 ES-4; do
    git checkout "$b"
    JAVA_HOME=$HOME/.jdks/corretto-21.0.10 ./gradlew test || echo "FAILED: $b"
done

# 2. The harness is byte-identical everywhere
git checkout TO-3
for b in TO-1 TO-2 TO-4 ES-1 ES-2 ES-3 ES-4; do
    printf '%-6s ' "$b"
    [ -z "$(git diff --stat TO-3 "$b" -- k6 docker-compose.bench.yml)" ] && echo OK || echo DIVERGED
done

# 3. Every branch has a bench.env whose VARIANT matches the branch
for b in TO-1 TO-2 TO-3 TO-4 ES-1 ES-2 ES-3 ES-4; do
    printf '%-6s ' "$b"
    git show "$b:bench.env" 2>/dev/null | grep '^VARIANT=' || echo "MISSING bench.env"
done

# 4. Every branch accepts both knobs at the DTO
for b in TO-1 TO-2 TO-3 TO-4 ES-1 ES-2 ES-3 ES-4; do
    printf '%-6s ' "$b"
    git show "$b:src/main/kotlin/pl/szymanski/wiktor/controller/InventoryController.kt" \
      | grep -c 'class CreateItemRequest.*reserveDelayMs'
done

# 5. Every TO branch stores the padding on the row
for b in TO-1 TO-2 TO-3 TO-4; do
    printf '%-6s ' "$b"
    git cat-file -e "$b:src/main/resources/db/migration/V6__additional_bytes.sql" 2>/dev/null \
      && echo OK || echo MISSING
done

# 6. The Python harness suite passes
git checkout TO-3
python3 -m unittest discover -s scripts/tests -t .
```

Expected: no `FAILED`; seven `OK` under (2); eight `VARIANT=<branch>` lines under (3); a `1` on all eight lines of (4); four `OK` under (5); a green Python suite.

---

## Self-Review Notes

Checked against `docs/superpowers/specs/2026-08-06-load-test-campaign-design.md`:

- §2a `reserveDelayMs` port → Tasks 6 (TO) and 7 (ES); the no-op-at-0 requirement is a test in both.
- §2b `additional_bytes` on TO → Tasks 5 and 6.
- §2c `stress` scenario → Task 2, all three sub-parts.
- §2d `RUN_LABEL` → Task 3.
- Cross-branch invariant → Tasks 1 and 4, Task 9 Step 5, gate check (2).
- §3, §4, §6 workload points, staircases, rates → Task 9's runbook, where they become commands.
- §8 operational guards → Task 9's runbook, items 1, 2 and 8.

**Added beyond the spec, and why.** The spec's §2c/§2d assumed propagation was a formality. An audit of the actual branch state found it is not: the harness diverges on all seven non-`TO-3` branches, and `ES-1` and `ES-3` have no harness and no `bench.env` at all, so `common.sh` exits `FATAL` there and neither branch can be benchmarked. Tasks 1 and 4 exist to fix that, and are prerequisites for the campaign rather than optional tidying.

**Not covered by this plan, by design:** §5 (selection), §7 (execution order) and §9 (budget) are operator procedure across ~47 h of machine time, not code. Task 9 transcribes them into the runbook.

**Deliberate deviation:** the spec's §2c says `evaluate.py` should judge `stress` on `drain_service_rate`. `dump.py` already computes and emits it, so it lands in `dump.json` and is read via `compare.py` with no code change. Adding a threshold would require a defensible limit, and no measurement yet exists to set one.
