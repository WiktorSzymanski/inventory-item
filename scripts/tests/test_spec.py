import unittest

from scripts.dashboards import spec


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
        # Task 9 deleted TO-2's jvm-dashboard.json and ES-1's inventory-es-dashboard.json without
        # the plan accounting for them; Task 10 merges their remaining signals back in.
        "system_load_average_1m", "system_cpu_count", "jvm_threads_states_threads",
        "http_server_requests_active_seconds_gcount", "jvm_gc_memory_allocated_bytes_total",
        "jvm_gc_memory_promoted_bytes_total", "jvm_gc_overhead", "jvm_gc_live_data_size_bytes",
        "jvm_gc_max_data_size_bytes", "jvm_memory_committed_bytes", "jvm_gc_pause_seconds_sum",
        "jvm_gc_pause_seconds_count", "jvm_gc_pause_seconds_max", "logback_events_total",
        "spring_data_repository_invocations_seconds_count", "hikaricp_connections_acquire_seconds_sum",
        "hikaricp_connections_acquire_seconds_count", "hikaricp_connections_acquire_seconds_max",
        "hikaricp_connections_usage_seconds_sum", "hikaricp_connections_usage_seconds_count",
        "hikaricp_connections_usage_seconds_max", "hikaricp_connections_timeout_total",
        "executor_pool_size_threads", "container_network_receive_bytes_total",
        "container_network_transmit_bytes_total", "r2dbc_pool_acquired_connections",
        "r2dbc_pool_pending_connections", "r2dbc_pool_idle_connections",
        "r2dbc_pool_max_allocated_connections", "data_state_fetch_ms_seconds_bucket",
    }

    # Names the deleted dashboards queried that NO branch has ever produced. They survived the
    # merge because OLD_METRICS only proves a name was carried over, never that it resolves;
    # scripts/verify_dashboard_metrics.py found them empty against a live stack, and each was
    # then traced to a root cause that no dashboard edit can fix:
    #
    #   data_state_fetch_ms_seconds_bucket  no branch registers this meter (name is also
    #                                       malformed — "_ms_seconds")
    #   jvm_gc_pause_seconds_bucket         jvm.gc.pause is on no branch's
    #                                       management.metrics.distribution.percentiles-histogram
    #                                       list, so Micrometer emits only count/sum/max
    #   tomcat_threads_*                    needs server.tomcat.mbeanregistry.enabled=true, which
    #                                       no branch sets; only tomcat_sessions_* are exposed
    #   r2dbc_pool_*                        no branch uses R2DBC (only main so much as mentions it)
    #
    # Recovering the first two would mean changing the application under measurement across all
    # eight variant branches mid-campaign, so the panels were removed instead.
    NEVER_COLLECTED = {
        "data_state_fetch_ms_seconds_bucket", "jvm_gc_pause_seconds_bucket",
        "tomcat_threads_busy_threads", "tomcat_threads_current_threads",
        "tomcat_threads_config_max_threads", "r2dbc_pool_acquired_connections",
        "r2dbc_pool_pending_connections", "r2dbc_pool_idle_connections",
        "r2dbc_pool_max_allocated_connections",
    }

    def test_no_old_metric_was_dropped(self):
        blob = " ".join(t.expr for s in spec.SECTIONS for p in s.panels for t in p.targets)
        missing = sorted(m for m in self.OLD_METRICS - self.NEVER_COLLECTED if m not in blob)
        self.assertEqual(missing, [], f"metrics lost in the merge: {missing}")

    def test_never_collected_metrics_are_not_reintroduced(self):
        blob = " ".join(t.expr for s in spec.SECTIONS for p in s.panels for t in p.targets)
        back = sorted(m for m in self.NEVER_COLLECTED if m in blob)
        self.assertEqual(back, [], f"metrics no branch produces are back in the spec: {back}")


if __name__ == "__main__":
    unittest.main()
