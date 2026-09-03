import os
import re
import subprocess
import unittest

HERE = os.path.dirname(__file__)
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
COMMON_SH = os.path.join(ROOT, "k6", "bench", "common.sh")


def source_common(env_overrides, want):
    """Source common.sh with a controlled environment and echo one variable back."""
    env = {k: v for k, v in os.environ.items()
           if k not in ("VARIANT", "VARIANT_FAMILY", "IMAGE_TAG", "API_SVC",
                        "DB_SVC", "PROM_JOB", "API_CONTAINER_RE")}
    env.update(env_overrides)
    return subprocess.run(
        ["bash", "-c", f'. "{COMMON_SH}" >/dev/null 2>&1; printf "%s" "${{{want}}}"'],
        env=env, capture_output=True, text=True)


class DerivedConfig(unittest.TestCase):
    BASE = {"VARIANT": "ES-4-mongo", "VARIANT_FAMILY": "ES", "IMAGE_TAG": "x:latest"}

    def test_api_service_is_family_neutral(self):
        self.assertEqual(source_common(self.BASE, "API_SVC").stdout, "api")

    def test_db_service_is_family_neutral(self):
        self.assertEqual(source_common(self.BASE, "DB_SVC").stdout, "mongo")

    def test_db_service_does_not_collide_with_the_exporter_or_the_init_container(self):
        """DB_SVC is applied as an ANCHORED cadvisor matcher (bench.sh's container guard and
        queries.promql both use name=~), so `mongo` must not be a prefix that also selects
        `mongodb-exporter` or `mongo-init`. Prometheus anchors regexes fully, which is what
        makes the bare name safe -- this pins that the name stays bare."""
        self.assertEqual(source_common(self.BASE, "DB_SVC").stdout, "mongo")

    def test_no_db_user_is_exported(self):
        """mongod runs unauthenticated here; a DB_USER left over from the Postgres harness
        would be dead configuration that looks live."""
        self.assertEqual(source_common(self.BASE, "DB_USER").stdout, "")

    def test_prom_job_is_family_neutral(self):
        self.assertEqual(source_common(self.BASE, "PROM_JOB").stdout, "inventory")

    def test_container_regex_is_the_exact_container_name(self):
        # docker-compose.yml pins `container_name: api`, so there is no <project>-api-N
        # shape left to match around and no COMPOSE_PROJECT_NAME dependence.
        self.assertEqual(source_common(self.BASE, "API_CONTAINER_RE").stdout, "api")

    def test_container_regex_selects_the_api_container_and_nothing_else(self):
        """Pinning the literal string above proves nothing about what it selects — and this
        regex is only ever consumed by Prometheus, which anchors it fully. So apply it the
        way queries.promql's `name=~"$CRE"` does, against the container names the stack
        actually produces (every service, api included, pins a container_name)."""
        cre = source_common(self.BASE, "API_CONTAINER_RE").stdout
        pattern = re.compile(f"^(?:{cre})$")          # Prometheus semantics: fully anchored
        names = ["api", "postgres", "postgres-exporter", "cadvisor", "prometheus",
                 "grafana", "grafana-renderer", "grafana-reporter", "k6"]
        self.assertEqual([n for n in names if pattern.match(n)], ["api"])

    def test_container_regex_does_not_catch_a_foreign_project(self):
        """A leftover container from another Compose project keeps that project's prefix.
        The exact name must not match it — nor the substring `api` inside a longer name."""
        cre = source_common(self.BASE, "API_CONTAINER_RE").stdout
        pattern = re.compile(f"^(?:{cre})$")
        for name in ["otherproject-api-1", "iir-api-1", "api-es", "rapid"]:
            self.assertIsNone(pattern.match(name), name)

    def test_variant_is_taken_from_the_environment(self):
        env = dict(self.BASE, VARIANT="TO-1")
        self.assertEqual(source_common(env, "VARIANT").stdout, "TO-1")

    def test_missing_variant_is_fatal(self):
        self.assert_missing_is_fatal("VARIANT")

    def test_missing_image_tag_is_fatal_with_a_useful_message(self):
        """VARIANT was guarded with `:?` and a message; IMAGE_TAG was not, so bench.sh died
        with a bare `IMAGE_TAG: unbound variable` naming neither the cause nor the fix."""
        stderr = self.assert_missing_is_fatal("IMAGE_TAG")
        self.assertIn("run-suite.sh", stderr)

    def assert_missing_is_fatal(self, missing):
        env = {**os.environ, **self.BASE, missing: ""}
        result = subprocess.run(["bash", "-c", f'. "{COMMON_SH}"'],
                                env=env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0, f"{missing} unset was accepted")
        self.assertIn(missing, result.stderr)
        return result.stderr


if __name__ == "__main__":
    unittest.main()
