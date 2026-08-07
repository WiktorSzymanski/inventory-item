"""Behavioural tests for scripts/run-campaign.sh.

Everything here drives the REAL script through `--dry-run`, which validates the plan and
exits before any Docker work. So these bind to shipped behaviour rather than to a copy of
the parsing rules, and they need no daemon, no images and no network.
"""

import os
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(__file__)
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
SCRIPT = os.path.join(ROOT, "scripts", "run-campaign.sh")


def run(*args, env_extra=None):
    env = dict(os.environ)
    # Scrub knobs that would otherwise satisfy the derived-rate gate from the ambient
    # environment and make those tests pass for the wrong reason.
    for k in ("RATE", "SPIKE_BASE", "POINT", "SCENARIO", "DISTINCT_ITEMS"):
        env.pop(k, None)
    if env_extra:
        env.update(env_extra)
    return subprocess.run([SCRIPT, *args], cwd=ROOT, env=env,
                          capture_output=True, text=True)


class PlanValidation(unittest.TestCase):
    def test_a_valid_plan_passes(self):
        r = run("--dry-run", "capacity:W-base", "capacity:W-hot", "capacity:W-fan")
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_unknown_scenario_is_fatal(self):
        r = run("--dry-run", "capcity:W-base")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("unknown scenario", r.stderr)

    def test_unknown_point_is_fatal(self):
        r = run("--dry-run", "capacity:W-bse")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("unknown point", r.stderr)

    def test_a_composed_point_is_accepted(self):
        # points.env composes with a comma; phase 2 depends on this working.
        r = run("--dry-run", "capacity:W-base,C11")
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_a_composed_point_validates_every_member(self):
        r = run("--dry-run", "capacity:W-base,C99")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("C99", r.stderr)

    def test_a_bare_scenario_needs_no_point(self):
        r = run("--dry-run", "steady")
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_a_malformed_knob_is_fatal(self):
        r = run("--dry-run", "capacity:W-base:DRAIN_TIMEOUT")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("not KEY=VALUE", r.stderr)

    def test_validation_runs_before_anything_starts(self):
        """A typo in the LAST step must fail without executing the first.

        The whole point of validating up front: an invalid step 19 should cost a second,
        not eighteen steps of machine time.
        """
        r = run("--dry-run", "capacity:W-base", "capacity:W-hot", "nonsense:W-fan")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("nonsense", r.stderr)


class DerivedRateGate(unittest.TestCase):
    """soak/stress/spike rates come from a measured knee. Running them at the harness
    default produces well-formed artifacts that answer a different question, and nothing
    downstream can detect it — so the gate must fire before any container starts."""

    def test_soak_without_rate_is_fatal(self):
        r = run("--dry-run", "soak:W-base")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("needs an explicit RATE", r.stderr)

    def test_stress_without_rate_is_fatal(self):
        r = run("--dry-run", "stress:W-base")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("needs an explicit RATE", r.stderr)

    def test_spike_requires_spike_base_not_rate(self):
        # profiles.js spike() reads spikeBase; RATE would be silently ignored.
        r = run("--dry-run", "spike:W-base:RATE=42")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("needs an explicit SPIKE_BASE", r.stderr)

    def test_soak_with_rate_passes(self):
        r = run("--dry-run", "soak:W-base:RATE=42,DRAIN_TIMEOUT=1800")
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_spike_with_spike_base_passes(self):
        r = run("--dry-run", "spike:W-base:SPIKE_BASE=17")
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_an_inherited_rate_satisfies_the_gate(self):
        # `RATE=42 scripts/run-campaign.sh soak:W-base` is a legitimate single-step form.
        r = run("--dry-run", "soak:W-base", env_extra={"RATE": "42"})
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_capacity_needs_no_rate(self):
        # Its staircase comes from points.env, not from a derived rate.
        r = run("--dry-run", "capacity:W-base")
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_the_suggested_fix_is_itself_valid_input(self):
        """The error prints a corrected step spec. If that spec is malformed, the message
        sends the operator into a second failure."""
        r = run("--dry-run", "spike:W-base:SPIKE_FACTOR=4")
        self.assertNotEqual(r.returncode, 0)
        suggested = r.stderr.strip().splitlines()[-1].strip()
        self.assertIn("SPIKE_BASE=<value>", suggested)
        again = run("--dry-run", suggested.replace("<value>", "17"))
        self.assertEqual(again.returncode, 0, again.stderr)


class Resume(unittest.TestCase):
    PLAN = ["capacity:W-base", "capacity:W-hot", "capacity:W-fan"]

    def _state(self, outcomes):
        """Write a state file: {step: 'DONE'|'FAIL'} for steps that finished."""
        fh = tempfile.NamedTemporaryFile("w", suffix=".state", delete=False)
        fh.write("# campaign started 2026-08-07T04:00:00Z\n")
        for s in self.PLAN:
            fh.write(f"# STEP {s}\n")
        for step, status in outcomes.items():
            fh.write(f"2026-08-07T05:00:00Z\t{status}\t{step}\trc=0\n")
        fh.close()
        self.addCleanup(os.unlink, fh.name)
        return fh.name

    def test_resume_reconstructs_the_plan_from_the_state_file(self):
        r = run("--resume", self._state({}), "--dry-run")
        self.assertEqual(r.returncode, 0, r.stderr)
        for s in self.PLAN:
            self.assertIn(s, r.stdout)

    def test_a_done_step_is_marked_done(self):
        r = run("--resume", self._state({"capacity:W-base": "DONE"}), "--dry-run")
        self.assertRegex(r.stdout, r"capacity:W-base\s+\(done\)")

    def test_a_failed_step_is_not_mislabelled_as_done(self):
        r = run("--resume", self._state({"capacity:W-hot": "FAIL"}), "--dry-run")
        self.assertIn("(failed, skipping)", r.stdout)
        self.assertNotRegex(r.stdout, r"capacity:W-hot\s+\(done\)")

    def test_retry_failed_flips_a_failed_step_to_runnable(self):
        state = self._state({"capacity:W-hot": "FAIL"})
        self.assertIn("(failed, skipping)", run("--resume", state, "--dry-run").stdout)
        self.assertIn("(failed, will retry)",
                      run("--resume", state, "--retry-failed", "--dry-run").stdout)

    def test_retry_failed_does_not_rerun_a_successful_step(self):
        state = self._state({"capacity:W-base": "DONE", "capacity:W-hot": "FAIL"})
        out = run("--resume", state, "--retry-failed", "--dry-run").stdout
        self.assertRegex(out, r"capacity:W-base\s+\(done\)")

    def test_resume_refuses_extra_steps(self):
        # Two sources for the plan would silently disagree.
        r = run("--resume", self._state({}), "capacity:W-fan")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("do not also pass steps", r.stderr)

    def test_a_missing_state_file_is_fatal(self):
        r = run("--resume", "/nonexistent/campaign.state", "--dry-run")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("no such state file", r.stderr)


class SkipDecision(unittest.TestCase):
    """Exercise state_should_skip directly.

    --dry-run exits before the run loop, so no plan-level test ever reaches the function
    that actually decides whether to re-run a step. A mutation that made FAIL unconditionally
    skippable — defeating --retry-failed — survived the whole plan-level suite. So extract
    the real block from the shipped script and execute it, the same technique
    test_bench_sh.py uses for bench.sh's run-label and provenance blocks.
    """

    @staticmethod
    def _block():
        with open(SCRIPT) as fh:
            script = fh.read()
        return script.split("# >>> state-skip")[1].split("# <<< state-skip")[0]

    def _decide(self, recorded_status, retry_failed):
        """Return True when the step would be SKIPPED."""
        fh = tempfile.NamedTemporaryFile("w", suffix=".state", delete=False)
        fh.write("# STEP capacity:W-base\n")
        if recorded_status:
            fh.write(f"2026-08-07T05:00:00Z\t{recorded_status}\tcapacity:W-base\trc=0\n")
        fh.close()
        self.addCleanup(os.unlink, fh.name)
        prog = (f'RETRY_FAILED={retry_failed}\n' + self._block() +
                f'\nif state_should_skip "{fh.name}" "capacity:W-base"; '
                f'then echo SKIP; else echo RUN; fi')
        r = subprocess.run(["bash", "-c", prog], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        return r.stdout.strip() == "SKIP"

    def test_a_done_step_is_skipped(self):
        self.assertTrue(self._decide("DONE", retry_failed=0))

    def test_a_done_step_is_skipped_even_with_retry_failed(self):
        # --retry-failed must not re-run work that already succeeded.
        self.assertTrue(self._decide("DONE", retry_failed=1))

    def test_a_failed_step_is_skipped_by_default(self):
        self.assertTrue(self._decide("FAIL", retry_failed=0))

    def test_retry_failed_makes_a_failed_step_run(self):
        # The case the plan-level tests could not reach.
        self.assertFalse(self._decide("FAIL", retry_failed=1))

    def test_an_unfinished_step_always_runs(self):
        self.assertFalse(self._decide(None, retry_failed=0))
        self.assertFalse(self._decide(None, retry_failed=1))


class DryRunStartsNothing(unittest.TestCase):
    def test_dry_run_writes_no_state_file(self):
        before = set(os.listdir(os.path.join(ROOT, "bench-results")))
        run("--dry-run", "capacity:W-base")
        after = set(os.listdir(os.path.join(ROOT, "bench-results")))
        self.assertEqual(before, after)


if __name__ == "__main__":
    unittest.main()
