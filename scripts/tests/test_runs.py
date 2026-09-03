import datetime
import json
import os
import re
import sys
import tempfile
import unittest

from scripts.dashboards import runs, spec

OFF = " offset $run"


class OffsetInjection(unittest.TestCase):
    """`offset $run` must land on every vector selector and nowhere else.

    The dashboard's whole re-anchoring mechanism is this rewrite: the time range is pinned to
    a fixed anchor and each run is reached by subtracting its own age. A selector that misses
    its offset does not error -- it queries the anchor window, where there is no data, and the
    panel renders empty next to populated ones. So the interesting cases are all the places an
    identifier appears WITHOUT being a metric name: function names, aggregation keywords, the
    label lists inside by(), and label matchers inside braces.
    """

    def check(self, expr, expected):
        self.assertEqual(runs.offset_expr(expr), expected)

    def test_bare_metric(self):
        self.check("pg_wal_size_bytes", "pg_wal_size_bytes" + OFF)

    def test_metric_with_matchers(self):
        self.check('outbox_backlog{job="$job"}', 'outbox_backlog{job="$job"}' + OFF)

    def test_offset_follows_the_range_selector(self):
        # `rate(x offset 5m [1m])` is a parse error; the offset belongs after the range.
        self.check('rate(m{job="$job"}[1m])', 'rate(m{job="$job"}[1m]' + OFF + ")")

    def test_function_names_are_not_metrics(self):
        self.check("avg(process_cpu_usage)", "avg(process_cpu_usage" + OFF + ")")

    def test_aggregation_label_list_is_untouched(self):
        self.check("sum(rate(m[1m])) by (uri, method)",
                   "sum(rate(m[1m]" + OFF + ")) by (uri, method)")

    def test_prefix_by_clause_is_untouched(self):
        self.check("sum by (outcome, reason) (rate(m[1m]))",
                   "sum by (outcome, reason) (rate(m[1m]" + OFF + "))")

    def test_label_matcher_contents_are_untouched(self):
        # `status` and `[45]..` live inside the braces; only the metric name takes an offset.
        self.check('sum(rate(m{job="$job",status=~"[45].."}[1m]))',
                   'sum(rate(m{job="$job",status=~"[45].."}[1m]' + OFF + "))")

    def test_scalar_literals_and_vector_zero(self):
        self.check("(sum(a) or vector(0)) - (sum(b) or vector(0))",
                   "(sum(a" + OFF + ") or vector(0)) - (sum(b" + OFF + ") or vector(0))")

    def test_histogram_quantile_leading_float(self):
        self.check("histogram_quantile(0.95, sum(rate(m[1m])) by (le))",
                   "histogram_quantile(0.95, sum(rate(m[1m]" + OFF + ")) by (le))")

    def test_topk_leading_int(self):
        self.check("topk(10, sum by (repository) (rate(m[1m])))",
                   "topk(10, sum by (repository) (rate(m[1m]" + OFF + ")))")

    def test_binary_operands_both_offset(self):
        self.check("rate(a[1m]) / rate(b[1m])",
                   "rate(a[1m]" + OFF + ") / rate(b[1m]" + OFF + ")")


class RewriteIsReversible(unittest.TestCase):
    """Every live target, rewritten, must differ from the original by nothing but offsets.

    Removing the injected text has to reproduce the input byte for byte. That is what proves
    the rewriter is not quietly reformatting, dropping or duplicating anything in the 99
    expressions spec.py owns -- a property no amount of hand-written cases can cover.
    """

    def test_all_live_targets_round_trip(self):
        exprs = [t.expr for s in spec.SECTIONS for p in s.panels for t in (p.targets or [])]
        self.assertGreater(len(exprs), 50)
        for expr in exprs:
            with self.subTest(expr=expr):
                self.assertEqual(runs.offset_expr(expr).replace(OFF, ""), expr)

    def test_every_live_target_gets_at_least_one_offset(self):
        for section in spec.SECTIONS:
            for panel in section.panels:
                for target in panel.targets or []:
                    with self.subTest(expr=target.expr):
                        self.assertIn(OFF, runs.offset_expr(target.expr))

    def test_offset_count_matches_selector_count(self):
        # Hand-counted selectors in the two densest shapes spec.py uses.
        self.assertEqual(
            runs.offset_expr('rate(a{d="$db"}[1m]) / (rate(a{d="$db"}[1m]) + rate(b[1m]))'
                             ).count(OFF), 3)
        self.assertEqual(runs.offset_expr("(sum(a) or vector(0)) - (sum(b) or vector(0))"
                                          ).count(OFF), 2)


def _meta(run_id, variant, point, start, end):
    return {"run_id": run_id, "variant": variant, "variant_family": variant.split("-")[0],
            "scenario": "capacity", "point": point,
            "windows": {"load": [start, end - 100], "full": [start, end]}}


class ScanRuns(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = os.path.join(self.tmp.name, "dirA")
        os.makedirs(self.root)
        self.addCleanup(self.tmp.cleanup)

    def write(self, run_id, variant, point, start, end, meta=True, snapshot=True, under=""):
        d = os.path.join(self.root, under, run_id)
        os.makedirs(d)
        if snapshot:
            os.makedirs(os.path.join(d, "prom-snapshot"))
        if meta:
            with open(os.path.join(d, "meta.json"), "w") as fh:
                json.dump(_meta(run_id, variant, point, start, end), fh)

    def test_run_without_a_tsdb_snapshot_is_skipped(self):
        """A run with no prom-snapshot/ was never archived into bench-replay-data (or was made
        with --no-snapshot-tsdb), so selecting it would render 53 empty panels with nothing to
        say why. Only dump.json survives for those, which is what bench-replay is for."""
        self.write("TO-1_capacity_W-base_20260812T140542Z", "TO-1", "W-base", 1786543581, 1786547987)
        self.write("TO-9_capacity_W-base_20260812T140542Z", "TO-9", "W-base", 1786543581, 1786547987,
                   snapshot=False)
        self.assertEqual([r.variant for r in runs.scan_runs([self.root])], ["TO-1"])

    def test_offset_is_anchor_minus_run_start(self):
        self.write("TO-1_capacity_W-base_20260812T140542Z", "TO-1", "W-base", 1786543581, 1786547987)
        found = runs.scan_runs([self.root])
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].offset, runs.ANCHOR_EPOCH - 1786543581)

    def test_offsets_are_positive(self):
        """Prometheus rejects a negative offset without --enable-feature=promql-negative-offset,
        so the anchor has to sit after every run. A run recorded after the anchor is a silent
        killer: the query 400s and every panel shows an error, so fail loudly at build time."""
        self.write("late_capacity_W-base_20260901T000000Z", "TO-1", "W-base",
                   runs.ANCHOR_EPOCH + 10, runs.ANCHOR_EPOCH + 900)
        with self.assertRaises(SystemExit):
            runs.scan_runs([self.root])

    def test_incomplete_run_is_skipped(self):
        self.write("TO-1_capacity_W-base_20260812T140542Z", "TO-1", "W-base", 1786543581, 1786547987)
        self.write("ES-2-NullLock_capacity_W-base_20260812T200542Z", "ES-2", "W-base", 0, 0, meta=False)
        self.assertEqual([r.variant for r in runs.scan_runs([self.root])], ["TO-1"])

    def test_labels_carry_no_option_separators(self):
        """Grafana parses a custom variable's option list as `label : value, label : value`,
        so a colon or comma in a label silently splits it into a different option. The label is
        built from directory names now, which are the user's to choose."""
        self.write("TO-1_capacity_W-base_20260812T140542Z", "TO-1", "W-base", 1786543581, 1786547987)
        self.write("ES-4-NullLock_capacity_W-hot_20260813T144640Z", "ES-4", "W-hot", 1786633600, 1786637000,
                   under="phase 1: TO, then ES")
        for run in runs.scan_runs([self.root]):
            with self.subTest(run=run.run_id):
                self.assertNotIn(":", run.label)
                self.assertNotIn(",", run.label)

    def test_repeats_of_one_variant_and_point_stay_distinguishable(self):
        self.write("TO-1_capacity_W-base_20260812T140542Z", "TO-1", "W-base", 1786543581, 1786547987)
        self.write("TO-1_capacity_W-base_20260812T225752Z", "TO-1", "W-base", 1786575000, 1786579000)
        labels = [r.label for r in runs.scan_runs([self.root])]
        self.assertEqual(len(set(labels)), 2)

    def test_duplicate_run_id_across_roots_is_kept_once(self):
        self.write("TO-1_capacity_W-base_20260812T140542Z", "TO-1", "W-base", 1786543581, 1786547987)
        self.assertEqual(len(runs.scan_runs([self.root, self.root])), 1)

    def test_ordered_by_path_so_the_dropdown_reads_as_a_tree(self):
        """The dropdown text IS the path now, so any other order leaves the options in a
        sequence the labels do not explain -- and scatters one directory's runs through the
        list, which is what naming them after their directory is meant to fix."""
        self.write("ES-1_capacity_W-hot_20260813T120929Z", "ES-1", "W-hot", 1786620000, 1786623000,
                   under="dirC")
        self.write("TO-2_capacity_W-base_20260812T152159Z", "TO-2", "W-base", 1786550000, 1786553000,
                   under="dirB")
        self.write("TO-1_capacity_W-base_20260812T140542Z", "TO-1", "W-base", 1786543581, 1786547987,
                   under="dirB")
        self.assertEqual([r.label for r in runs.scan_runs([self.root])], [
            "dirA - dirB - TO-1_capacity_W-base_20260812T140542Z",
            "dirA - dirB - TO-2_capacity_W-base_20260812T152159Z",
            "dirA - dirC - ES-1_capacity_W-hot_20260813T120929Z",
        ])

    def test_a_run_directly_under_the_root_is_labelled_root_then_run(self):
        self.write("TO-1_capacity_W-base_20260812T140542Z", "TO-1", "W-base", 1786543581, 1786547987)
        self.assertEqual([r.label for r in runs.scan_runs([self.root])],
                         ["dirA - TO-1_capacity_W-base_20260812T140542Z"])

    def test_runs_nested_arbitrarily_deep_are_found(self):
        """Two levels was the old limit, and a campaign directory of campaign directories --
        which is what accumulating phases produces -- put every run one level past it."""
        self.write("TO-1_capacity_W-base_20260812T140542Z", "TO-1", "W-base", 1786543581, 1786547987,
                   under=os.path.join("dirB", "dirC", "dirD"))
        found = runs.scan_runs([self.root])
        self.assertEqual([r.label for r in found],
                         ["dirA - dirB - dirC - dirD - TO-1_capacity_W-base_20260812T140542Z"])

    def test_the_root_label_can_be_overridden(self):
        """docker-compose.replay-load.yml bind-mounts the selected directory at /runs, so the
        name the user knows it by is not on the container's filesystem at all."""
        self.write("TO-1_capacity_W-base_20260812T140542Z", "TO-1", "W-base", 1786543581, 1786547987)
        found = runs.scan_runs([self.root], ["Final-Bench"])
        self.assertEqual([r.label for r in found],
                         ["Final-Bench - TO-1_capacity_W-base_20260812T140542Z"])

    def test_a_tsdb_block_is_not_mistaken_for_a_run(self):
        """Every block under prom-snapshot/ carries a meta.json of its own -- Prometheus' block
        descriptor, which has no `windows` and would blow up on the first field read."""
        self.write("TO-1_capacity_W-base_20260812T140542Z", "TO-1", "W-base", 1786543581, 1786547987)
        block = os.path.join(self.root, "TO-1_capacity_W-base_20260812T140542Z",
                             "prom-snapshot", "01M1ECNMB93M3TTMMJQSRAWZ3E")
        os.makedirs(os.path.join(block, "prom-snapshot"))
        with open(os.path.join(block, "meta.json"), "w") as fh:
            json.dump({"ulid": "01M1ECNMB93M3TTMMJQSRAWZ3E", "minTime": 0}, fh)
        self.assertEqual(len(runs.scan_runs([self.root])), 1)

    def test_a_self_referential_symlink_does_not_recurse(self):
        """ensure_results_link() leaves `bench-results/bench-results -> <RESULTS_DIR>` when
        MAIN_ROOT is not the repo root. A recursive glob walks through it forever; os.walk
        does not follow symlinks, which is the whole reason it is used here."""
        self.write("TO-1_capacity_W-base_20260812T140542Z", "TO-1", "W-base", 1786543581, 1786547987)
        os.symlink(self.root, os.path.join(self.root, "dirA"))
        self.assertEqual([r.label for r in runs.scan_runs([self.root])],
                         ["dirA - TO-1_capacity_W-base_20260812T140542Z"])


class BuildRunsDashboard(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runs = [
            runs.Run("TO-1_capacity_W-base_20260812T140542Z", "TO-1", "TO", "W-base", 1786543581, 1786547987),
            runs.Run("ES-4-NullLock_capacity_W-hot_20260813T144640Z", "ES-4", "ES", "W-hot", 1786633600, 1786637000),
        ]
        cls.dash = runs.build_runs(cls.runs)
        cls.panels = [p for p in cls.dash["panels"] if p["type"] not in ("row", "text")]
        cls.vars = {v["name"]: v for v in cls.dash["templating"]["list"]}

    def test_uid_is_its_own(self):
        # the-dashboard's uid is rendered into every run's report.pdf by k6/bench/bench.sh, and
        # bench-replay owns the dump.json overlay. This must collide with neither.
        self.assertEqual(self.dash["uid"], "bench-runs")

    def test_run_variable_has_one_option_per_run(self):
        run_var = self.vars["run"]
        self.assertEqual(run_var["type"], "custom")
        self.assertEqual([o["value"] for o in run_var["options"]],
                         [f"{runs.ANCHOR_EPOCH - 1786543581}s", f"{runs.ANCHOR_EPOCH - 1786633600}s"])

    def test_run_variable_defaults_to_the_first_option(self):
        current = self.vars["run"]["current"]
        self.assertEqual(current["value"], f"{runs.ANCHOR_EPOCH - 1786543581}s")
        self.assertTrue(current["text"].startswith("TO-1"))

    def test_run_variable_is_single_select(self):
        # Multi-select would interpolate to `offset (a|b)`, which is not a duration.
        self.assertFalse(self.vars["run"]["multi"])
        self.assertFalse(self.vars["run"]["includeAll"])

    def test_family_variables_are_constants_not_queries(self):
        """Query variables resolve against the dashboard's time range. Here that range is the
        anchor window, which holds no data at all -- label_values() would come back empty and
        every panel filtering on $job would query {job=""} and render blank."""
        for name, value in (("job", "inventory"), ("db", "inventory"), ("dbc", "postgres")):
            with self.subTest(name=name):
                self.assertEqual(self.vars[name]["type"], "constant")
                # Both sample runs default to prom_job="inventory", so the alternation
                # collapses to the single name. PerRunScrapeJob covers the mixed set.
                self.assertEqual(self.vars[name]["query"], value)

    def test_every_target_is_offset(self):
        for panel in self.panels:
            for target in panel["targets"]:
                with self.subTest(panel=panel["title"]):
                    self.assertIn(OFF, target["expr"])

    def test_panels_are_pinned_to_the_replay_datasource(self):
        # The offsets are computed against the archive; pointing this at the live Prometheus
        # would silently query a window that never existed.
        for panel in self.panels:
            with self.subTest(panel=panel["title"]):
                self.assertEqual(panel["datasource"]["uid"], "prometheus-replay")

    def test_time_range_is_the_anchor_window(self):
        self.assertEqual(self.dash["time"]["from"], runs.ANCHOR_ISO)
        self.assertEqual(self.dash["refresh"], "")

    def test_carries_every_live_panel(self):
        live = [p for s in spec.SECTIONS for p in s.panels if p.targets]
        self.assertEqual(len(self.panels),
                         len(live) + len(runs.POOLED.panels) + len(runs.TOTALS.panels))


class ClipsToTheSelectedRun(unittest.TestCase):
    """Without clipping, everything past the selected run's end shows the run that followed it.

    The window is sized for the longest run and the campaign ran back-to-back, so a 60-minute run
    in a 76-minute window draws ~13 minutes of the next run's warm-up, and a 10-minute run draws
    three runs in a row. `and on() (vector(time()) < vector($end))` gates each panel on the
    evaluation instant, which is what makes one selection show exactly one run.
    """

    @classmethod
    def setUpClass(cls):
        cls.dash = runs.build_runs([
            runs.Run("TO-1_capacity_W-base_20260812T140542Z", "TO-1", "TO", "W-base",
                     1786543581, 1786547987)])
        cls.panels = [p for p in cls.dash["panels"] if p["type"] not in ("row", "text")]
        cls.vars = {v["name"]: v for v in cls.dash["templating"]["list"]}

    @classmethod
    def clipped(cls):
        """Every panel, with no exception: the dashboard has no @-pinned query left in it."""
        return cls.panels

    def test_every_target_is_clipped(self):
        for panel in self.clipped():
            for target in panel["targets"]:
                with self.subTest(panel=panel["title"]):
                    self.assertTrue(target["expr"].endswith(runs.CLIP), target["expr"])

    def test_clip_is_applied_once_per_target_not_per_selector(self):
        for panel in self.clipped():
            for target in panel["targets"]:
                with self.subTest(panel=panel["title"]):
                    self.assertEqual(target["expr"].count("$end"), 1)

    def test_end_variable_chains_off_the_selected_run(self):
        # $run's value IS the offset, and the marker carries that offset as a label, so the two
        # stay in step with no second dropdown to keep synchronised.
        end = self.vars["end"]
        self.assertEqual(end["type"], "query")
        self.assertEqual(end["query"]["query"],
                         'label_values(bench_run_marker{offset="$run"}, end_at)')
        self.assertEqual(end["datasource"]["uid"], "prometheus-replay")
        self.assertEqual(end["hide"], 2)

    def test_clipped_expression_still_round_trips(self):
        for panel in self.clipped():
            for target in panel["targets"]:
                with self.subTest(panel=panel["title"]):
                    bare = target["expr"][:-len(runs.CLIP)].removeprefix("(").removesuffix(")")
                    self.assertEqual(bare.replace(OFF, ""), self.original(panel["title"],
                                                                          target["legendFormat"]))

    @staticmethod
    def original(title, legend):
        # The replay-only sections first: POOLED and TOTALS are declared in runs.py, not
        # spec.py, but are rewritten by the same pick() and must round-trip the same way.
        for section in [runs.POOLED, runs.TOTALS] + spec.SECTIONS:
            for panel in section.panels:
                if panel.title != title:
                    continue
                for target in panel.targets or []:
                    if target.legend == legend:
                        return target.expr
        raise AssertionError(f"no spec target for {title} / {legend}")



class PooledPublishLag(unittest.TestCase):
    """Publish lag with every event type in ONE histogram, which the per-type panel is not.

    Quantiles do not aggregate: the p95 of each event type separately says nothing about the
    p95 over all of them, and the largest of the per-type curves is not it either. The pooling
    has to happen before histogram_quantile is applied -- which is what leaving `eventType` out
    of the `by` clause does. dump.json only ever splits publish lag by eventType, so nothing
    else in the project draws the pooled figure.
    """

    @classmethod
    def setUpClass(cls):
        # NOT `cls.run`: TestCase.run is the method unittest calls to execute the case.
        cls.sample = runs.Run("TO-1_capacity_W-base_20260812T140542Z", "TO-1", "TO", "W-base",
                              1786543581, 1786547987)
        cls.dash = runs.build_runs([cls.sample])
        titles = {p.title for p in runs.POOLED.panels}
        cls.panels = [p for p in cls.dash["panels"] if p.get("title") in titles]

    def targets(self):
        for panel in self.panels:
            for target in panel["targets"]:
                yield panel["title"], target

    def test_panel_is_present_and_is_a_curve(self):
        self.assertEqual(len(self.panels), len(runs.POOLED.panels))
        for panel in self.panels:
            with self.subTest(panel=panel["title"]):
                self.assertEqual(panel["type"], "timeseries")
                self.assertEqual(panel["fieldConfig"]["defaults"]["unit"], "s")

    def test_it_is_the_first_panel_under_the_header(self):
        """It is the reason to open this dashboard on a finished run; burying it under the
        HTTP section would leave the per-type curves as the first publish lag anyone sees."""
        ordered = [p for p in self.dash["panels"] if p["type"] not in ("row", "text")]
        self.assertEqual(ordered[0]["title"], runs.POOLED.panels[0].title)

    def test_the_three_quantiles_are_p50_p95_p99(self):
        for panel in self.panels:
            with self.subTest(panel=panel["title"]):
                self.assertEqual([t["legendFormat"] for t in panel["targets"]],
                                 ["p50", "p95", "p99"])

    def test_buckets_are_pooled_before_the_quantile(self):
        """The whole point: `by (le)` and nothing else. `by (le, eventType)` would silently
        turn this into a second copy of the per-type panel."""
        for title, target in self.targets():
            with self.subTest(panel=title):
                self.assertIn("by (le)", target["expr"])
                self.assertNotIn("eventType", target["expr"])

    def test_it_reads_the_same_metric_as_the_per_type_panel(self):
        """Same samples, different grouping -- so the two panels are readable against each
        other, and a p50 above the per-type p50s means the mix moved, not the metric."""
        for title, target in self.targets():
            with self.subTest(panel=title):
                self.assertIn("publish_lag_seconds_bucket", target["expr"])

    def test_it_is_offset_and_clipped_like_every_other_curve(self):
        """Nothing in the expression is replay-specific, so it takes the ordinary rewrite: one
        offset on its single selector, and CLIP so it stops at the selected run's end rather
        than drawing the warm-up of whichever run the campaign recorded next."""
        for title, target in self.targets():
            with self.subTest(panel=title):
                self.assertEqual(target["expr"].count(OFF), 1)
                self.assertTrue(target["expr"].endswith(runs.CLIP), target["expr"])

    def test_it_is_replay_only(self):
        """Declared in runs.py, so the live dashboard does not carry it. Nothing in the query
        prevents it from being moved into spec.py -- this is placement, not a constraint."""
        live_titles = {p.title for s in spec.SECTIONS for p in s.panels}
        for panel in runs.POOLED.panels:
            with self.subTest(panel=panel.title):
                self.assertNotIn(panel.title, live_titles)


class CompletedOrdersRunningTotal(unittest.TestCase):
    """The one panel that answers "how many orders did this run finish", not "how fast".

    Every other orders panel is a rate. This is the undifferentiated counter, so the legend
    table's `Last *` column is the run's total per outcome.
    """

    @classmethod
    def setUpClass(cls):
        cls.sample = runs.Run("TO-1_capacity_W-base_20260812T140542Z", "TO-1", "TO", "W-base",
                              1786543581, 1786547987)
        cls.dash = runs.build_runs([cls.sample])
        titles = {p.title for p in runs.TOTALS.panels}
        cls.panels = [p for p in cls.dash["panels"] if p.get("title") in titles]

    def targets(self):
        for panel in self.panels:
            for target in panel["targets"]:
                yield panel["title"], target

    def test_panel_is_present_and_is_a_count_curve(self):
        self.assertEqual(len(self.panels), len(runs.TOTALS.panels))
        for panel in self.panels:
            with self.subTest(panel=panel["title"]):
                self.assertEqual(panel["type"], "timeseries")
                # `short`, not `ops`: this is a count, not a per-second rate. Shipping it as
                # `ops` would label 1.6M completed orders as "1.6 Mops" beside the rate panels.
                self.assertEqual(panel["fieldConfig"]["defaults"]["unit"], "short")

    def test_the_legend_carries_the_run_total(self):
        """`Last *` is the whole point of the panel -- the count is read off the legend, not
        off the axis. The shared builder supplies it; this pins that it stays supplied."""
        for panel in self.panels:
            with self.subTest(panel=panel["title"]):
                self.assertIn("lastNotNull", panel["options"]["legend"]["calcs"])
                self.assertEqual(panel["options"]["legend"]["displayMode"], "table")

    def test_it_is_split_by_outcome_and_nothing_else(self):
        """`by (outcome)` collapses the `reason` dimension. The outcome x reason split is
        already drawn, as a rate, by "Orders completed by outcome & reason"."""
        for title, target in self.targets():
            with self.subTest(panel=title):
                self.assertIn("by (outcome)", target["expr"])
                self.assertNotIn("reason", target["expr"])
                self.assertEqual(target["legendFormat"], "{{outcome}}")

    def test_the_counter_is_undifferentiated(self):
        """No rate(), no increase(), no irate(): differentiating it turns this back into a
        copy of the rate panel and the legend's `Last *` stops being a count."""
        for title, target in self.targets():
            with self.subTest(panel=title):
                self.assertIn("orders_completed_total", target["expr"])
                for fn in ("rate(", "irate(", "increase(", "delta("):
                    self.assertNotIn(fn, target["expr"])

    def test_no_baseline_is_subtracted_at_the_window_start(self):
        """The obvious warm-up fix -- `counter - counter @ start()` -- is wrong here, and
        wrong in the silent direction.

        Prometheus carries a series' last sample forward for 5 minutes and run-suite.sh
        restarts the stack back-to-back, so a series with no sample yet in THIS run resolves
        to the PREVIOUS run's final value just past t0. In the 12-run phase-1 archive that
        hits `rejected` on 2 of the 6 TO runs (opening at 238 140 and 43 400, because no order
        had been rejected when measured load started). Subtracting that baseline drives the
        whole series negative, and a negative series is not drawn on a `min: 0` axis -- so
        101 767 rejections would read as "no rejections", with nothing on screen to say so.
        The raw counter instead shows the carry-over as a plateau-then-cliff in the first
        minutes, which is visible, and every point after it is this run's own.
        """
        for title, target in self.targets():
            with self.subTest(panel=title):
                self.assertNotIn("@ start()", target["expr"])
                self.assertNotIn("clamp_min", target["expr"])

    def test_it_is_offset_and_clipped_like_every_other_curve(self):
        """One offset on its single selector, and CLIP so the curve stops at the selected
        run's end rather than climbing into the next run's counter."""
        for title, target in self.targets():
            with self.subTest(panel=title):
                self.assertEqual(target["expr"].count(OFF), 1)
                self.assertTrue(target["expr"].endswith(runs.CLIP), target["expr"])

    def test_it_is_replay_only(self):
        """On the live dashboard the same expression would draw the counter since JVM start,
        which is a different quantity and not one anyone watches climb."""
        live_titles = {p.title for s in spec.SECTIONS for p in s.panels}
        for panel in runs.TOTALS.panels:
            with self.subTest(panel=panel.title):
                self.assertNotIn(panel.title, live_titles)

    def test_it_sits_directly_under_the_pooled_publish_lag(self):
        """POOLED stays first -- it is the reason to open this dashboard on a finished run --
        and the run's totals come next, above the per-instant rates."""
        ordered = [p for p in self.dash["panels"] if p["type"] not in ("row", "text")]
        self.assertEqual(ordered[0]["title"], runs.POOLED.panels[0].title)
        self.assertEqual(ordered[1]["title"], runs.TOTALS.panels[0].title)


class PerRunScrapeJob(unittest.TestCase):
    """$job must cover every scrape job the archived run set actually used.

    monitoring/prometheus/prometheus.yml declares two application jobs: `inventory`, and
    `inventory-mgmt` for variants that give actuator its own connector. Which one a run landed
    under is a property of the variant, recorded per run in meta.json as `prom_job`.

    With $job pinned to the literal `inventory`, selecting a run scraped as `inventory-mgmt`
    left 90 of its 91 $job-filtered targets returning nothing -- the entire dashboard blank,
    reading as a missing TSDB snapshot rather than a one-word label mismatch. In the phase-1
    archive that is both TO-2-fix-A runs, which are the A/B evidence for the watermark cursor.
    """

    MIXED = [
        runs.Run("TO-1_capacity_W-base_20260812T140542Z", "TO-1", "TO", "W-base",
                 1786543581, 1786547987, "inventory"),
        runs.Run("TO-2-fix-A_capacity_W-base_20260828T202501Z", "TO-2-fix-A", "TO", "W-base",
                 1786633600, 1786637000, "inventory-mgmt"),
    ]

    def test_the_constant_is_the_alternation_of_every_job_in_the_set(self):
        self.assertEqual(runs._job_constant(self.MIXED), "inventory|inventory-mgmt")

    def test_a_uniform_run_set_collapses_to_one_name(self):
        """No gratuitous alternation when every run agrees -- the common case stays readable."""
        self.assertEqual(runs._job_constant(self.MIXED[:1]), "inventory")

    def test_it_is_deduplicated_and_ordered(self):
        """The value goes into a generated file that is diffed against the committed one, so it
        must not depend on the order scan_runs happened to return."""
        doubled = self.MIXED + list(reversed(self.MIXED))
        self.assertEqual(runs._job_constant(doubled), "inventory|inventory-mgmt")

    def test_an_empty_set_still_yields_a_usable_job(self):
        """`""` would interpolate as {job=~""}, which matches only the empty job name."""
        self.assertEqual(runs._job_constant([]), runs.DEFAULT_PROM_JOB)

    def test_the_dashboard_carries_it(self):
        var = {v["name"]: v for v in runs.build_runs(self.MIXED)["templating"]["list"]}["job"]
        self.assertEqual(var["type"], "constant")
        self.assertEqual(var["query"], "inventory|inventory-mgmt")
        # A constant with `current: {}` interpolates empty exactly like an unresolved query
        # variable, so declaring the value is only half of it.
        self.assertEqual(var["current"]["value"], "inventory|inventory-mgmt")

    def test_every_job_filtered_target_uses_a_regex_matcher(self):
        """`job="inventory|inventory-mgmt"` is a literal job name no run has, so `=~` is what
        makes the alternation mean anything. This is the half of the fix that lives in spec.py
        and it is silent when wrong -- the panels render empty, they do not error."""
        for panel in runs.build_runs(self.MIXED)["panels"]:
            for target in panel.get("targets", []):
                if "$job" not in target["expr"]:
                    continue
                with self.subTest(panel=panel["title"]):
                    self.assertIn('job=~"$job"', target["expr"])
                    self.assertNotIn('job="$job"', target["expr"])

    def _scan_one(self, run_id, variant, prom_job):
        """One archived run on disk, with prom_job present or absent, through scan_runs()."""
        with tempfile.TemporaryDirectory() as root:
            d = os.path.join(root, run_id)
            os.makedirs(os.path.join(d, "prom-snapshot"))
            meta = _meta(run_id, variant, "W-base", 1786543581, 1786547987)
            if prom_job is not None:
                meta["prom_job"] = prom_job
            with open(os.path.join(d, "meta.json"), "w") as fh:
                json.dump(meta, fh)
            return runs.scan_runs([root])

    def test_scan_runs_defaults_the_job_for_a_meta_without_one(self):
        """Runs archived before bench.sh recorded prom_job predate the second job entirely."""
        found = self._scan_one("TO-1_capacity_W-base_20260812T140542Z", "TO-1", None)
        self.assertEqual([r.prom_job for r in found], [runs.DEFAULT_PROM_JOB])

    def test_scan_runs_reads_prom_job_from_meta(self):
        found = self._scan_one("TO-2-fix-A_capacity_W-base_20260828T202501Z", "TO-2-fix-A",
                               "inventory-mgmt")
        self.assertEqual([r.prom_job for r in found], ["inventory-mgmt"])


class MarkerCoverage(unittest.TestCase):
    """The marker series has to span the whole visible window: Grafana resolves label_values()
    over whatever range is on screen, so a series that stopped at the anchor would leave $end
    empty for some ranges and not others — and an empty $end makes every query `vector()`."""

    def setUp(self):
        sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__)))))
        import scripts.run_markers as markers
        self.markers = markers

    def test_samples_cover_the_anchor_window_with_padding(self):
        found = [runs.Run("TO-1_capacity_W-base_20260812T140542Z", "TO-1", "TO", "W-base",
                          1786543581, 1786547987)]
        text, samples = self.markers.build_openmetrics(found, found[0].seconds)
        stamps = [int(line.rsplit(" ", 1)[1]) for line in text.splitlines()
                  if line.startswith(self.markers.METRIC)]
        self.assertLessEqual(min(stamps), runs.ANCHOR_EPOCH - self.markers.PAD_SECONDS)
        self.assertGreaterEqual(max(stamps),
                                runs.ANCHOR_EPOCH + found[0].seconds + self.markers.PAD_SECONDS)
        self.assertEqual(samples, len(stamps))

    def test_end_at_is_in_anchor_space(self):
        run = runs.Run("TO-1_capacity_W-base_20260812T140542Z", "TO-1", "TO", "W-base",
                       1786543581, 1786547987)
        text, _ = self.markers.build_openmetrics([run], run.seconds)
        self.assertIn(f'end_at="{runs.ANCHOR_EPOCH + run.seconds}"', text)
        self.assertIn(f'offset="{run.offset}s"', text)


class VerifierReadsTheDashboardsConstants(unittest.TestCase):
    """verify_dashboard_metrics.py must not keep its own copy of a template constant.

    It is the tool that answers "does this panel actually resolve?", so a stale value there
    reports a bug in the dashboard that is really a bug in the verifier. That had already
    happened twice: DEFAULT_VARS still held `job: inventory-to` and `dbc: postgres-to` long
    after the stack dropped the family suffixes, and its hardcoded `job: inventory` reported
    27/117 targets resolving on a TO-2-fix-A run where the dashboard renders 104/117.
    """

    def setUp(self):
        root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        self.script = os.path.join(root, "scripts", "verify_dashboard_metrics.py")
        self.dash_dir = os.path.join(root, "monitoring", "grafana", "provisioning", "dashboards")
        with open(self.script) as fh:
            self.source = fh.read()

    def constants(self, name):
        with open(os.path.join(self.dash_dir, f"{name}.json")) as fh:
            variables = json.load(fh)["templating"]["list"]
        return {v["name"]: v["query"] for v in variables if v.get("type") == "constant"}

    def test_it_reads_the_constants_out_of_the_dashboard_json(self):
        self.assertIn("def dashboard_constants(", self.source)
        self.assertIn("variables.update(dashboard_constants(", self.source)

    def test_the_committed_dashboards_declare_the_constants_it_needs(self):
        """bench-runs must carry $job as a constant, or the verifier silently falls back to the
        DEFAULT_VARS literal and the drift this test exists to prevent is back."""
        self.assertIn("job", self.constants("bench-runs"))
        self.assertEqual(self.constants("bench-runs")["job"], "inventory|inventory-mgmt")

    def test_no_default_carries_a_job_or_container_name_the_stack_dropped(self):
        """The `-to`/`-es` suffixes were removed from every job, database and container name."""
        head = self.source[:self.source.index("def ")]
        for stale in ("inventory-to", "inventory-es", "postgres-to", "postgres-es"):
            with self.subTest(stale=stale):
                self.assertNotIn(f'"{stale}"', head)


class AnchorsAgree(unittest.TestCase):
    """verify_dashboard_metrics.py duplicates the anchor as a literal (it runs as a path script,
    so it cannot import scripts.dashboards). A drift between the two is invisible: the verifier
    would probe a window with no data and report all 99 targets EMPTY, which reads as a broken
    archive rather than a stale constant."""

    def test_verifier_anchor_matches_runs_py(self):
        root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        with open(os.path.join(root, "scripts", "verify_dashboard_metrics.py")) as fh:
            source = fh.read()
        found = re.search(r"^ANCHOR_RUNS = (\d+)$", source, re.M)
        self.assertIsNotNone(found, "ANCHOR_RUNS not found in verify_dashboard_metrics.py")
        self.assertEqual(int(found.group(1)), runs.ANCHOR_EPOCH)

    def test_anchor_iso_matches_anchor_epoch(self):
        stamp = datetime.datetime.strptime(runs.ANCHOR_ISO, "%Y-%m-%dT%H:%M:%S.000Z")
        self.assertEqual(int(stamp.replace(tzinfo=datetime.timezone.utc).timestamp()),
                         runs.ANCHOR_EPOCH)


if __name__ == "__main__":
    unittest.main()
