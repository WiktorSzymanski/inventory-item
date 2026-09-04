"""The optional api -> postgres network delay, and the four ways it can go silently wrong.

Every check here guards a failure that produces a RUN, not an error: a benchmark that reports
a delay it never had, or one that carries a delay nobody asked for. None of it needs Docker --
these are structural assertions over the harness text, in the same spirit as
test_compose_files.py.

What was verified live, once, against Compose v5.5.0 and iproute2 in nicolaka/netshoot, and is
recorded here because the tests below encode the consequences rather than re-measure them:

    delay 2ms   -> host-to-postgres TCP connect 0.013ms -> 2.030ms   (one crossing per RTT)
    delay 100   -> `delay 100ns`                                     (bare number = nanoseconds)
    delay abc   -> `docker compose up -d` still reports "Started"; the container is dead
    down -v --remove-orphans, no profile -> the netem container SURVIVES the teardown
"""
import os
import re
import unittest

HERE = os.path.dirname(__file__)
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

COMPOSE = open(os.path.join(ROOT, "docker-compose.yml")).read()
BENCH = open(os.path.join(ROOT, "k6", "bench", "bench.sh")).read()
LIB = open(os.path.join(ROOT, "scripts", "lib.sh")).read()


def service_block(text, name):
    """Lines of one compose service, by indentation. Stdlib only -- the harness suite has no
    third-party dependencies and gains nothing from PyYAML for a single block."""
    lines = text.splitlines()
    start = next(i for i, l in enumerate(lines) if l == f"  {name}:")
    out = []
    for line in lines[start + 1:]:
        if line.strip() and not line.startswith("    "):
            break
        out.append(line)
    return "\n".join(out)


class NetemService(unittest.TestCase):
    def test_service_exists(self):
        self.assertIn("\n  netem:\n", COMPOSE)

    def test_sits_behind_a_profile(self):
        """Without the profile, `docker compose up` and every `up -d <services>` that names
        postgres would drag the shaper in and silently shape runs that never asked."""
        block = service_block(COMPOSE, "netem")
        self.assertRegex(block, r"profiles:\s*\n\s*- netem")

    def test_joins_the_postgres_namespace(self):
        """The qdisc has to land on postgres' own eth0. A sidecar on its own netns would
        install the delay on an interface no traffic crosses -- and would still look fine to
        `tc qdisc show`."""
        self.assertIn('network_mode: "service:postgres"', service_block(COMPOSE, "netem"))

    def test_has_net_admin(self):
        block = service_block(COMPOSE, "netem")
        self.assertRegex(block, r"cap_add:\s*\n\s*- NET_ADMIN")

    def test_image_is_pinned(self):
        """Same reason grafana/k6 is pinned: an image drifting mid-campaign would change the
        shaping between variants that are supposed to differ only in the application."""
        block = service_block(COMPOSE, "netem")
        image = re.search(r"^\s*image:\s*(\S+)", block, re.M).group(1)
        self.assertNotIn(":latest", image)
        self.assertRegex(image, r":\S+$")

    def test_installs_nothing_when_the_knob_is_empty(self):
        """A netem qdisc with `delay 0` is NOT the unshaped system -- it replaces the default
        qdisc with a 1000-packet queue of its own, so a "zero" arm would compare two shaped
        configurations. The command must therefore guard the add on a non-empty value."""
        block = service_block(COMPOSE, "netem")
        self.assertIn('if [ -n "$$DB_NET_DELAY" ]; then', block)


class BenchWiring(unittest.TestCase):
    def test_supporting_services_do_not_include_netem(self):
        """bench.sh brings the stack up by naming services. netem must not be among them, or
        an unshaped run would start the sidecar."""
        line = re.search(r'dc up -d "\$DB_SVC" [^\n]*(?:\\\n[^\n]*)*', BENCH).group(0)
        self.assertIn("prometheus", line)
        self.assertNotIn("netem", line)

    def test_requires_a_unit_on_the_delay(self):
        """The regex bench.sh actually uses, applied to the values that matter. A bare number
        is NANOSECONDS to tc, so accepting one would install `delay 2ns` and shape nothing
        while meta.json recorded '2'."""
        pattern = re.search(r"re\.fullmatch\(r'([^']+)'", BENCH).group(1)
        rx = re.compile(pattern)
        for good in ("500us", "2ms", "50msec", "1s", "0.5ms", " 2ms "):
            with self.subTest(value=good):
                self.assertTrue(rx.fullmatch(good))
        for bad in ("2", "100", "abc", "", "2 ms extra", "2ns", "2m"):
            with self.subTest(value=bad):
                self.assertIsNone(rx.fullmatch(bad))

    def test_reads_the_qdisc_back_out_of_the_namespace(self):
        """`up -d` reports success even when tc rejected the value and the container died, so
        the installed qdisc is the only trustworthy witness."""
        self.assertIn("tc qdisc show dev eth0", BENCH)
        self.assertIn("*netem*delay*)", BENCH)

    def test_clears_a_shaper_left_by_an_earlier_run(self):
        """teardown() handles this between variants; a repeated direct bench.sh does not get
        one, and would otherwise inherit the previous run's delay without recording it."""
        self.assertIn("docker inspect netem", BENCH)
        self.assertIn("netem_clear", BENCH)
        # netem_clear can die, and `die` inside a command substitution exits only the subshell.
        # Expanded into an argument its status is discarded and the run continues -- verified:
        # the log form printed the FATAL line and carried straight on. Only an assignment puts
        # the status where set -e can see it.
        self.assertIn('STALE_QDISC="$(netem_clear)"', BENCH)
        self.assertNotIn("($(netem_clear))", BENCH)

    def test_meta_records_what_was_observed(self):
        for field in ("db_net_delay", "db_net_jitter", "db_net_qdisc",
                      "db_net_rtt_before_ms", "db_net_rtt_after_ms"):
            with self.subTest(field=field):
                self.assertIn(f'"{field}"', BENCH)

    def test_meta_defaults_are_python_not_json(self):
        """That heredoc is PYTHON source, not JSON. `${VAR:-null}` renders a bare `null`, which
        is a NameError -- and it fires only when the knob is UNSET, i.e. on every ordinary run,
        at the very end after the load and drain are already spent. Caught exactly that way."""
        meta = BENCH[BENCH.index('python3 - "$RUN_DIR/meta.json"'):]
        self.assertNotIn(":-null}", meta)
        self.assertIn("${NETEM_RTT_BEFORE:-None}", meta)
        self.assertIn("${NETEM_RTT_AFTER:-None}", meta)

    def test_installed_after_the_reset(self):
        """Shaping the api's cold start would let a large delay push Flyway plus the Spring
        context past reset.sh's HEALTH_TIMEOUT and fail the run for a reason unrelated to the
        measurement."""
        self.assertLess(BENCH.index('"$HERE/reset.sh"'), BENCH.index("# >>> netem"))
        self.assertLess(BENCH.index("# <<< netem"), BENCH.index("log \"seed:"))


class Teardown(unittest.TestCase):
    def test_teardown_enables_the_netem_profile(self):
        """Verified against Compose v5.5.0: `down` does not remove containers of services whose
        profile is inactive, so a plain teardown tore down the whole stack and left netem."""
        body = re.search(r"teardown\(\) \{(.*?)\n\}", LIB, re.S).group(1)
        self.assertIn("--profile netem", body)
        self.assertIn("down -v", body)


if __name__ == "__main__":
    unittest.main()
