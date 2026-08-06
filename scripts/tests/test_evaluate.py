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

    def test_the_scenario_limits_are_not_shadowed_by_the_vu_limits_lookup(self):
        """check_validity has a local for profile.json's vu_limits that is assigned BEFORE
        the drain gate is read. If it ever reuses the `limits` name it silently wins, the
        gate falls back to True, and `stress` reports INVALID on the backlog it exists to
        produce — with every test above still passing on the default path."""
        with open(os.path.join(self.tmp, "profile.json"), "w") as fh:
            json.dump({"vu_limits": {"stress": {"maxVUs": 4000}}}, fh)
        self.assertEqual(
            [], self.failed_names(self.run_validity({"require_backlog_drained": False})))


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
