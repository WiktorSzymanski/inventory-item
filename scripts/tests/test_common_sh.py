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
    BASE = {"VARIANT": "ES-4", "VARIANT_FAMILY": "ES", "IMAGE_TAG": "x:latest"}

    def test_api_service_is_family_neutral(self):
        self.assertEqual(source_common(self.BASE, "API_SVC").stdout, "api")

    def test_db_service_is_family_neutral(self):
        self.assertEqual(source_common(self.BASE, "DB_SVC").stdout, "postgres")

    def test_prom_job_is_family_neutral(self):
        self.assertEqual(source_common(self.BASE, "PROM_JOB").stdout, "inventory")

    def test_container_regex_is_unanchored_and_hyphen_bounded(self):
        # Prometheus anchors regexes fully, so the leading/trailing .* are required;
        # the hyphens stop it matching a sibling container.
        self.assertEqual(source_common(self.BASE, "API_CONTAINER_RE").stdout, ".*-api-.*")

    def test_container_regex_selects_the_api_replicas_and_nothing_else(self):
        """Pinning the literal string above proves nothing about what it selects — and this
        regex is only ever consumed by Prometheus, which anchors it fully. So apply it the
        way queries.promql's `name=~"$CRE"` does, against the container names the unified
        stack actually produces at REPLICAS=2 (project `iir`, api scaled by deploy.replicas
        and therefore unnamed; every other service pins a container_name)."""
        cre = source_common(self.BASE, "API_CONTAINER_RE").stdout
        pattern = re.compile(f"^(?:{cre})$")          # Prometheus semantics: fully anchored
        names = ["iir-api-1", "iir-api-2", "postgres", "postgres-exporter", "nginx",
                 "cadvisor", "prometheus", "grafana", "grafana-renderer",
                 "grafana-reporter", "k6"]
        self.assertEqual([n for n in names if pattern.match(n)],
                         ["iir-api-1", "iir-api-2"])

    def test_container_regex_is_not_pinned_to_one_project_name(self):
        """COMPOSE_PROJECT_NAME is overridable in scripts/lib.sh, so `iir` must not be
        baked in."""
        cre = source_common(self.BASE, "API_CONTAINER_RE").stdout
        self.assertRegex("otherproject-api-1", f"^(?:{cre})$")

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
