"""Every compose file a shell script names must actually be in the tree.

This is the check that was missing when the harness moved to `main`. Task 2 verified that
every *script* referenced by docs/bench-replay.md existed; nothing verified the compose
files those scripts invoke, and `docker-compose.replay.yml` was simply never imported. The
symptom was silent by construction: `ARCHIVE_TSDB=1` is the default, so `run-suite.sh` called
`prom_archive.sh` after every variant, it failed on the missing file, and the failure is
non-fatal — the suite logged one line, carried on, and `bench-replay-data` was never
populated. The remedy that line suggests (re-run it later) failed identically.
"""
import glob
import os
import re
import unittest

HERE = os.path.dirname(__file__)
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

# Any docker-compose*.yml token, wherever it appears in a script — a `-f` argument, an array
# element, or a message telling the operator what to run. All of them are promises the tree
# has to keep.
COMPOSE_REF = re.compile(r"docker-compose[A-Za-z0-9._-]*\.ya?ml")

SCRIPTS = sorted(glob.glob(os.path.join(ROOT, "scripts", "*.sh"))
                 + glob.glob(os.path.join(ROOT, "k6", "bench", "*.sh")))


def referenced_compose_files():
    """{filename: [script paths that name it]}"""
    found = {}
    for path in SCRIPTS:
        with open(path) as fh:
            for name in set(COMPOSE_REF.findall(fh.read())):
                found.setdefault(name, []).append(os.path.relpath(path, ROOT))
    return found


class ComposeFilesExist(unittest.TestCase):
    def test_scripts_were_actually_found(self):
        """A path typo above would make every other test in this file vacuously pass."""
        self.assertTrue(SCRIPTS)
        self.assertIn("scripts/prom_archive.sh",
                      [os.path.relpath(p, ROOT) for p in SCRIPTS])

    def test_at_least_one_compose_file_is_referenced(self):
        self.assertTrue(referenced_compose_files())

    def test_every_referenced_compose_file_exists(self):
        for name, referrers in sorted(referenced_compose_files().items()):
            with self.subTest(compose_file=name):
                self.assertTrue(
                    os.path.isfile(os.path.join(ROOT, name)),
                    f"{name} is named by {', '.join(referrers)} but is not in the tree")


# ---------------------------------------------------------------- meta.json vs compose

COMPOSE = os.path.join(ROOT, "docker-compose.yml")
BENCH_SH = os.path.join(ROOT, "k6", "bench", "bench.sh")

# `${VAR:-default}` — the only form either file uses to name a fallback.
DEFAULTED = re.compile(r"\$\{([A-Z0-9_]+):-([^}]*)\}")
# `      VAR:` with nothing after it: compose forwards the host's value or nothing at all.
BARE = re.compile(r"^ {6}([A-Z0-9_]+):\s*$", re.M)


def api_env_block():
    """The `api` service's environment, up to `ports:`. Scoped deliberately: the k6 and
    netem services have variables of their own that meta.json has no business matching."""
    with open(COMPOSE) as fh:
        body = fh.read()
    return body.split("\n  api:\n", 1)[1].split("\n    ports:", 1)[0]


def meta_heredoc():
    """bench.sh's meta.json heredoc, which bash expands before python sees it."""
    with open(BENCH_SH) as fh:
        body = fh.read()
    start = body.index('python3 - "$RUN_DIR/meta.json" <<PYEOF')
    return body[start:body.index("\nPYEOF", start)]


class MetaDefaultsMatchCompose(unittest.TestCase):
    """meta.json must record the value the API actually ran with.

    A compose default always SETS the variable, so on a run that does not export the knob
    the container gets compose's default — and bench.sh, reading the same unset variable,
    falls back to its OWN literal. Where the two literals disagree, meta.json describes a
    stack that never existed, and it fails in the direction that hides: the run succeeds,
    the number looks deliberate, and only a reader who opens both files can tell. That is
    what saga_total_segments (60 recorded, 32 run) and saga_intake_capacity (112 recorded,
    70 run) did to every run archived before 2026-09-04.
    """

    def setUp(self):
        self.compose_defaults = dict(DEFAULTED.findall(api_env_block()))
        self.meta_defaults = dict(DEFAULTED.findall(meta_heredoc()))

    def test_the_parsers_found_something(self):
        """A changed heredoc marker or service name would make the rest vacuously pass."""
        self.assertIn("COMMAND_POOL", self.compose_defaults)
        self.assertIn("COMMAND_POOL", self.meta_defaults)

    def test_shared_knobs_agree_on_their_default(self):
        shared = sorted(set(self.compose_defaults) & set(self.meta_defaults))
        self.assertTrue(shared)
        for var in shared:
            with self.subTest(var=var):
                self.assertEqual(
                    self.compose_defaults[var], self.meta_defaults[var],
                    f"docker-compose.yml sends {var}={self.compose_defaults[var]} to the "
                    f"container when the host does not set it, but meta.json would record "
                    f"{self.meta_defaults[var]}")

    def test_bare_compose_knobs_are_recorded_as_absent(self):
        """The other half of the rule. A variable compose forwards BARE reaches no container
        unless the host set it, so the branch's own application.yaml decides — and there is no
        single number meta.json could name, the branches differing. Its fallback must stay
        empty, so an unset run is recorded as "the branch default" rather than as a value."""
        for var in sorted(set(BARE.findall(api_env_block())) & set(self.meta_defaults)):
            with self.subTest(var=var):
                self.assertEqual(
                    "", self.meta_defaults[var],
                    f"{var} is forwarded bare by docker-compose.yml, so an unset run leaves "
                    f"the branch's own default in force; meta.json must not claim "
                    f"{self.meta_defaults[var]!r}")


if __name__ == "__main__":
    unittest.main()
