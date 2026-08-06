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


class MergeCoverage(unittest.TestCase):
    """Every metric the three old dashboards queried must survive the merge."""

    OLD_METRICS = {
        # the-dashboard
        "http_server_requests_seconds_count", "http_server_requests_seconds_bucket",
        "process_cpu_usage", "system_cpu_usage", "jvm_memory_used_bytes", "jvm_memory_max_bytes",
        "publish_lag_seconds_bucket", "state_load_time_seconds_bucket",
        "inventory_append_success_total", "inventory_optimistic_retry_total",
        "inventory_optimistic_exhausted_total", "inventory_exception_total",
        "pg_database_size_bytes", "executor_queued_tasks", "executor_active_threads",
        "order_processing_time_seconds_bucket", "order_e2e_time_seconds_bucket",
        "order_queue_wait_seconds_bucket", "orders_completed_total",
        "state_persist_time_seconds_bucket", "outbox_backlog", "outbox_write_time_seconds_bucket",
        "hikaricp_connections_active", "hikaricp_connections_pending", "hikaricp_connections_max",
        "tomcat_threads_busy_threads", "tomcat_threads_current_threads",
        "tomcat_threads_config_max_threads",
        # postgres-dashboard
        "pg_stat_activity_count", "pg_stat_database_xact_commit", "pg_stat_database_xact_rollback",
        "pg_stat_database_tup_inserted", "pg_stat_database_tup_updated",
        "pg_stat_database_tup_deleted", "pg_stat_database_tup_fetched",
        "pg_stat_database_blks_hit", "pg_stat_database_blks_read",
        "pg_stat_user_tables_n_live_tup", "pg_stat_user_tables_n_tup_ins",
        "pg_stat_user_tables_n_tup_upd", "pg_stat_user_tables_n_tup_del",
        "pg_wal_size_bytes", "pg_locks_count", "pg_stat_bgwriter_checkpoints_timed_total",
        "pg_stat_bgwriter_checkpoints_req_total", "pg_stat_bgwriter_checkpoint_write_time_total",
        "container_cpu_usage_seconds_total", "container_memory_rss",
        "container_memory_working_set_bytes",
        # jvm-spring
        "jvm_gc_pause_seconds_bucket", "jvm_threads_live_threads", "jvm_threads_daemon_threads",
        "jvm_threads_peak_threads", "jvm_classes_loaded_classes", "process_uptime_seconds",
    }

    def test_no_old_metric_was_dropped(self):
        blob = " ".join(t.expr for s in spec.SECTIONS for p in s.panels for t in p.targets)
        missing = sorted(m for m in self.OLD_METRICS if m not in blob)
        self.assertEqual(missing, [], f"metrics lost in the merge: {missing}")


if __name__ == "__main__":
    unittest.main()
