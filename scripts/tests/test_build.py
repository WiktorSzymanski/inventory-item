import unittest

from scripts.dashboards import build


class GeneratedDashboards(unittest.TestCase):
    def setUp(self):
        self.live = build.build_live()
        self.archived = build.build_archived()

    def test_live_keeps_the_reporter_uid(self):
        """bench.sh renders /api/v5/report/the-dashboard for every run's report.pdf."""
        self.assertEqual(self.live["uid"], "the-dashboard")

    def test_panel_ids_are_unique(self):
        for dashboard in (self.live, self.archived):
            ids = [p["id"] for p in dashboard["panels"]]
            self.assertEqual(len(ids), len(set(ids)), f"{dashboard['uid']}: duplicate panel ids")

    def test_every_variable_used_is_declared(self):
        for dashboard in (self.live, self.archived):
            declared = {v["name"] for v in dashboard["templating"]["list"]}
            for panel in dashboard["panels"]:
                for target in panel.get("targets", []):
                    for token in ("$job", "$db", "$dbc", "$apic", "$runs"):
                        if token in target.get("expr", ""):
                            self.assertIn(token[1:], declared,
                                          f"{dashboard['uid']}/{panel['title']}: {token} not declared")

    def test_no_panel_overflows_the_grid(self):
        for dashboard in (self.live, self.archived):
            for panel in dashboard["panels"]:
                pos = panel["gridPos"]
                self.assertLessEqual(pos["x"] + pos["w"], 24, f"{panel.get('title')} overflows")

    def test_build_is_deterministic(self):
        self.assertEqual(build.build_live(), self.live)
        self.assertEqual(build.build_archived(), self.archived)


if __name__ == "__main__":
    unittest.main()
