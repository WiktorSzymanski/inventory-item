import gzip
import os
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(__file__)
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
BENCH_SH = os.path.join(HERE, "..", "..", "k6", "bench", "bench.sh")
COMMON_SH = os.path.join(HERE, "..", "..", "k6", "bench", "common.sh")

PROVENANCE_VARS = ("VARIANT_GIT_BRANCH", "VARIANT_GIT_COMMIT",
                   "VARIANT_GIT_DIRTY", "VARIANT_HEAD_EPOCH")


def block(marker, path=BENCH_SH):
    """Execute one marked block out of the shipped bench.sh (or common.sh), so these tests
    bind to the real code rather than to a copy of it that can drift."""
    with open(path) as fh:
        script = fh.read()
    return script.split(f"# >>> {marker}")[1].split(f"# <<< {marker}")[0]


def provenance_block(**overrides):
    """Run bench.sh's provenance block with $REPO_ROOT pointed at this checkout of `main`
    and report the four values it settles on."""
    env = {k: v for k, v in os.environ.items() if k not in PROVENANCE_VARS}
    env["REPO_ROOT"] = ROOT
    env.update(overrides)
    script = (block("provenance")
              + '\nprintf "%s\\n%s\\n%s\\n%s\\n" '
                '"$GIT_BRANCH" "$GIT_COMMIT" "$GIT_DIRTY" "$HEAD_EPOCH"')
    result = subprocess.run(["bash", "-c", script], env=env,
                            capture_output=True, text=True, check=True)
    return dict(zip(("branch", "commit", "dirty", "head_epoch"),
                    result.stdout.split("\n")))


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
    "API_CONTAINER_RE": "api",
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


class Provenance(unittest.TestCase):
    """meta.json must describe the code UNDER TEST, not the harness.

    bench.sh's $REPO_ROOT is `main`, which carries no application code, so every fact
    derived from it describes the wrong tree. Two of them are validity checks in
    evaluate.py: `image_fresh` compared the image against `main`'s HEAD (an unrelated
    clock — one docs-only commit here returned INVALID for all eight variants) and
    `git_clean` counted `main/src/`, which does not exist, so it was always 0.
    """

    def test_variant_values_win_when_run_suite_supplies_them(self):
        got = provenance_block(VARIANT_GIT_BRANCH="ES-4",
                               VARIANT_GIT_COMMIT="0123456789abcdef",
                               VARIANT_GIT_DIRTY="3",
                               VARIANT_HEAD_EPOCH="1700000000")
        self.assertEqual(got, {"branch": "ES-4", "commit": "0123456789abcdef",
                               "dirty": "3", "head_epoch": "1700000000"})

    def test_a_dirty_variant_worktree_is_visible_here(self):
        """The whole point of I4: a non-zero count must survive into GIT_DIRTY so the
        guard below it can abort. Before the fix this was pinned to `main`'s absent src/."""
        self.assertEqual(provenance_block(VARIANT_GIT_DIRTY="1")["dirty"], "1")

    def test_falls_back_to_its_own_tree_for_a_direct_invocation(self):
        """k6/README.md documents running bench.sh by hand, and the variant branches carry
        their own copy where $REPO_ROOT really is the tree under test. Dropping the
        fallback would break both."""
        got = provenance_block()
        expected_commit = subprocess.run(
            ["git", "-C", ROOT, "rev-parse", "HEAD"],
            capture_output=True, text=True, check=True).stdout.strip()
        expected_epoch = subprocess.run(
            ["git", "-C", ROOT, "log", "-1", "--format=%ct"],
            capture_output=True, text=True, check=True).stdout.strip()
        self.assertEqual(got["commit"], expected_commit)
        self.assertEqual(got["head_epoch"], expected_epoch)

    def test_head_epoch_is_not_recomputed_after_the_provenance_block(self):
        """A second `HEAD_EPOCH=$(git ... log)` further down would silently reinstate the
        bug for image_fresh while every test above still passed."""
        with open(BENCH_SH) as fh:
            script = fh.read()
        self.assertEqual(script.count("HEAD_EPOCH="), 1)


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

    def test_point_field_records_only_the_resolved_value(self):
        # POINT_RESOLVED is normalised ("W-base,C11" -> "W-base-C11"). There is deliberately
        # NO fallback to raw POINT: see UnresolvedPointIsRefused below.
        self.assertIn('"point": "${POINT_RESOLVED:-}"', self.script)
        self.assertNotIn('${POINT_RESOLVED:-${POINT:-}}', self.script)


class KnobForwarding(unittest.TestCase):
    """k6 >= 2.0 does not forward system env into __ENV, so a knob missing from bench.sh's
    KNOBS array never reaches the script no matter who set it."""

    def knobs(self):
        with open(BENCH_SH) as fh:
            body = fh.read().split("KNOBS=(")[1].split(")")[0]
        return body.split()

    def test_warmup_rate_is_forwarded(self):
        # Without this the rate resolved from points.env is silently dropped and every run
        # aborts at k6 init with "WARMUP_RATE is not set".
        self.assertIn("WARMUP_RATE", self.knobs())

    def test_warmup_iterations_and_cap_are_forwarded(self):
        self.assertIn("WARMUP_ITERATIONS", self.knobs())
        self.assertIn("WARMUP_MAX_DURATION", self.knobs())


class UnresolvedPointIsRefused(unittest.TestCase):
    """`POINT=W-hot ./k6/bench/bench.sh` used to run the DEFAULT workload.

    resolve_point() lives in scripts/lib.sh and its only caller is scripts/run-suite.sh, so
    a direct bench.sh invocation — which k6/README.md documents — expanded nothing. The run
    used config.js's defaults (DISTINCT_ITEMS=6) while meta.json recorded `point: W-hot`,
    which means 8, and compare.py grouped it with genuine W-hot runs. Keeping one resolution
    path means bench.sh must refuse, not guess.
    """

    def guard(self, **env_overrides):
        env = {k: v for k, v in os.environ.items()
               if k not in ("POINT", "POINT_RESOLVED")}
        env.update(env_overrides)
        # die() is defined in common.sh, which this block does not source; stub it so the
        # block's own control flow is what decides the exit status.
        script = ('die() { printf "FATAL: %s\\n" "$*" >&2; exit 1; }\n'
                  + block("point-guard") + '\necho ok')
        return subprocess.run(["bash", "-c", script], env=env,
                              capture_output=True, text=True)

    def test_unresolved_point_aborts(self):
        r = self.guard(POINT="W-hot")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("W-hot", r.stderr)

    def test_resolved_point_proceeds(self):
        r = self.guard(POINT="W-base,C11", POINT_RESOLVED="W-base-C11")
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_no_point_at_all_proceeds(self):
        """The overwhelmingly common case: bench.sh run with no point in sight."""
        r = self.guard()
        self.assertEqual(r.returncode, 0, r.stderr)


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

    def test_an_unresolved_point_is_never_recorded(self):
        """This asserted the opposite until the raw-POINT fallback was removed.

        The fallback fired exactly when resolve_point had NOT run — a direct bench.sh
        invocation — so it recorded a point whose knobs were never applied. It could not be
        made correct by tightening the fallback either: the failure is that the run used the
        wrong workload, and no meta.json value fixes that. bench.sh now refuses the run
        outright (UnresolvedPointIsRefused), and this asserts the recording side.
        """
        rendered = render_meta_heredoc(POINT_RESOLVED="", POINT="W-raw")
        self.assertIn('"point": ""', rendered)
        self.assertNotIn("W-raw", rendered)

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


# ---------------------------------------------------------------- cadvisor guard

def cadvisor_line(cgroup_id, name, image="", extra_labels=()):
    """One container_memory_rss line shaped the way cadvisor v0.49 really emits it.

    Two details of that shape are the whole reason the guard needs testing. cadvisor writes
    `name=""` EXPLICITLY on every cgroup it cannot attribute to a container -- Prometheus
    drops empty labels, cadvisor's text exposition does not -- so a blind collector still
    produces well-formed container_memory_rss lines. And every series carries a pile of
    container_label_* labels whose names end in arbitrary text, so a substring match for
    name="api" is not the same question as a match on the `name` label.
    """
    labels = [f'container_label_com_docker_compose_project="{"iir" if name else ""}"',
              f'container_label_com_docker_compose_service="{name}"']
    labels.extend(extra_labels)
    labels.extend([f'id="{cgroup_id}"', f'image="{image}"', f'name="{name}"'])
    return "container_memory_rss{" + ",".join(labels) + "} 5.840896e+07 1787584246837"


# What cadvisor published for the whole of the 2026-08-22 campaign: host cgroups only, every
# one of them carrying name="". Trimmed from 112 series to the shape-bearing ones.
HOST_CGROUPS_ONLY = "\n".join([
    cadvisor_line("/", ""),
    cadvisor_line("/init.scope", ""),
    cadvisor_line("/system.slice", ""),
    cadvisor_line("/system.slice/ModemManager.service", ""),
    cadvisor_line("/user.slice/user-1000.slice", ""),
])

# What --docker_only leaves when cadvisor cannot see containers: the machine root, alone.
DOCKER_ONLY_BLIND = cadvisor_line("/", "")

WORKING = "\n".join([
    cadvisor_line("/", ""),
    cadvisor_line("/system.slice/docker-aaa.scope", "mongo", "mongo:7"),
    cadvisor_line("/system.slice/docker-bbb.scope", "api", "inventory-reservation-to-3:latest"),
    cadvisor_line("/system.slice/docker-ccc.scope", "cadvisor", "gcr.io/cadvisor/cadvisor:v0.49.1"),
])


def cadvisor_probe(body, db_svc="mongo", api_re="api"):
    """Run bench.sh's real probe over a /metrics body; True when it reports containers seen."""
    env = dict(os.environ)
    env["DB_SVC"] = db_svc
    env["API_CONTAINER_RE"] = api_re
    result = subprocess.run(
        ["bash", "-c", block("cadvisor-probe") + "\ncadvisor_sees_containers"],
        input=body, env=env, capture_output=True, text=True)
    return result.returncode == 0


class CadvisorProbe(unittest.TestCase):
    """The guard that would have stopped the 2026-08-22 campaign on its first run.

    cadvisor stayed `up` and container_scrape_error stayed 0 for 40 hours while attributing
    nothing to a container, so neither of those is the signal. The only reliable signal is
    whether the two containers whose panels the run has to record are actually named in the
    exposition.
    """

    def test_a_working_cadvisor_passes(self):
        self.assertTrue(cadvisor_probe(WORKING))

    def test_host_cgroups_only_fails(self):
        """The exact 2026-08-22 failure: 112 well-formed series, not one container."""
        self.assertFalse(cadvisor_probe(HOST_CGROUPS_ONLY))

    def test_docker_only_blind_output_fails(self):
        """What --docker_only leaves behind when the same fault recurs."""
        self.assertFalse(cadvisor_probe(DOCKER_ONLY_BLIND))

    def test_missing_api_container_fails(self):
        body = "\n".join([cadvisor_line("/", ""),
                          cadvisor_line("/system.slice/docker-aaa.scope", "mongo")])
        self.assertFalse(cadvisor_probe(body))

    def test_missing_db_container_fails(self):
        body = "\n".join([cadvisor_line("/", ""),
                          cadvisor_line("/system.slice/docker-bbb.scope", "api")])
        self.assertFalse(cadvisor_probe(body))

    def test_empty_body_fails(self):
        """curl -sf against a cadvisor that is not listening yet yields nothing at all."""
        self.assertFalse(cadvisor_probe(""))

    def test_a_container_label_ending_in_name_does_not_satisfy_the_probe(self):
        """Images commonly ship a `name` label, which cadvisor exposes as
        container_label_name. An unanchored search for name="api" is satisfied by that on a
        host cgroup whose real name label is empty -- i.e. by exactly the blind collector this
        guard exists to catch."""
        body = cadvisor_line("/system.slice", "",
                             extra_labels=['container_label_name="api"',
                                           'container_label_name="mongo"'])
        self.assertFalse(cadvisor_probe(body))

    def test_the_api_regex_is_applied_as_a_regex(self):
        """API_CONTAINER_RE is a regex in every other consumer (Prometheus name=~), so the
        guard must not degrade to a literal comparison for a multi-container variant."""
        body = "\n".join([cadvisor_line("/system.slice/docker-aaa.scope", "mongo"),
                          cadvisor_line("/system.slice/docker-bbb.scope", "iir-api-1")])
        self.assertTrue(cadvisor_probe(body, api_re="api|.*-api-[0-9]+"))

    def test_a_partial_name_match_does_not_pass(self):
        """mongodb-exporter must not stand in for mongo, and the trap is sharper here than on
        the Postgres stack: `mongo` is a strict PREFIX of both `mongodb-exporter` and
        `mongo-init`, so an unanchored match would find two impostors rather than one.
        Prometheus anchors name=~ fully,
        so a guard that accepted a prefix would pass runs whose panels then render nothing."""
        body = "\n".join([cadvisor_line("/system.slice/docker-aaa.scope", "mongodb-exporter"),
                          cadvisor_line("/system.slice/docker-bbb.scope", "api")])
        self.assertFalse(cadvisor_probe(body))


SERVICE_LOG_HARNESS = """
API_SVC=api
DB_SVC=mongo
log() {{ printf '%s\n' "$*" >&2; }}
# Stub compose. Records its argv one line per service, then emits a body so the archive is
# not merely present but correct.
dc() {{
    printf '%s\n' "$*" >>"$ARGV_LOG"
    printf 'stdout of $*\n'
    return {rc}
}}
{body}
capture_service_logs "$DEST" "$SINCE"
echo "rc=$?"
"""


def capture_service_logs(dest, since="", rc=0):
    """Run the real capture helper out of common.sh against a stub `dc`.

    Returns (stdout, recorded compose argv per service, {service: decompressed archive}).
    """
    script = SERVICE_LOG_HARNESS.format(body=block("service-logs", COMMON_SH), rc=rc)
    argv_log = os.path.join(dest, "argv")
    os.makedirs(dest, exist_ok=True)
    env = dict(os.environ)
    env.update({"DEST": os.path.join(dest, "logs"), "SINCE": since,
                "ARGV_LOG": argv_log})
    env.pop("SERVICE_LOG_SVCS", None)
    result = subprocess.run(["bash", "-euo", "pipefail", "-c", script],
                            env=env, capture_output=True, text=True)
    argv = []
    if os.path.exists(argv_log):
        with open(argv_log) as fh:
            argv = fh.read().splitlines()
    archives = {}
    logdir = os.path.join(dest, "logs")
    for name in sorted(os.listdir(logdir)) if os.path.isdir(logdir) else []:
        with gzip.open(os.path.join(logdir, name), "rt") as fh:
            archives[name] = fh.read()
    return result, argv, archives


class ServiceLogCapture(unittest.TestCase):
    """A run must keep what the service itself said.

    Before this, a run archived k6's view and Prometheus' view and nothing else — so a run
    that died at reset.sh's health timeout left no record of why the API never came up, and
    no artifact recorded which code path was live (TO-2-fix-A logs `[OUTBOX] drain
    mode=WATERMARK` at startup and nothing else does).
    """

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def test_one_gzipped_archive_per_service(self):
        _, _, archives = capture_service_logs(self.tmp, since="100")
        self.assertEqual(sorted(archives), ["api.log.gz", "mongo.log.gz"])

    def test_the_archive_holds_what_the_container_emitted(self):
        _, _, archives = capture_service_logs(self.tmp, since="100")
        self.assertIn("stdout of", archives["api.log.gz"])

    def test_since_scopes_the_dump_to_this_run(self):
        """reset.sh restarts the api container rather than recreating it, so json-file still
        holds the previous run's output. Without --since a repeated direct bench.sh run would
        silently concatenate it into this run's archive."""
        _, argv, _ = capture_service_logs(self.tmp, since="1755000000")
        self.assertTrue(all("--since 1755000000" in line for line in argv), argv)

    def test_since_is_omitted_when_no_anchor_is_given(self):
        _, argv, _ = capture_service_logs(self.tmp, since="")
        self.assertTrue(all("--since" not in line for line in argv), argv)

    def test_timestamps_are_requested(self):
        """docker's own timestamp is the only thing that lines the log up against the run
        timeline in meta.json; logback's pattern has no timezone."""
        _, argv, _ = capture_service_logs(self.tmp, since="100")
        self.assertTrue(all("--timestamps" in line for line in argv), argv)

    def test_a_failing_compose_call_is_not_fatal(self):
        """This runs from an EXIT trap, including the one that fires while bench.sh is
        already dying of something else. It must never replace that exit status."""
        result, _, _ = capture_service_logs(self.tmp, since="100", rc=1)
        self.assertIn("rc=0", result.stdout)
        self.assertIn("non-fatal", result.stderr)

    def test_a_failing_compose_call_still_records_why(self):
        """2>&1 into the archive on purpose: compose's complaint is what the file should
        hold rather than nothing at all."""
        _, _, archives = capture_service_logs(self.tmp, since="100", rc=1)
        self.assertEqual(sorted(archives), ["api.log.gz", "mongo.log.gz"])


class ServiceLogTrap(unittest.TestCase):
    """The capture has to be reachable from the failure paths, not only the happy one."""

    def setUp(self):
        with open(BENCH_SH) as fh:
            self.script = fh.read()

    def test_the_trap_is_installed(self):
        self.assertIn("trap 'capture_service_logs", self.script)

    def test_the_trap_is_installed_after_t_reset_and_before_reset_sh(self):
        """$T_RESET is the window anchor, so the trap cannot precede it; reset.sh's health
        timeout is one of the failures worth capturing, so it cannot follow it either."""
        t_reset = self.script.index('T_RESET="$(date +%s)"')
        trap = self.script.index("trap 'capture_service_logs")
        reset = self.script.index('"$HERE/reset.sh"')
        self.assertLess(t_reset, trap)
        self.assertLess(trap, reset)

    def test_the_capture_is_not_also_called_inline(self):
        """A second call would double the work and, worse, write a partial archive that the
        trap then overwrites — making the artifact depend on which path exited.

        Comment lines are stripped first: the block above the trap names the function twice
        while explaining it, and counting prose would make this a tripwire on wording."""
        code = [l for l in self.script.splitlines() if not l.lstrip().startswith("#")]
        self.assertEqual("\n".join(code).count("capture_service_logs"), 1)


if __name__ == "__main__":
    unittest.main()
