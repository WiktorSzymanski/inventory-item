"""The monitoring stack has to be configured so that a broken collector is LOUD.

Both assertions here exist because of the 2026-08-22 campaign, where cadvisor ran for the
lifetime of one Docker daemon without ever attributing a cgroup to a container. It stayed
`up`, `container_scrape_error` stayed 0, and it published 112 host cgroup series -- the
machine root, every systemd service, the whole GNOME session -- with `name=""` on all of
them. Nothing in the stack noticed, and all 14 archived runs carry host-wide numbers where
per-container ones belong.

    --docker_only    cadvisor then publishes the machine root and real containers, nothing
                     else. A blind cadvisor emits `/` alone, which no panel can mistake for
                     a container, instead of 112 plausible-looking series. It also drops
                     ~110 series per scrape out of every archived snapshot.

    track_io_timing  pg_stat_database_blk_read_time / blk_write_time are the only source of
                     real DB I/O wait in this stack, and with the setting off they increase
                     by exactly 0 forever -- which reads as "no I/O wait", not as "not
                     measured".
"""
import os
import re
import unittest

HERE = os.path.dirname(__file__)
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
COMPOSE = os.path.join(ROOT, "docker-compose.yml")


def service_block(name):
    """The body of one top-level service in docker-compose.yml.

    Deliberately not PyYAML: scripts/run-tests.sh promises the suite is stdlib-only and
    hermetic, and a two-space-indented service block needs no parser. The block runs from
    `  <name>:` to the next line indented exactly two spaces.
    """
    with open(COMPOSE) as fh:
        lines = fh.read().splitlines()
    start = next((i for i, ln in enumerate(lines) if ln == f"  {name}:"), None)
    assert start is not None, f"no service {name!r} in docker-compose.yml"
    body = []
    for ln in lines[start + 1:]:
        if re.match(r"^  \S", ln):
            break
        body.append(ln)
    return "\n".join(body)


def uncommented(block):
    """The block with `#` comment lines stripped, so a flag merely DISCUSSED in a comment
    cannot satisfy a test that means to assert it is actually passed."""
    return "\n".join(ln for ln in block.splitlines() if not ln.lstrip().startswith("#"))


class CadvisorReportsContainersOnly(unittest.TestCase):
    def setUp(self):
        self.block = uncommented(service_block("cadvisor"))

    def test_cadvisor_service_was_actually_found(self):
        """A rename would make every other assertion here vacuously pass."""
        self.assertIn("image:", self.block)

    def test_docker_only_is_passed(self):
        self.assertRegex(
            self.block, r"--docker_only(=true)?\b",
            "cadvisor must run with --docker_only: without it a cadvisor that cannot see "
            "containers still publishes ~110 host cgroup series, which is what silently "
            "filled the container panels with whole-machine numbers")


class MongoRunsAsAReplicaSet(unittest.TestCase):
    """The Postgres counterpart of this class asserted `track_io_timing=on`, because without it
    the I/O-wait series increase by exactly 0 and that is indistinguishable from no I/O wait.
    The equivalent trap here is bigger: a mongod started WITHOUT --replSet answers ping, accepts
    every connection, and then fails every single transaction -- and the ES-*-mongo branches wire
    a MongoTransactionManager because an Axon append spans several documents. The run does not
    error, it times out waiting for health, with nothing in the logs naming the cause.
    """

    def setUp(self):
        self.block = uncommented(service_block("mongo"))

    def test_mongo_service_was_actually_found(self):
        self.assertIn("image:", self.block)

    def test_replica_set_is_enabled(self):
        self.assertRegex(
            self.block, r"--replSet",
            "without --replSet, mongod cannot start a transaction; every Axon append fails and "
            "the run presents as a health-check timeout rather than as an error")

    def test_bind_ip_all_is_still_set(self):
        """The flags share one command line; appending one must not drop the other."""
        self.assertRegex(self.block, r"--bind_ip_all")


class ApiWaitsForTheReplicaSet(unittest.TestCase):
    """Starting mongod is not enough, and this is the check that says so.

    rs.initiate() runs in the one-shot mongo-init container. If the api depended on mongo's
    healthcheck instead, it would start against an uninitiated set -- the healthcheck is a ping
    and passes long before a primary exists -- and every command would fail at startTransaction.
    """

    def setUp(self):
        self.block = uncommented(service_block("api"))

    def test_api_waits_for_mongo_init_to_complete(self):
        self.assertRegex(self.block, r"mongo-init:")
        self.assertRegex(
            self.block, r"condition:\s*service_completed_successfully",
            "the api must wait for mongo-init to EXIT, not for mongo to be healthy: a healthy "
            "mongod with no initiated replica set accepts connections and fails every transaction")

    def test_no_postgres_settings_survived(self):
        """This branch's stack has no PostgreSQL; a leftover DB_JDBC_URL would silently win."""
        for leftover in ("DB_JDBC_URL", "DB_R2DBC_URL", "AXON_JDBC_POOL_SIZE"):
            self.assertNotIn(leftover, self.block)

    def test_mongo_uri_names_the_replica_set(self):
        self.assertRegex(
            self.block, r"MONGO_URI:.*replicaSet=rs0",
            "the driver needs replicaSet in the URI to discover a primary it can transact against")


if __name__ == "__main__":
    unittest.main()
