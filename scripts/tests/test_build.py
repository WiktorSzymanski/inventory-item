import json
import os
import re
import unittest

from scripts.dashboards import build

# What the unified stack actually emits, per label the live dashboard filters on.
#
# job     monitoring/prometheus/prometheus.yml declares exactly these three scrape jobs.
# database mongodb_dbstats_* reports every database on the server, admin/config/local
#          included, which is why the $db variable carries a /^inventory$/ regex.
# name    cadvisor's container name label. Every service in docker-compose.yml pins a
#         container_name, the single un-scaled `api` included, so COMPOSE_PROJECT_NAME
#         never appears here.
STACK_LABEL_VALUES = {
    "job": ["cadvisor", "inventory", "mongo"],
    "database": ["admin", "config", "inventory", "local"],
    "name": ["api", "cadvisor", "grafana", "grafana-renderer", "grafana-reporter",
             "k6", "mongo", "mongo-init", "mongodb-exporter", "prometheus"],
}

# Which label each query variable draws its options from.
VAR_LABEL = {"job": "job", "db": "database"}

# The option Grafana must land on for `current: {}` to resolve usefully.
VAR_EXPECTED = {"job": ["inventory"], "db": ["inventory"]}

# $dbc and $apic are NOT here: they are constants now. See ContainerVariablesAreConstants.
VAR_CONST_EXPECTED = {"dbc": "mongo", "apic": "api"}


def prometheus_selects(pattern, values):
    """Apply a matcher the way Prometheus applies name=~: fully anchored, whole value."""
    return [v for v in values if re.fullmatch(pattern, v)]


def grafana_filter(regex, values):
    """Apply a Grafana variable `regex` the way Grafana does: strip the /.../ delimiters,
    keep an option when the pattern is found anywhere in it. These patterns carry their own
    ^...$ anchors, so JS RegExp and Python re agree on every one of them."""
    body = regex
    if body.startswith("/") and body.rfind("/") > 0:
        body = body[1:body.rfind("/")]
    pattern = re.compile(body)
    return [v for v in values if pattern.search(v)]


class LiveVariableRegexesSelectRealValues(unittest.TestCase):
    """The regexes must match the label values the stack EMITS.

    Asserting only that a regex is non-empty (which is all the older test did) passes
    happily on a regex that matches nothing -- and a regex matching nothing is the worse
    failure of the two. Grafana leaves `current` as {}, `$job` expands to the empty string,
    every panel queries {job=""} and returns no data, and each run's report.pdf renders
    blank with nothing logged anywhere. That is what shipped when the stack's names were
    unified: `/^inventory-.*/` demanded a hyphen after "inventory", `/^postgres-(?:to|es)$/`
    a family suffix on the DB container, and `/api-(?:to|es)-[0-9]+$/` one on the API
    containers -- 3 of the 4 selected nothing.
    """

    def setUp(self):
        self.vars = {v["name"]: v for v in build.build_live()["templating"]["list"]
                     if v.get("type") == "query"}

    def test_every_query_variable_is_covered_by_this_test(self):
        """A new query variable must arrive with its expected label values, or the rest of
        this class silently stops covering it."""
        self.assertEqual(set(self.vars), set(VAR_EXPECTED))

    def test_each_regex_selects_exactly_the_expected_options(self):
        for name, expected in VAR_EXPECTED.items():
            with self.subTest(var=name):
                selected = grafana_filter(self.vars[name]["regex"],
                                          STACK_LABEL_VALUES[VAR_LABEL[name]])
                self.assertEqual(selected, expected)

    def test_no_regex_selects_nothing(self):
        """Stated separately from the equality above because it is the specific failure
        that shipped, and it is the one with no visible symptom."""
        for name in VAR_EXPECTED:
            with self.subTest(var=name):
                selected = grafana_filter(self.vars[name]["regex"],
                                          STACK_LABEL_VALUES[VAR_LABEL[name]])
                self.assertTrue(selected, f"${name}: regex selects no option, $%s expands "
                                          "empty and every panel using it goes blank" % name)

    def test_no_regex_uses_a_capturing_group(self):
        """Grafana substitutes the FIRST capturing group's match for the option's value when
        one is present, so `(to|es)` turned the option "postgres-to" into "to"."""
        for name, var in self.vars.items():
            with self.subTest(var=name):
                self.assertEqual(re.compile(var["regex"].strip("/")).groups, 0)


class ContainerVariablesAreConstants(unittest.TestCase):
    """$dbc and $apic must not be query variables.

    They used to be, over label_values(container_memory_rss, name) -- which asks the very
    collector the container panels query. When cadvisor stops attributing cgroups to
    containers, that takes the variables down with it: they resolve to "", the panel matcher
    becomes name=~"|", and because cadvisor writes name="" EXPLICITLY on every cgroup it
    cannot attribute, that matches the host rather than matching nothing. For the whole
    2026-08-22 campaign the container panels therefore rendered machine-wide CPU and RSS --
    the desktop session included -- and every report.pdf looked entirely reasonable.

    bench-runs.json already declared both as constants and correctly rendered empty over the
    same data, which is the comparison that identified this.
    """

    def setUp(self):
        self.vars = {v["name"]: v for v in build.build_live()["templating"]["list"]}

    def test_both_are_declared_constant(self):
        for name in VAR_CONST_EXPECTED:
            with self.subTest(var=name):
                self.assertEqual(self.vars[name]["type"], "constant")

    def test_each_carries_a_resolved_current_value(self):
        """A constant with `current: {}` interpolates empty just like an unresolved query
        variable, so declaring the type is only half of the fix."""
        for name, value in VAR_CONST_EXPECTED.items():
            with self.subTest(var=name):
                self.assertEqual(self.vars[name]["current"].get("value"), value)

    def test_the_db_container_selects_mongo_and_not_the_exporter_or_init(self):
        """`mongodb-exporter` and `mongo-init` also carry a `name` label, and `mongo` is a
        prefix of both. Prometheus anchors name=~ fully, which is the only reason the bare
        name is safe -- this pins that, because over-matching here would silently fold the
        exporter's and the init container's CPU and RSS into the database's."""
        selected = prometheus_selects(VAR_CONST_EXPECTED["dbc"], STACK_LABEL_VALUES["name"])
        self.assertEqual(selected, ["mongo"])

    def test_the_api_container_is_an_exact_match(self):
        """The api pins `container_name: api`, so this must select that and only that --
        not a leftover container from another Compose project, and not a longer name that
        merely contains `api`."""
        selected = prometheus_selects(VAR_CONST_EXPECTED["apic"],
                                      ["api", "otherproj-api-1", "api-es", "cadvisor"])
        self.assertEqual(selected, ["api"])

    def test_neither_matches_an_unnamed_cgroup(self):
        """The specific failure: cadvisor's host cgroups carry name="". Neither constant may
        select it, however the collector is behaving."""
        for name, value in VAR_CONST_EXPECTED.items():
            with self.subTest(var=name):
                self.assertEqual(prometheus_selects(value, [""]), [])


class ContainerPanelsExcludeUnnamedSeries(unittest.TestCase):
    """Belt-and-braces on top of the constants: the cadvisor panels carry name!="" so that
    no future variable change can point them back at the host."""

    def test_every_cadvisor_panel_target_excludes_the_empty_name(self):
        live = build.build_live()
        checked = 0
        for panel in live["panels"]:
            for target in panel.get("targets", []):
                expr = target.get("expr", "")
                if "container_" not in expr:
                    continue
                checked += 1
                self.assertIn('name!=""', expr,
                              f"{panel['title']}: cadvisor target without name!=\"\": {expr}")
        self.assertEqual(checked, 5, "expected the 5 cadvisor targets across the 3 panels")


class CommittedJsonMatchesTheGenerator(unittest.TestCase):
    """monitoring/grafana/provisioning/dashboards/*.json is generated output. A hand-edit
    there is invisible until the next `python3 -m scripts.dashboards.build` silently reverts
    it -- or, worse, until it does not and the file and the generator disagree forever."""

    def committed(self, name):
        path = os.path.join(build.OUT_DIR, f"{name}.json")
        with open(path) as fh:
            return json.load(fh)

    def test_the_dashboard_json_is_current(self):
        self.assertEqual(self.committed("the-dashboard"), build.build_live())


class GeneratedDashboards(unittest.TestCase):
    def setUp(self):
        self.live = build.build_live()

    def test_live_keeps_the_reporter_uid(self):
        """bench.sh renders /api/v5/report/the-dashboard for every run's report.pdf."""
        self.assertEqual(self.live["uid"], "the-dashboard")

    def test_panel_ids_are_unique(self):
        ids = [p["id"] for p in self.live["panels"]]
        self.assertEqual(len(ids), len(set(ids)), "the-dashboard: duplicate panel ids")

    def test_every_variable_used_is_declared(self):
        declared = {v["name"] for v in self.live["templating"]["list"]}
        for panel in self.live["panels"]:
            for target in panel.get("targets", []):
                for token in ("$job", "$db", "$dbc", "$apic"):
                    if token in target.get("expr", ""):
                        self.assertIn(token[1:], declared,
                                      f"the-dashboard/{panel['title']}: {token} not declared")

    def test_live_dashboard_is_pinned_to_the_live_prometheus(self):
        """The live dashboard has exactly one job: show the stack that is running now.

        It used to carry a `ds` datasource picker so it could double as an archived-run
        viewer, pointed at "Prometheus Replay" with the run's absolute window typed into the
        time picker. That is `bench-runs`' job now, and it does it without the two failure
        modes the picker had: a dashboard left on the archive silently shows stale data during
        the next live run, and one left on the live Prometheus after `run-suite.sh`'s
        `down -v` renders every variable-filtered panel as "No data" while the unfiltered ones
        look fine -- which reads as a corrupt archive rather than an unresolved variable.

        So: no `ds` variable, and every panel, target and query variable hard-pinned to the
        live Prometheus. Nothing on this dashboard can reach the archive.
        """
        live_ds = {"type": "prometheus", "uid": "prometheus"}
        names = {v["name"]: v for v in self.live["templating"]["list"]}
        self.assertNotIn("ds", names)
        for var in names.values():
            if var["type"] == "query":
                self.assertEqual(var.get("datasource"), live_ds,
                                 f"the-dashboard variable ${var['name']} is not on the live Prometheus")
        for panel in self.live["panels"]:
            if "datasource" in panel:
                self.assertEqual(panel["datasource"], live_ds,
                                 f"the-dashboard/{panel.get('title')}: not on the live Prometheus")
            for target in panel.get("targets", []):
                self.assertEqual(target["datasource"], live_ds,
                                 f"the-dashboard/{panel.get('title')}: target not on the live Prometheus")

    def test_no_panel_overflows_the_grid(self):
        for panel in self.live["panels"]:
            pos = panel["gridPos"]
            self.assertLessEqual(pos["x"] + pos["w"], 24, f"{panel.get('title')} overflows")

    def test_no_panels_overlap(self):
        """_layout must not place two panels on the same grid cell. Regression test for a bug
        where wrapping advanced `y` by the INCOMING panel's height instead of the height of the
        row just completed, which overlapped rows whenever a wrap point followed a taller panel."""
        claimed = {}
        for panel in self.live["panels"]:
            if panel.get("type") == "row":
                continue
            pos = panel["gridPos"]
            for x in range(pos["x"], pos["x"] + pos["w"]):
                for y in range(pos["y"], pos["y"] + pos["h"]):
                    cell = (x, y)
                    self.assertNotIn(
                        cell, claimed,
                        f"the-dashboard: {panel.get('title')!r} overlaps "
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


if __name__ == "__main__":
    unittest.main()
