"""Every compose file a shell script names must actually be in the tree.

This is the check that was missing when the harness moved to `main`. Task 2 verified that
every *script* referenced by docs/bench-replay.md existed; nothing verified the compose
files those scripts invoke, and `docker-compose.replay.yml` was simply never imported. The
symptom was silent by construction: `ARCHIVE_TSDB=1` is the default, so `run-suite.sh` called
`prom_archive.sh` after every variant, it failed on the missing file, and the failure is
non-fatal — the suite logged one line, carried on, and `bench-replay-mongo` was never
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


if __name__ == "__main__":
    unittest.main()
