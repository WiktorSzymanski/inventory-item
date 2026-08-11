import argparse
import importlib.util
import json
import os
import tempfile
import unittest

HERE = os.path.dirname(__file__)
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
TIPPING = os.path.join(ROOT, "k6", "bench", "tipping_point.py")


def load_tipping():
    spec = importlib.util.spec_from_file_location("tipping_mod", TIPPING)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def defaults(**over):
    args = argparse.Namespace(basis="goodput", tolerance=0.05, plateau_tol=0.05,
                              confirm=2, collapse_frac=0.7, shed_tol=0.05)
    for key, value in over.items():
        setattr(args, key, value)
    return args


def step(index, target, window=30, **scalars):
    """A per_step entry as dump.py writes it: counts over the trimmed plateau."""
    base = {"orders_accepted": None, "e2e_count": None, "events_processed": None,
            "saga_completed": None, "inflight_start": 0.0, "inflight_end": 0.0}
    base.update(scalars)
    return {"index": index, "target_rate": target,
            "window": [1000, 1000 + window], "scalars": base}


def to_step(index, target, offered, confirmed, rejected=0.0, window=30, **kw):
    total = offered * window
    return step(index, target, window,
                orders_accepted=total,
                e2e_count={"confirmed": confirmed * window, "rejected": rejected * window},
                **kw)


def es_step(index, target, offered, completed, window=30, **kw):
    return step(index, target, window,
                orders_accepted=offered * window,
                events_processed={"OrderCreatedEvent": offered * window},
                saga_completed={"completed": completed * window},
                **kw)


class MetricSelection(unittest.TestCase):
    """The whole point of the script: ES is judged on OrderCreatedEvent against
    saga outcomes, TO on admitted orders against order outcomes. Reading the
    wrong pair silently produces a plausible-looking wrong answer."""

    def setUp(self):
        self.mod = load_tipping()

    def test_es_reads_ordercreated_against_saga_outcome(self):
        dump = {"per_step": [es_step(1, 20, offered=20.0, completed=5.0)]}
        rows = self.mod.step_rows(dump, self.mod.FAMILIES["ES"], "goodput")
        self.assertEqual(rows[0]["offered_rps"], 20.0)
        self.assertEqual(rows[0]["done_rps"], 5.0)

    def test_es_ignores_e2e_count_even_when_present(self):
        # order_e2e_time fires after the saga ends, so the two disagree under load.
        raw = es_step(1, 20, offered=20.0, completed=5.0)
        raw["scalars"]["e2e_count"] = {"confirmed": 20.0 * 30}
        rows = self.mod.step_rows({"per_step": [raw]}, self.mod.FAMILIES["ES"], "goodput")
        self.assertEqual(rows[0]["done_rps"], 5.0)

    def test_to_reads_accepted_against_order_outcome(self):
        dump = {"per_step": [to_step(1, 20, offered=20.0, confirmed=12.0, rejected=8.0)]}
        rows = self.mod.step_rows(dump, self.mod.FAMILIES["TO"], "goodput")
        self.assertEqual(rows[0]["offered_rps"], 20.0)
        self.assertEqual(rows[0]["good_rps"], 12.0)
        self.assertEqual(rows[0]["failed_rps"], 8.0)

    def test_goodput_basis_excludes_rejections_terminal_basis_includes_them(self):
        dump = {"per_step": [to_step(1, 20, offered=20.0, confirmed=12.0, rejected=8.0)]}
        spec = self.mod.FAMILIES["TO"]
        self.assertEqual(self.mod.step_rows(dump, spec, "goodput")[0]["done_rps"], 12.0)
        self.assertEqual(self.mod.step_rows(dump, spec, "terminal")[0]["done_rps"], 20.0)

    def test_idle_step_zero_is_dropped(self):
        # Target 0 offers nothing and retires warmup leftovers; keeping it would
        # make the first keep_ratio a division by zero.
        dump = {"per_step": [to_step(0, 0, offered=0.0, confirmed=90.0),
                             to_step(1, 20, offered=20.0, confirmed=20.0)]}
        rows = self.mod.step_rows(dump, self.mod.FAMILIES["TO"], "goodput")
        self.assertEqual([r["index"] for r in rows], [1])

    def test_rates_are_per_second_not_window_totals(self):
        dump = {"per_step": [to_step(1, 20, offered=20.0, confirmed=20.0, window=33)]}
        rows = self.mod.step_rows(dump, self.mod.FAMILIES["TO"], "goodput")
        self.assertAlmostEqual(rows[0]["offered_rps"], 20.0, places=1)


class Detection(unittest.TestCase):
    def setUp(self):
        self.mod = load_tipping()

    def rows(self, pairs, family="TO", **kw):
        maker = es_step if family == "ES" else to_step
        steps = []
        for i, (offered, done) in enumerate(pairs, start=1):
            if family == "ES":
                steps.append(maker(i, offered, offered=offered, completed=done, **kw))
            else:
                steps.append(maker(i, offered, offered=offered, confirmed=done,
                                   rejected=offered - done, **kw))
        return self.mod.step_rows({"per_step": steps}, self.mod.FAMILIES[family], "goodput")

    def detect(self, rows, **over):
        args = defaults(**over)
        return self.mod.detect(rows, args.tolerance, args.plateau_tol, args.confirm,
                               args.collapse_frac, args.shed_tol)

    def test_tipping_point_is_the_first_step_that_stays_behind(self):
        res = self.detect(self.rows([(20, 20), (40, 40), (60, 45), (80, 46), (100, 46)]))
        self.assertEqual(res["onset"]["offered_rps"], 60)
        self.assertEqual(res["last_good"]["offered_rps"], 40)

    def test_a_single_dip_is_not_a_tipping_point(self):
        # One bad plateau (a GC pause, a lost scrape) must not be read as saturation.
        res = self.detect(self.rows([(20, 20), (40, 30), (60, 60), (80, 80), (100, 100)]))
        self.assertIsNone(res["onset"])

    def test_a_dip_on_the_final_step_is_reported_unconfirmed(self):
        res = self.detect(self.rows([(20, 20), (40, 40), (60, 30)]))
        self.assertEqual(res["onset"]["offered_rps"], 60)
        self.assertFalse(res["onset"]["confirmed"])

    def test_no_tipping_point_when_the_ramp_never_saturates(self):
        res = self.detect(self.rows([(20, 20), (40, 40), (60, 60), (80, 80)]))
        self.assertIsNone(res["onset"])
        self.assertEqual(res["class"], "tracking")
        self.assertEqual(res["peak"]["goodput_rps"], 80)

    def test_peak_does_not_credit_backlog_catchup_as_capacity(self):
        # Step 1 retires a warmup backlog at 5x what it was offered. Capacity is
        # 40/s here, not 100/s.
        res = self.detect(self.rows([(20, 100), (40, 40), (60, 30), (80, 30)]))
        self.assertEqual(res["peak"]["goodput_rps"], 40)

    def test_plateau_is_where_throughput_first_stops_increasing(self):
        res = self.detect(self.rows([(20, 20), (40, 40), (60, 50), (80, 51), (100, 51)]))
        self.assertEqual(res["plateau"]["offered_rps"], 60)

    def test_collapse_when_goodput_falls_away_under_more_load(self):
        res = self.detect(self.rows([(20, 20), (40, 8), (60, 4), (80, 1)], family="ES"))
        self.assertEqual(res["class"], "collapse")
        self.assertEqual(res["onset"]["offered_rps"], 40)

    def test_load_shed_when_orders_still_terminate_but_as_failures(self):
        # TO's signature: total terminal keeps up with offered, goodput does not.
        res = self.detect(self.rows([(20, 20), (40, 40), (60, 45), (80, 46), (100, 46)]))
        self.assertEqual(res["class"], "load-shed")

    def test_queueing_is_not_load_shed(self):
        steps = [to_step(1, 20, offered=20.0, confirmed=20.0),
                 to_step(2, 40, offered=40.0, confirmed=40.0),
                 to_step(3, 60, offered=60.0, confirmed=45.0),
                 to_step(4, 80, offered=80.0, confirmed=46.0)]
        rows = self.mod.step_rows({"per_step": steps}, self.mod.FAMILIES["TO"], "goodput")
        self.assertEqual(self.detect(rows)["class"], "plateau")

    def test_backlog_growth_is_reported_per_second(self):
        steps = [to_step(1, 20, offered=20.0, confirmed=5.0, window=30,
                         inflight_start=100.0, inflight_end=550.0)]
        rows = self.mod.step_rows({"per_step": steps}, self.mod.FAMILIES["TO"], "goodput")
        self.assertEqual(rows[0]["backlog_growth_rps"], 15.0)

    def test_tolerance_widens_the_healthy_band(self):
        pairs = [(20, 20), (40, 37), (60, 55), (80, 73)]
        self.assertIsNotNone(self.detect(self.rows(pairs), tolerance=0.05)["onset"])
        self.assertIsNone(self.detect(self.rows(pairs), tolerance=0.15)["onset"])

    def test_empty_staircase_is_not_a_crash(self):
        self.assertEqual(self.detect([])["class"], "no-steps")


class Fallbacks(unittest.TestCase):
    def setUp(self):
        self.mod = load_tipping()

    def run_dir(self, tmp, meta, dump):
        path = os.path.join(tmp, meta.get("run_id", "run"))
        os.makedirs(path)
        for name, blob in (("meta", meta), ("dump", dump)):
            with open(os.path.join(path, f"{name}.json"), "w") as fh:
                json.dump(blob, fh)
        return path

    def test_es_run_without_the_saga_counter_falls_back_and_says_so(self):
        raw = step(1, 20, orders_accepted=600.0, e2e_count={"confirmed": 600.0},
                   events_processed={"OrderCreatedEvent": 600.0})
        run = {"_name": "r", "_dir": "d",
               "meta": {"variant": "ES-1", "variant_family": "ES"},
               "dump": {"per_step": [raw]}}
        res = self.mod.analyse(run, defaults())
        self.assertEqual(res["steps"][0]["done_rps"], 20.0)
        self.assertTrue(any("e2e_count" in n for n in res["notes"]))

    def test_unknown_family_uses_the_generic_pair_and_says_so(self):
        run = {"_name": "r", "_dir": "d", "meta": {"variant": "XX-9"},
               "dump": {"per_step": [to_step(1, 20, offered=20.0, confirmed=20.0)]}}
        res = self.mod.analyse(run, defaults())
        self.assertTrue(any("unknown family" in n for n in res["notes"]))

    def test_expand_finds_runs_nested_below_the_results_root(self):
        with tempfile.TemporaryDirectory() as tmp:
            os.makedirs(os.path.join(tmp, "campaign"))
            deep = self.run_dir(os.path.join(tmp, "campaign"), {"run_id": "TO-1_capacity"},
                                {"per_step": []})
            found = self.mod.expand([tmp])
            self.assertEqual([os.path.realpath(p) for p in found], [os.path.realpath(deep)])

    def test_expand_does_not_descend_into_a_run_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = self.run_dir(tmp, {"run_id": "TO-1_capacity"}, {"per_step": []})
            os.makedirs(os.path.join(path, "prom-snapshot"))
            self.assertEqual(len(self.mod.expand([tmp])), 1)

    def test_a_soak_run_yields_no_steps_rather_than_a_bogus_tipping_point(self):
        run = {"_name": "r", "_dir": "d",
               "meta": {"variant": "TO-1", "variant_family": "TO", "scenario": "soak"},
               "dump": {"per_step": []}}
        self.assertEqual(self.mod.analyse(run, defaults())["steps"], [])


if __name__ == "__main__":
    unittest.main()
