"""Guards for scripts/build-images.sh's manifest step.

The manifest used to re-parse variants.env with its own `if len(line) == 3`. That is a
second parser for the registry, and it broke silently when variants.env grew a fourth
column: every line then had 4 fields, the registry came out empty, and the first lookup
raised KeyError — after all eight images had already been built, and leaving
bench-results/images.json stale. A stale manifest makes run-suite.sh think every image is
behind its branch head, so it calls build-images.sh, which crashes again and aborts the run.

These tests exist to make sure the registry has exactly ONE parser, and that adding a
further column cannot resurrect the bug.
"""

import os
import re
import subprocess
import unittest

HERE = os.path.dirname(__file__)
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
BUILD_SH = os.path.join(ROOT, "scripts", "build-images.sh")
LIB_SH = os.path.join(ROOT, "scripts", "lib.sh")
VARIANTS_ENV = os.path.join(ROOT, "variants.env")


def data_lines():
    out = []
    with open(VARIANTS_ENV) as fh:
        for line in fh:
            line = line.split("#", 1)[0].split()
            if line:
                out.append(line)
    return out


def code_of(path):
    """File contents with whole-line comments stripped.

    Both the shell and the embedded python use `#`. Without this, these tests match the
    comment that *documents* the old bug and fail on correct code — which is what happened
    the first time they were run.
    """
    with open(path) as fh:
        lines = fh.read().splitlines()
    return "\n".join(l for l in lines if not l.lstrip().startswith("#"))


class RegistryHasOneParser(unittest.TestCase):
    def test_build_images_does_not_reparse_variants_env(self):
        """lib.sh's read_variants is the only reader. A second one drifts."""
        code = code_of(BUILD_SH)
        self.assertNotIn('"variants.env"', code)
        self.assertNotIn("'variants.env'", code)

    def test_no_field_count_equality_check_survives(self):
        """`len(line) == 3` is the exact shape of the bug: it silently stops matching when
        the registry gains a column, rather than failing loudly."""
        self.assertIsNone(re.search(r"len\(\s*line\s*\)\s*==\s*\d", code_of(BUILD_SH)))

    def test_manifest_receives_branch_and_family_from_lib_sh(self):
        code = code_of(BUILD_SH)
        self.assertIn("branch_of", code)
        self.assertIn("family_of", code)


class RegistryToleratesExtraColumns(unittest.TestCase):
    """read_variants must keep working as variants.env grows. It is awk over $1..$4 with a
    default for the capability column; a fifth column must not break the first four."""

    def _read_variants(self, contents):
        with open(LIB_SH) as fh:
            lib = fh.read()
        registry_block = (lib.split("---------- registry")[1]
                             .split("---------- worktrees")[0])
        prog = 'MAIN_ROOT="$1"\n' + registry_block + "\nread_variants\n"
        import tempfile
        d = tempfile.mkdtemp()
        with open(os.path.join(d, "variants.env"), "w") as fh:
            fh.write(contents)
        r = subprocess.run(["bash", "-c", prog, "_", d], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        return [l.split() for l in r.stdout.strip().splitlines()]

    def test_the_current_registry_parses(self):
        with open(VARIANTS_ENV) as fh:
            rows = self._read_variants(fh.read())
        self.assertEqual(len(rows), len(data_lines()))
        for row in rows:
            self.assertEqual(len(row), 4, row)

    def test_a_three_column_line_still_parses(self):
        rows = self._read_variants("TO-9  TO-9  TO\n")
        self.assertEqual(rows, [["TO-9", "TO-9", "TO", "-"]])

    def test_a_five_column_line_does_not_break_the_first_four(self):
        rows = self._read_variants("TO-9  TO-9  TO  cap-a,cap-b  future-column\n")
        self.assertEqual(rows[0][:4], ["TO-9", "TO-9", "TO", "cap-a,cap-b"])

    def test_comments_and_blank_lines_are_ignored(self):
        rows = self._read_variants("# header\n\nTO-9  TO-9  TO  x\n  \n")
        self.assertEqual(rows, [["TO-9", "TO-9", "TO", "x"]])


class EveryRegisteredVariantIsResolvable(unittest.TestCase):
    """branch_of/family_of must answer for every registry row — the manifest now depends on
    them, so an unresolvable row would write a malformed images.json instead of crashing."""

    def test_branch_and_family_resolve_for_every_variant(self):
        prog = (f'. "{LIB_SH}"\n'
                'for v in $(read_variants | awk "{print \\$1}"); do\n'
                '  printf "%s\\t%s\\t%s\\n" "$v" "$(branch_of "$v")" "$(family_of "$v")"\n'
                'done')
        r = subprocess.run(["bash", "-c", prog], cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        rows = [l.split("\t") for l in r.stdout.strip().splitlines()]
        self.assertEqual(len(rows), len(data_lines()))
        for variant, branch, family in rows:
            self.assertTrue(branch, f"{variant} has no branch")
            self.assertTrue(family, f"{variant} has no family")

    def test_a_variant_colon_branch_colon_family_row_splits_unambiguously(self):
        """The manifest packs rows as v:branch:family and splits with maxsplit=2, so a
        family containing a colon would still be safe but a VARIANT containing one would
        not. Assert no registry field contains a colon."""
        for row in data_lines():
            for field in row[:3]:
                self.assertNotIn(":", field, row)


if __name__ == "__main__":
    unittest.main()
