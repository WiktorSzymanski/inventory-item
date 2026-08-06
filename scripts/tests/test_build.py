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

    def test_live_dashboard_has_a_switchable_datasource_variable(self):
        """The live dashboard can be pointed at the replay archive from the dropdown
        (Task 8): every panel/target uses ${ds}, not a fixed uid, and the default is
        explicitly the live Prometheus so switching is opt-in, not accidental."""
        names = {v["name"]: v for v in self.live["templating"]["list"]}
        self.assertIn("ds", names)
        self.assertEqual(names["ds"]["type"], "datasource")
        self.assertEqual(names["ds"]["current"]["uid"], "prometheus")
        for panel in self.live["panels"]:
            if "datasource" in panel:
                self.assertEqual(panel["datasource"], {"type": "prometheus", "uid": "${ds}"},
                                  f"the-dashboard/{panel.get('title')}: not on ${{ds}}")
            for target in panel.get("targets", []):
                self.assertEqual(target["datasource"], {"type": "prometheus", "uid": "${ds}"},
                                  f"the-dashboard/{panel.get('title')}: target not on ${{ds}}")

    def test_archived_dashboard_stays_pinned_to_the_replay_datasource(self):
        """bench-replay must never expose the switch: it only ever holds replayed
        dump.json data, so pointing it at live Prometheus would be nonsensical."""
        names = {v["name"] for v in self.archived["templating"]["list"]}
        self.assertNotIn("ds", names)
        for panel in self.archived["panels"]:
            if "datasource" in panel:
                self.assertEqual(panel["datasource"]["uid"], "prometheus-replay",
                                  f"bench-replay/{panel.get('title')}: not pinned to prometheus-replay")
            for target in panel.get("targets", []):
                self.assertEqual(target["datasource"]["uid"], "prometheus-replay",
                                  f"bench-replay/{panel.get('title')}: target not pinned to prometheus-replay")

    def test_no_panel_overflows_the_grid(self):
        for dashboard in (self.live, self.archived):
            for panel in dashboard["panels"]:
                pos = panel["gridPos"]
                self.assertLessEqual(pos["x"] + pos["w"], 24, f"{panel.get('title')} overflows")

    def test_no_panels_overlap(self):
        """_layout must not place two panels on the same grid cell. Regression test for a bug
        where wrapping advanced `y` by the INCOMING panel's height instead of the height of the
        row just completed, which overlapped rows whenever a wrap point followed a taller panel."""
        for dashboard in (self.live, self.archived):
            claimed = {}
            for panel in dashboard["panels"]:
                if panel.get("type") == "row":
                    continue
                pos = panel["gridPos"]
                for x in range(pos["x"], pos["x"] + pos["w"]):
                    for y in range(pos["y"], pos["y"] + pos["h"]):
                        cell = (x, y)
                        self.assertNotIn(
                            cell, claimed,
                            f"{dashboard['uid']}: {panel.get('title')!r} overlaps "
                            f"{claimed.get(cell)!r} at cell {cell}")
                        claimed[cell] = panel.get("title")

    def test_live_query_variables_have_a_narrowing_regex(self):
        """Grafana resolves an empty `current` ({}) to the first option under sort=1
        (alphabetical) across ALL variant families' label values -- e.g. label_values(up, job)
        returns ["cadvisor", "inventory-to", "postgres"], so $job defaults to "cadvisor" and
        every panel using {job="$job"} queries a job with none of the expected metrics. A `regex`
        that narrows the options to exactly the one matching this branch's family fixes the
        default without hardcoding a family name into the file."""
        names = {v["name"]: v for v in self.live["templating"]["list"]}
        for name, var in names.items():
            if var.get("type") != "query":
                continue
            self.assertTrue(var.get("regex"), f"$({name}): query variable has no narrowing regex")

    def test_build_is_deterministic(self):
        self.assertEqual(build.build_live(), self.live)
        self.assertEqual(build.build_archived(), self.archived)


if __name__ == "__main__":
    unittest.main()
