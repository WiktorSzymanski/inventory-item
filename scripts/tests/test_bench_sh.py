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


if __name__ == "__main__":
    unittest.main()
