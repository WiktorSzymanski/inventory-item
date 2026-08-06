import os
import re
import unittest

import importlib.util

HERE = os.path.dirname(__file__)
FIXTURE_DIR = os.path.join(HERE, "fixtures")
spec_ = importlib.util.spec_from_file_location(
    "replay_run", os.path.join(HERE, "..", "replay_run.py"))
replay_run = importlib.util.module_from_spec(spec_)
spec_.loader.exec_module(replay_run)


def samples(text, family):
    return [line for line in text.splitlines() if line.startswith(family + "{")]


def label(line, name):
    match = re.search(rf'{name}="([^"]*)"', line)
    return match.group(1) if match else None


class OpenMetricsGeneration(unittest.TestCase):
    def setUp(self):
        # The fixture directory doubles as a run directory: it contains mini-dump.json,
        # which the test copies to dump.json in a temp dir.
        import json
        import shutil
        import tempfile
        self.run_dir = tempfile.mkdtemp(prefix="replay-test-")
        shutil.copy(os.path.join(FIXTURE_DIR, "mini-dump.json"),
                    os.path.join(self.run_dir, "dump.json"))
        with open(os.path.join(self.run_dir, "meta.json"), "w") as fh:
            json.dump({"variant": "TEST-1", "scenario": "capacity", "steps": [
                {"index": 0, "targetRate": 20, "startsAt": 0, "endsAt": 120},
                {"index": 1, "targetRate": 40, "startsAt": 120, "endsAt": 240}]}, fh)
        self.text, self.run_id, self.window, self.count = \
            replay_run.build_openmetrics(self.run_dir, axis="elapsed")

    def test_emits_exactly_three_families(self):
        families = {line.split("{")[0] for line in self.text.splitlines()
                    if line and not line.startswith("#")}
        self.assertEqual(families, {"replay_series", "replay_step", "replay_summary"})

    def test_elapsed_axis_anchors_the_first_sample(self):
        first = samples(self.text, "replay_series")[0]
        # window.full starts at 1000 in the fixture; the first sample is at t=1000 -> ANCHOR + 0
        self.assertTrue(first.endswith(f" {replay_run.ANCHOR_EPOCH}"), first)

    def test_wall_axis_keeps_original_timestamps(self):
        text, _, _, _ = replay_run.build_openmetrics(self.run_dir, axis="wall")
        first = samples(text, "replay_series")[0]
        self.assertTrue(first.endswith(" 1000"), first)
        self.assertEqual(label(first, "axis"), "wall")

    def test_dict_valued_scalars_become_the_dim_label(self):
        e2e = [s for s in samples(self.text, "replay_step") if label(s, "metric") == "e2e_p95"]
        self.assertEqual(len(e2e), 2, "one sample per step")
        self.assertEqual({label(s, "dim") for s in e2e}, {"confirmed"})

    def test_null_scalars_are_skipped(self):
        self.assertEqual([s for s in samples(self.text, "replay_step")
                          if label(s, "metric") == "opt_retry"], [])

    def test_step_samples_land_at_the_plateau_midpoint(self):
        cpu = [s for s in samples(self.text, "replay_step") if label(s, "metric") == "cpu_avg"]
        # fixture step 0 window is [1010, 1070] -> midpoint 1040 -> elapsed 40
        self.assertTrue(cpu[0].endswith(f" {replay_run.ANCHOR_EPOCH + 40}"), cpu[0])

    def test_summary_carries_both_windows(self):
        windows = {label(s, "window") for s in samples(self.text, "replay_summary")}
        self.assertEqual(windows, {"full"})  # the fixture has no load_window_scalars

    def test_samples_within_a_series_are_time_ordered(self):
        """promtool rejects a series whose samples go backwards."""
        seen = {}
        for line in self.text.splitlines():
            if line.startswith("#") or not line:
                continue
            identity, _, rest = line.partition("} ")
            value, _, ts = rest.rpartition(" ")
            ts = int(ts)
            self.assertGreaterEqual(ts, seen.get(identity, ts), f"out of order: {line}")
            seen[identity] = ts


if __name__ == "__main__":
    unittest.main()
