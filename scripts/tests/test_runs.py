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
        self.root = self.tmp.name
        self.addCleanup(self.tmp.cleanup)

    def write(self, run_id, variant, point, start, end, meta=True, snapshot=True):
        d = os.path.join(self.root, run_id)
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
        so a colon or comma in a label silently splits it into a different option."""
        self.write("TO-1_capacity_W-base_20260812T140542Z", "TO-1", "W-base", 1786543581, 1786547987)
        self.write("ES-4-NullLock_capacity_W-hot_20260813T144640Z", "ES-4", "W-hot", 1786633600, 1786637000)
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

    def test_grouped_by_workload_point_then_variant(self):
        self.write("ES-1_capacity_W-hot_20260813T120929Z", "ES-1", "W-hot", 1786620000, 1786623000)
        self.write("TO-2_capacity_W-base_20260812T152159Z", "TO-2", "W-base", 1786550000, 1786553000)
        self.write("TO-1_capacity_W-base_20260812T140542Z", "TO-1", "W-base", 1786543581, 1786547987)
        self.assertEqual([(r.point, r.variant) for r in runs.scan_runs([self.root])],
                         [("W-base", "TO-1"), ("W-base", "TO-2"), ("W-hot", "ES-1")])


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
        self.assertEqual(len(self.panels), len(live) + len(runs.POOLED.panels))


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
        # POOLED first: the pooled publish-lag panel is declared in runs.py, not spec.py, but
        # is rewritten by the same pick() and must round-trip the same way.
        for section in [runs.POOLED] + spec.SECTIONS:
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
