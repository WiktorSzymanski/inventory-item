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

    def test_two_points_disagreeing_on_an_identity_knob_is_fatal(self):
        """The conflict check used to compare only against __SHELL_$key, captured before
        anything expanded. A knob no point had touched yet had an empty prior, so once point
        A set it, point B overwrote it silently. Measured before the fix:

            POINT=C01,C11      -> PAYLOAD_BYTES=1048576, label "C01-C11", rc=0

        A typo (C01 for W-base) therefore produced a run labelled for a workload it did not
        run — the exact failure points.env exists to prevent.
        """
        r = resolve({"POINT": "C01,C11"}, ("PAYLOAD_BYTES", "RUN_LABEL"))
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("PAYLOAD_BYTES", r.stderr)
        # Both points named, so the message says which two disagreed rather than just
        # "conflict".
        self.assertIn("C01", r.stderr)
        self.assertIn("C11", r.stderr)

    def test_two_workload_points_conflict_on_distinct_items(self):
        r = resolve({"POINT": "W-base,W-hot"})
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("DISTINCT_ITEMS", r.stderr)

    def test_points_repeating_the_same_identity_value_stay_legal(self):
        """Only a DIFFERING value is a conflict. C00 twice is pointless but not wrong, and
        rejecting it would be a rule about repetition rather than about identity."""
        r = resolve({"POINT": "C00,C00"}, ("PAYLOAD_BYTES", "RESERVE_DELAY_MS", "RUN_LABEL"))
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout.split("\n")[:3], ["0", "0", "C00-C00"])

    def test_the_conflict_check_does_not_break_legal_composition(self):
        """POINT=W-base,C11 is a documented phase-2 campaign command: a workload point and a
        cost-lever point, sharing no identity knob. Guarded separately from
        test_points_compose because the risk here is the FIX over-rejecting, not the
        composition mechanism itself."""
        r = resolve({"POINT": "W-base,C11"},
                    ("DISTINCT_ITEMS", "ITEMS_PER_ORDER", "PAYLOAD_BYTES",
                     "RESERVE_DELAY_MS", "RUN_LABEL"))
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout.split("\n")[:5],
                         ["100", "4", "1048576", "25", "W-base-C11"])

    def test_calibration_knobs_may_still_differ_between_points(self):
        """STEP_* are explicitly NOT identity: the campaign's bracketing rule expects
        staircases to be re-tuned, so last-point-wins must stay silent there. W-base sets
        STEP_START=40 and C11 sets 2; that must compose, not abort."""
        r = resolve({"POINT": "W-base,C11"}, ("STEP_START", "STEP_INC", "STEP_COUNT"))
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout.split("\n")[:3], ["2", "3", "10"])

    def test_unknown_point_is_fatal(self):
        r = resolve({"POINT": "W-nope"})
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("W-nope", r.stderr)

    def test_explicit_run_label_survives(self):
        r = resolve({"POINT": "W-hot", "RUN_LABEL": "rerun2"}, ("RUN_LABEL",))
        self.assertEqual(r.stdout.strip(), "rerun2")


if __name__ == "__main__":
    unittest.main()
