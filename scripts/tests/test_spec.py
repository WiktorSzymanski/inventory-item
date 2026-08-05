import json
import os
import unittest

from scripts.dashboards import spec

FIXTURE = os.path.join(os.path.dirname(__file__), "fixtures", "mini-dump.json")


class SpecInvariants(unittest.TestCase):
    def all_panels(self):
        return [p for section in spec.SECTIONS for p in section.panels]

    def test_no_duplicate_live_expression(self):
        """The whole point of the merge: one metric, queried in one place."""
        seen = {}
        for panel in self.all_panels():
            for target in panel.targets:
                key = " ".join(target.expr.split())
                if key in spec.DUP_EXEMPT:
                    continue
                self.assertNotIn(
                    key, seen,
                    f"expression duplicated in {panel.title!r} and {seen.get(key)!r}: {key}")
                seen[key] = panel.title

    def test_every_panel_has_a_unit_and_title(self):
        for panel in self.all_panels():
            self.assertTrue(panel.title, "panel without a title")
            self.assertTrue(panel.unit, f"{panel.title}: no unit")

    def test_panel_widths_fill_whole_rows(self):
        for section in spec.SECTIONS:
            width = sum(p.w for p in section.panels)
            self.assertEqual(width % 24, 0, f"{section.title}: widths sum to {width}, not a multiple of 24")

    def test_archived_targets_reference_real_dump_keys(self):
        """Every replay_step metric= / replay_series metric= must exist in a real dump.json."""
        with open(FIXTURE) as fh:
            dump = json.load(fh)
        step_keys = set(dump["per_step"][0]["scalars"]) | set(dump["per_step"][0]["derived"])
        series_keys = set(dump["series"])
        summary_keys = set(dump["scalars"]) | set(dump["derived"])
        known = {"replay_step": step_keys, "replay_series": series_keys, "replay_summary": summary_keys}
        for panel in self.all_panels():
            for target in panel.archived or []:
                for family, keys in known.items():
                    if not target.expr.startswith(family + "{"):
                        continue
                    for referenced in spec.metric_labels(target.expr):
                        self.assertIn(referenced, keys | spec.FIXTURE_GAPS,
                                      f"{panel.title}: {family} metric={referenced!r} is not a dump.json key")


if __name__ == "__main__":
    unittest.main()
