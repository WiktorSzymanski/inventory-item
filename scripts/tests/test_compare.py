import importlib.util
import os
import unittest

HERE = os.path.dirname(__file__)
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
COMPARE = os.path.join(ROOT, "k6", "bench", "compare.py")


def load_compare():
    spec = importlib.util.spec_from_file_location("compare_mod", COMPARE)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class CoreColumns(unittest.TestCase):
    def setUp(self):
        self.core = load_compare().COLUMNS["core"]

    def test_core_table_shows_the_point(self):
        self.assertIn("point", [header for header, _, _ in self.core])

    def test_point_reads_the_meta_field_not_the_directory_name(self):
        path = next(p for h, p, _ in self.core if h == "point")
        self.assertEqual(path, "meta.point")

    def test_point_sits_next_to_the_knobs_it_names(self):
        # A point is a name for the items/lines/payloadB/reserveMs group; separating them
        # in the table is what lets a mislabelled run hide.
        headers = [h for h, _, _ in self.core]
        self.assertEqual(headers.index("point") + 1, headers.index("items"))


if __name__ == "__main__":
    unittest.main()
