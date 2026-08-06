import os
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

    def test_variant_is_taken_from_the_environment(self):
        env = dict(self.BASE, VARIANT="TO-1")
        self.assertEqual(source_common(env, "VARIANT").stdout, "TO-1")

    def test_missing_variant_is_fatal(self):
        env = {k: v for k, v in self.BASE.items() if k != "VARIANT"}
        result = subprocess.run(
            ["bash", "-c", f'. "{COMMON_SH}"'],
            env={**os.environ, **env, "VARIANT": ""},
            capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("VARIANT", result.stderr)


if __name__ == "__main__":
    unittest.main()
