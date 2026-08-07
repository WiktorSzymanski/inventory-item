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


META_HEREDOC_START = 'python3 - "$RUN_DIR/meta.json" <<PYEOF\n'
META_HEREDOC_END = "\nPYEOF"

# Every shell variable the meta.json heredoc interpolates, with an inert dummy value.
# RUN_LABEL, POINT_RESOLVED and POINT are deliberately left out here; each test that
# cares about them sets its own combination via overrides.
META_HEREDOC_ENV_DEFAULTS = {
    "RUN_DIR": "/tmp/meta-heredoc-test-rundir",
    "REPO_ROOT": "/tmp",
    "VARIANT": "ES-4",
    "VARIANT_FAMILY": "ES",
    "SCENARIO": "steady",
    "RUN_LABEL": "",
    "POINT_RESOLVED": "",
    "POINT": "",
    "GIT_BRANCH": "main",
    "GIT_COMMIT": "abc123",
    "GIT_DIRTY": "0",
    "IMAGE_TAG": "test:latest",
    "IMAGE_ID": "sha256:xyz",
    "IMAGE_CREATED": "2026-08-07T00:00:00Z",
    "IMAGE_FRESH": "true",
    "K6_VERSION": "1.1.0",
    "PROM_JOB": "inventory-es",
    "DB_NAME": "inventory",
    "API_CONTAINER_RE": ".*api-es.*",
    "EXPECTED_REPLICAS": "1",
    "K6_EXIT": "0",
    "RUN_NAME": "test-run",
    "T_RESET": "0",
    "T_SEED": "1",
    "T_WARMUP": "2",
    "T_WARMUP_END": "3",
    "T0": "4",
    "T1": "5",
    "T2": "6",
    "BACKLOG_AT_STOP": "0",
    "DRAIN_STATE": "drained",
    "DRAIN_SECONDS": "0",
}


def extract_meta_heredoc():
    """Pull the meta.json heredoc body (start line through PYEOF) out of the shipped
    bench.sh, so tests bind to the real script rather than to a copy of it."""
    with open(BENCH_SH) as fh:
        script = fh.read()
    start = script.index(META_HEREDOC_START)
    end = script.index(META_HEREDOC_END, start) + len(META_HEREDOC_END)
    return script[start:end]


def render_meta_heredoc(**overrides):
    """Render the meta.json heredoc through bash with dummy values, exactly the way
    bench.sh's own unquoted `<<PYEOF` expands $VAR / ${VAR:-default} before python ever
    sees the text. The first line is swapped for `cat <<PYEOF` so bash performs the
    expansion and prints the resulting python source instead of invoking python3 on it
    (the body reads profile.json and calls shutil.disk_usage, which we don't want to
    actually run)."""
    body = extract_meta_heredoc()
    first_line, rest = body.split("\n", 1)
    assert first_line == META_HEREDOC_START.rstrip("\n"), first_line
    script = "cat <<PYEOF\n" + rest

    env = dict(os.environ)
    for key in META_HEREDOC_ENV_DEFAULTS:
        env.pop(key, None)
    env.update(META_HEREDOC_ENV_DEFAULTS)
    env.update(overrides)

    result = subprocess.run(
        ["bash", "-c", script], env=env, capture_output=True, text=True, check=True)
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


class MetaHeredocRendersCorrectly(unittest.TestCase):
    """The string-search tests above would pass even if run_label/point were moved
    outside the meta = {} dict, or if a stray quote/brace elsewhere in the heredoc broke
    JSON validity — the substring would still be present in the file. These tests bind
    to actual bash expansion of the shipped heredoc, the same mechanism RunLabel uses
    above, so a regression that preserves the substring but breaks behaviour is caught.
    """

    def test_rendered_heredoc_compiles_as_valid_python(self):
        rendered = render_meta_heredoc(POINT_RESOLVED="W-hot", POINT="W-raw")
        # Raises SyntaxError on an unbalanced brace or stray quote from the interpolation.
        compile(rendered, "<meta.json heredoc>", "exec")

    def test_point_prefers_resolved_over_raw(self):
        rendered = render_meta_heredoc(POINT_RESOLVED="W-hot", POINT="W-raw")
        self.assertIn('"point": "W-hot"', rendered)

    def test_point_falls_back_to_raw_when_resolved_is_empty(self):
        rendered = render_meta_heredoc(POINT_RESOLVED="", POINT="W-raw")
        self.assertIn('"point": "W-raw"', rendered)

    def test_point_is_empty_when_both_are_empty(self):
        rendered = render_meta_heredoc(POINT_RESOLVED="", POINT="")
        self.assertIn('"point": ""', rendered)

    def test_run_label_and_point_are_inside_the_meta_dict_literal(self):
        # Not merely "somewhere in the file": inside the meta = { ... } dict literal.
        # Only the dict's own closing brace is unindented ("\n}"); every nested dict
        # (timeline/windows/drain/host) closes with a "    }," at 4-space indent, so
        # this delimits exactly the outer dict body.
        body = extract_meta_heredoc()
        start = body.index("meta = {")
        end = body.index("\n}", start)
        dict_block = body[start:end]
        self.assertIn('"run_label"', dict_block)
        self.assertIn('"point"', dict_block)


if __name__ == "__main__":
    unittest.main()
