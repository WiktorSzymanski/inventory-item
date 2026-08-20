#!/usr/bin/env bash
# Run the harness test suite.
#
#   scripts/run-tests.sh          # all of it
#   scripts/run-tests.sh -v       # verbose
#
# ONE suite, run ONCE — not once per variant. With a single harness on main there is a
# single set of tests, which is what "the same for all variants" means here. These cover
# bench.sh's label and point handling, evaluate.py's validity gates, and the dashboard spec,
# build and archived-run rewrite.
#
# Stdlib unittest only: no pytest, no conftest.py, no requirements.txt, no Docker and no
# JDK. `-t .` is required because the tests do `from scripts.dashboards import build`, so
# the repository root must be the top-level directory.
#
# Deliberately does NOT call require_tools, which demands a reachable Docker daemon. These
# tests are hermetic; requiring Docker to run them would be a lie. require_not_root stays,
# because a stray __pycache__ written as root breaks every later run.
#
# This does not run the variants' JVM tests. Those live in each branch's src/test and stay
# a per-branch `./gradlew test`.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib.sh
. "$HERE/lib.sh"

require_not_root
command -v python3 >/dev/null || die "required tool not on PATH: python3"

log "harness tests: python3 -m unittest discover -s scripts/tests -t ."
cd "$MAIN_ROOT"
exec python3 -m unittest discover -s scripts/tests -t . "$@"
