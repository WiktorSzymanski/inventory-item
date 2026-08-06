"""Single source of truth for both Grafana dashboards.

Every metric appears exactly once. `targets` is what the live dashboard queries against a
Prometheus that is scraping the stack; `archived` is the equivalent against the three generic
metrics scripts/replay_run.py backfills from a run's dump.json:

    replay_series {run_id, variant, scenario, metric}              -- 5s, whole window
    replay_step   {run_id, variant, scenario, metric, dim, step}   -- one point per capacity step
    replay_summary{run_id, variant, scenario, key, dim}            -- one point per run

`archived=None` means the signal is not in dump.json at all; build.py lists those panels in a
"not available" note rather than rendering an empty panel.
"""
import re
from dataclasses import dataclass, field


@dataclass
class Target:
    legend: str
    expr: str


@dataclass
class Panel:
    title: str
    unit: str
    targets: list
    archived: list = None
    w: int = 8
    h: int = 8
    description: str = ""
    type: str = "timeseries"
    max: float = None


@dataclass
class Section:
    title: str
    panels: list = field(default_factory=list)


# Expressions allowed to repeat because the second use is a different visual form of the same
# number (a gauge of heap-used-over-max next to the heap timeseries), not a duplicated panel.
DUP_EXEMPT = set()

# dump.json keys the miniature fixture does not carry, but real runs do. Keeping this list
# explicit means a typo in spec.py still fails the test.
FIXTURE_GAPS = {
    "rate_terminal", "e2e_p95_1m", "heap", "db_size", "conflict_rate", "k6_offered",
    "target_rate", "orders_non202", "reads_total", "e2e_count", "e2e_sum", "append_success",
    "opt_exhausted", "cache_hit", "cache_miss", "catchup", "events_processed",
    "saga_completed", "saga_cmd_failed", "e2e_p50", "e2e_p99", "http_order_p50",
    "http_order_p95", "http_order_p99", "projection_lag_p50", "projection_lag_p95",
    "projection_lag_p99", "order_proj_lag_p50", "order_proj_lag_p95", "order_proj_lag_p99",
    "state_load_p50", "state_load_p95", "state_load_p99", "state_persist_p50",
    "state_persist_p95", "state_persist_p99", "publish_lag_p50", "publish_lag_p95",
    "publish_lag_p99", "saga_lifetime_p50", "saga_lifetime_p95", "saga_lifetime_p99",
    "cpu_max", "sys_cpu_avg", "heap_max_bytes", "heap_end_bytes", "db_size_start",
    "db_size_end", "container_cpu", "container_rss", "inflight_start", "inflight_end",
    "inflight_max", "completion_ratio", "rejected_ratio", "non202_ratio", "conflict_ratio",
    "cache_hit_ratio", "db_growth_bytes", "db_bytes_per_order", "drain_seconds",
    "drain_service_rate", "e2e_mean_confirmed", "achieved_rps_load_window",
    "cpu_avg_load_window", "window_seconds", "orders_accepted",
}


def metric_labels(expr):
    """Every metric="..." value referenced by a replay_* expression."""
    return re.findall(r'metric="([^"]+)"', expr) + re.findall(r'key="([^"]+)"', expr)


def _q(quantile, metric, by, job=True):
    selector = '{job="$job"}' if job else "{}"
    return (f'histogram_quantile({quantile}, sum(rate({metric}{selector}[1m])) by ({by}))')


SECTIONS = [
    Section("HTTP", [
        Panel(
            title="Request rate by endpoint & method",
            unit="reqps", w=12,
            description="sum(rate(http_server_requests_seconds_count)) by (uri, method). Replaces the "
                        "separate per-family throughput panels the three old dashboards each had.",
            targets=[Target("{{method}} {{uri}}",
                            'sum(rate(http_server_requests_seconds_count{job="$job"}[1m])) by (uri, method)')],
            archived=[Target("{{run_id}} accepted (202)", 'replay_series{run_id=~"$runs",axis="elapsed",metric="rate_accepted"}')],
        ),
        Panel(
            title="POST /inventory/orders by status",
            unit="reqps", w=12,
            description="Admission outcome. On archived runs only the 202/non-202 split survives.",
            targets=[Target("{{status}}",
                            'sum(rate(http_server_requests_seconds_count{job="$job",method="POST",uri="/inventory/orders"}[1m])) by (status)')],
            archived=[Target("{{run_id}} accepted", 'replay_step{run_id=~"$runs",axis="elapsed",metric="orders_accepted"}'),
                      Target("{{run_id}} non-202", 'replay_step{run_id=~"$runs",axis="elapsed",metric="orders_non202"}')],
        ),
        Panel(
            title="Request latency — p50 / p95 / p99",
            unit="s", w=12,
            targets=[Target("{{method}} {{uri}} p50", _q(0.50, "http_server_requests_seconds_bucket", "le, uri, method")),
                     Target("{{method}} {{uri}} p95", _q(0.95, "http_server_requests_seconds_bucket", "le, uri, method")),
                     Target("{{method}} {{uri}} p99", _q(0.99, "http_server_requests_seconds_bucket", "le, uri, method"))],
            archived=[Target("{{run_id}} POST orders p50", 'replay_step{run_id=~"$runs",axis="elapsed",metric="http_order_p50"}'),
                      Target("{{run_id}} POST orders p95", 'replay_step{run_id=~"$runs",axis="elapsed",metric="http_order_p95"}'),
                      Target("{{run_id}} POST orders p99", 'replay_step{run_id=~"$runs",axis="elapsed",metric="http_order_p99"}')],
        ),
        Panel(
            title="HTTP error rate (4xx / 5xx)",
            unit="reqps", w=12,
            targets=[Target("{{status}} {{uri}}",
                            'sum(rate(http_server_requests_seconds_count{job="$job",status=~"[45].."}[1m])) by (uri, status)')],
            archived=None,
        ),
    ]),
    Section("Orders & domain", [
        Panel(
            title="Offered vs accepted vs terminal",
            unit="reqps", w=24, h=9,
            description="One axis, orders/s. Dashed = asked of the system, solid = done by it.",
            targets=[Target("accepted (202)",
                            'sum(rate(http_server_requests_seconds_count{job="$job",method="POST",uri="/inventory/orders",status="202"}[1m]))'),
                     Target("terminal", 'sum(rate(order_e2e_time_seconds_count{job="$job"}[1m]))')],
            archived=[Target("{{run_id}} accepted", 'replay_series{run_id=~"$runs",axis="elapsed",metric="rate_accepted"}'),
                      Target("{{run_id}} terminal", 'replay_series{run_id=~"$runs",axis="elapsed",metric="rate_terminal"}'),
                      Target("{{run_id}} k6 offered", 'replay_series{run_id=~"$runs",axis="elapsed",metric="k6_offered"}'),
                      Target("{{run_id}} step target", 'replay_series{run_id=~"$runs",axis="elapsed",metric="target_rate"}')],
        ),
        Panel(
            title="In-flight orders (saturation)",
            unit="short", w=12,
            description="Admitted minus terminal. Monotonic growth on a constant-rate plateau is saturation.",
            targets=[Target("in-flight",
                            '(sum(http_server_requests_seconds_count{job="$job",method="POST",uri="/inventory/orders",status="202"}) or vector(0))'
                            ' - (sum(order_e2e_time_seconds_count{job="$job"}) or vector(0))')],
            archived=[Target("{{run_id}}", 'replay_series{run_id=~"$runs",axis="elapsed",metric="inflight"}')],
        ),
        Panel(
            title="Order e2e latency by outcome — p50 / p95 / p99",
            unit="s", w=12,
            targets=[Target("{{outcome}} p50", _q(0.50, "order_e2e_time_seconds_bucket", "le, outcome")),
                     Target("{{outcome}} p95", _q(0.95, "order_e2e_time_seconds_bucket", "le, outcome")),
                     Target("{{outcome}} p99", _q(0.99, "order_e2e_time_seconds_bucket", "le, outcome"))],
            archived=[Target("{{run_id}} {{dim}} p50", 'replay_step{run_id=~"$runs",axis="elapsed",metric="e2e_p50"}'),
                      Target("{{run_id}} {{dim}} p95", 'replay_step{run_id=~"$runs",axis="elapsed",metric="e2e_p95"}'),
                      Target("{{run_id}} {{dim}} p99", 'replay_step{run_id=~"$runs",axis="elapsed",metric="e2e_p99"}')],
        ),
        Panel(
            title="Order processing time — p50 / p95 (TO family)",
            unit="s", w=12,
            targets=[Target("p50", _q(0.50, "order_processing_time_seconds_bucket", "le")),
                     Target("p95", _q(0.95, "order_processing_time_seconds_bucket", "le"))],
            archived=None,
        ),
        Panel(
            title="Order queue wait — p50 / p95 (TO family)",
            unit="s", w=12,
            targets=[Target("p50", _q(0.50, "order_queue_wait_seconds_bucket", "le")),
                     Target("p95", _q(0.95, "order_queue_wait_seconds_bucket", "le"))],
            archived=None,
        ),
        Panel(
            title="Orders completed by outcome & reason (TO family)",
            unit="ops", w=12,
            targets=[Target("{{outcome}} {{reason}}",
                            'sum by (outcome, reason) (rate(orders_completed_total{job="$job"}[1m]))')],
            archived=None,
        ),
        Panel(
            title="Business exception rate by type",
            unit="ops", w=12,
            targets=[Target("{{type}}", 'sum by (type) (rate(inventory_exception_total{job="$job"}[1m]))')],
            archived=[Target("{{run_id}} {{dim}}", 'replay_step{run_id=~"$runs",axis="elapsed",metric="exceptions"}')],
        ),
        Panel(
            title="Optimistic locking — append success vs conflict",
            unit="ops", w=12,
            targets=[Target("append success", 'sum(rate(inventory_append_success_total{job="$job"}[1m]))'),
                     Target("retry", 'sum(rate(inventory_optimistic_retry_total{job="$job"}[1m]))'),
                     Target("exhausted", 'sum(rate(inventory_optimistic_exhausted_total{job="$job"}[1m]))')],
            archived=[Target("{{run_id}} retry rate", 'replay_series{run_id=~"$runs",axis="elapsed",metric="conflict_rate"}'),
                      Target("{{run_id}} exhausted (step total)", 'replay_step{run_id=~"$runs",axis="elapsed",metric="opt_exhausted"}')],
        ),
        Panel(
            title="Events processed by type",
            unit="ops", w=12,
            targets=[Target("{{eventType}}", 'sum by (eventType) (rate(es_events_processed_total{job="$job"}[1m]))')],
            archived=[Target("{{run_id}} {{dim}}", 'replay_step{run_id=~"$runs",axis="elapsed",metric="events_processed"}')],
        ),
        Panel(
            title="Publish lag by event type — p50 / p95 / p99",
            unit="s", w=12,
            targets=[Target("{{eventType}} p50", _q(0.50, "publish_lag_seconds_bucket", "le, eventType")),
                     Target("{{eventType}} p95", _q(0.95, "publish_lag_seconds_bucket", "le, eventType")),
                     Target("{{eventType}} p99", _q(0.99, "publish_lag_seconds_bucket", "le, eventType"))],
            archived=[Target("{{run_id}} {{dim}} p95", 'replay_step{run_id=~"$runs",axis="elapsed",metric="publish_lag_p95"}'),
                      Target("{{run_id}} {{dim}} p99", 'replay_step{run_id=~"$runs",axis="elapsed",metric="publish_lag_p99"}')],
        ),
        Panel(
            title="State load time by phase — p50 / p95",
            unit="s", w=12,
            targets=[Target("{{phase}} p50", _q(0.50, "state_load_time_seconds_bucket", "le, phase")),
                     Target("{{phase}} p95", _q(0.95, "state_load_time_seconds_bucket", "le, phase"))],
            archived=[Target("{{run_id}} {{dim}} p50", 'replay_step{run_id=~"$runs",axis="elapsed",metric="state_load_p50"}'),
                      Target("{{run_id}} {{dim}} p95", 'replay_step{run_id=~"$runs",axis="elapsed",metric="state_load_p95"}')],
        ),
        Panel(
            title="State persist time by source — p50 / p95 / p99",
            unit="s", w=12,
            targets=[Target("{{source}} p50", _q(0.50, "state_persist_time_seconds_bucket", "le, source")),
                     Target("{{source}} p95", _q(0.95, "state_persist_time_seconds_bucket", "le, source")),
                     Target("{{source}} p99", _q(0.99, "state_persist_time_seconds_bucket", "le, source"))],
            archived=[Target("{{run_id}} {{dim}} p95", 'replay_step{run_id=~"$runs",axis="elapsed",metric="state_persist_p95"}'),
                      Target("{{run_id}} {{dim}} p99", 'replay_step{run_id=~"$runs",axis="elapsed",metric="state_persist_p99"}')],
        ),
        Panel(
            title="Projection lag — p50 / p95 / p99",
            unit="s", w=12,
            targets=[Target("inventory p50", _q(0.50, "projection_lag_seconds_bucket", "le")),
                     Target("inventory p95", _q(0.95, "projection_lag_seconds_bucket", "le")),
                     Target("order p95", _q(0.95, "order_projection_lag_seconds_bucket", "le"))],
            archived=[Target("{{run_id}} inventory p95", 'replay_step{run_id=~"$runs",axis="elapsed",metric="projection_lag_p95"}'),
                      Target("{{run_id}} order p95", 'replay_step{run_id=~"$runs",axis="elapsed",metric="order_proj_lag_p95"}')],
        ),
        Panel(
            title="Aggregate cache — hit vs miss vs catch-up",
            unit="ops", w=12,
            targets=[Target("hit", 'sum(rate(inventory_opt_cache_hit_total{job="$job"}[1m]))'),
                     Target("miss", 'sum(rate(inventory_opt_cache_miss_total{job="$job"}[1m]))'),
                     Target("catch-up", 'sum(rate(inventory_opt_catchup_total{job="$job"}[1m]))')],
            archived=[Target("{{run_id}} hit", 'replay_step{run_id=~"$runs",axis="elapsed",metric="cache_hit"}'),
                      Target("{{run_id}} miss", 'replay_step{run_id=~"$runs",axis="elapsed",metric="cache_miss"}'),
                      Target("{{run_id}} catch-up", 'replay_step{run_id=~"$runs",axis="elapsed",metric="catchup"}')],
        ),
        Panel(
            title="Outbox backlog (TO family)",
            unit="short", w=12,
            targets=[Target("backlog", 'outbox_backlog{job="$job"}')],
            archived=None,
        ),
        Panel(
            title="Outbox write time — p50 / p95 (TO family)",
            unit="s", w=12,
            targets=[Target("p50", _q(0.50, "outbox_write_time_seconds_bucket", "le")),
                     Target("p95", _q(0.95, "outbox_write_time_seconds_bucket", "le"))],
            archived=None,
        ),
        # Replaces "Order worker — queue depth & active threads (TO family)": a general executor
        # panel covering pool size too, absorbed from TO-2's jvm-dashboard.json (Task 9 deleted
        # it; Task 10 merges its signal). The pinned-name TO-only panel would otherwise sit
        # alongside a superset of itself.
        Panel(
            title="Executor pools — threads & queue",
            unit="short", w=12,
            targets=[Target("{{name}} active", 'executor_active_threads{job="$job"}'),
                     Target("{{name}} pool size", 'executor_pool_size_threads{job="$job"}'),
                     Target("{{name}} queued", 'executor_queued_tasks{job="$job"}')],
            archived=None,
        ),
        Panel(
            title="Saga outcome — completed vs failed (ES family)",
            unit="ops", w=24,
            targets=[Target("completed", 'sum(rate(saga_completed_total{job="$job"}[1m]))'),
                     Target("command failed", 'sum(rate(saga_command_failed_total{job="$job"}[1m]))')],
            archived=[Target("{{run_id}} completed", 'replay_step{run_id=~"$runs",axis="elapsed",metric="saga_completed"}'),
                      Target("{{run_id}} cmd failed", 'replay_step{run_id=~"$runs",axis="elapsed",metric="saga_cmd_failed"}')],
        ),
    ]),
    Section("JVM", [
        Panel(title="Heap memory", unit="bytes", w=12,
              targets=[Target("used", 'sum(jvm_memory_used_bytes{job="$job",area="heap"})'),
                       Target("max", 'sum(jvm_memory_max_bytes{job="$job",area="heap"})'),
                       Target("committed", 'sum(jvm_memory_committed_bytes{job="$job",area="heap"})')],
              archived=[Target("{{run_id}}", 'replay_series{run_id=~"$runs",axis="elapsed",metric="heap"}')]),
        Panel(title="Non-heap memory by pool", unit="bytes", w=12,
              targets=[Target("{{id}}", 'sum(jvm_memory_used_bytes{job="$job",area="nonheap"}) by (id)')],
              archived=None),
        Panel(title="CPU", unit="percentunit", w=12, max=1,
              targets=[Target("process", 'avg(process_cpu_usage{job="$job"})'),
                       Target("system", 'avg(system_cpu_usage{job="$job"})')],
              archived=[Target("{{run_id}} process", 'replay_series{run_id=~"$runs",axis="elapsed",metric="cpu"}'),
                        Target("{{run_id}} system (step avg)", 'replay_step{run_id=~"$runs",axis="elapsed",metric="sys_cpu_avg"}')]),
        # avg/max only, no quantiles: Micrometer publishes buckets for a Timer solely when the
        # meter is listed under management.metrics.distribution.percentiles-histogram, and
        # jvm.gc.pause is on no branch's list, so jvm_gc_pause_seconds_bucket does not exist.
        Panel(title="GC pause duration — avg / max", unit="s", w=12,
              targets=[Target("avg",
                             'rate(jvm_gc_pause_seconds_sum{job="$job"}[1m]) / rate(jvm_gc_pause_seconds_count{job="$job"}[1m])'),
                       Target("max", 'jvm_gc_pause_seconds_max{job="$job"}')],
              archived=None),
        Panel(title="JVM threads", unit="short", w=8,
              targets=[Target("live", 'jvm_threads_live_threads{job="$job"}'),
                       Target("daemon", 'jvm_threads_daemon_threads{job="$job"}'),
                       Target("peak", 'jvm_threads_peak_threads{job="$job"}')],
              archived=None),
        Panel(title="Loaded classes", unit="short", w=8,
              targets=[Target("loaded classes", 'jvm_classes_loaded_classes{job="$job"}')],
              archived=None),
        Panel(title="Process uptime", unit="s", w=8,
              targets=[Target("uptime", 'process_uptime_seconds{job="$job"}')],
              archived=None),
        # Absorbed from TO-2's jvm-dashboard.json (Task 9 deleted it; Task 10 merges its signals).
        Panel(title="System load vs CPU cores", unit="short", w=8,
              targets=[Target("load 1m", 'system_load_average_1m{job="$job"}'),
                       Target("cpu cores", 'system_cpu_count{job="$job"}')],
              archived=None),
        Panel(title="Threads by state", unit="short", w=8,
              targets=[Target("{{state}}", 'jvm_threads_states_threads{job="$job"}')],
              archived=None),
        Panel(title="In-flight HTTP requests", unit="short", w=8,
              targets=[Target("in-flight", 'sum(http_server_requests_active_seconds_gcount{job="$job"})')],
              archived=None),
        Panel(title="GC allocation & promotion rate", unit="Bps", w=8,
              targets=[Target("allocation", 'rate(jvm_gc_memory_allocated_bytes_total{job="$job"}[1m])'),
                       Target("promotion", 'rate(jvm_gc_memory_promoted_bytes_total{job="$job"}[1m])')],
              archived=None),
        Panel(title="GC overhead", unit="percentunit", w=8, max=1,
              targets=[Target("overhead", 'jvm_gc_overhead{job="$job"}')],
              archived=None),
        Panel(title="Live data after GC", unit="bytes", w=8,
              targets=[Target("live data (old gen)", 'jvm_gc_live_data_size_bytes{job="$job"}'),
                       Target("old gen max", 'jvm_gc_max_data_size_bytes{job="$job"}')],
              archived=None),
        Panel(title="Log events by level", unit="ops", w=12,
              targets=[Target("{{level}}", 'sum by (level) (rate(logback_events_total{job="$job"}[1m]))')],
              archived=None),
        Panel(title="Spring Data repository invocations (top 10)", unit="ops", w=12,
              targets=[Target("{{repository}}.{{method}}",
                              'topk(10, sum by (repository, method) (rate(spring_data_repository_invocations_seconds_count{job="$job"}[1m])))')],
              archived=None),
    ]),
    Section("Spring pools", [
        Panel(title="HikariCP connections", unit="short", w=8,
              targets=[Target("active", 'hikaricp_connections_active{job="$job"}'),
                       Target("pending", 'hikaricp_connections_pending{job="$job"}'),
                       Target("max", 'hikaricp_connections_max{job="$job"}')],
              archived=None),
        # Absorbed from TO-2's jvm-dashboard.json and ES-1's inventory-es-dashboard.json
        # (Task 9 deleted both; Task 10 merges their signals).
        Panel(title="HikariCP — acquire & usage time", unit="s", w=8,
              targets=[Target("acquire avg",
                              'rate(hikaricp_connections_acquire_seconds_sum{job="$job"}[1m]) / rate(hikaricp_connections_acquire_seconds_count{job="$job"}[1m])'),
                       Target("acquire max", 'hikaricp_connections_acquire_seconds_max{job="$job"}'),
                       Target("usage avg",
                              'rate(hikaricp_connections_usage_seconds_sum{job="$job"}[1m]) / rate(hikaricp_connections_usage_seconds_count{job="$job"}[1m])'),
                       Target("usage max", 'hikaricp_connections_usage_seconds_max{job="$job"}')],
              archived=None),
        Panel(title="HikariCP — connection timeouts", unit="ops", w=8,
              targets=[Target("timeouts", 'rate(hikaricp_connections_timeout_total{job="$job"}[1m])')],
              archived=None),
    ]),
    Section("PostgreSQL", [
        Panel(title="Database size", unit="bytes", w=8,
              targets=[Target("size", 'pg_database_size_bytes{datname="$db"}')],
              archived=[Target("{{run_id}}", 'replay_series{run_id=~"$runs",axis="elapsed",metric="db_size"}')]),
        Panel(title="WAL size", unit="bytes", w=8,
              targets=[Target("wal", "pg_wal_size_bytes")], archived=None),
        Panel(title="Active connections by state", unit="short", w=8,
              targets=[Target("{{state}}", 'pg_stat_activity_count{datname="$db"}')], archived=None),
        Panel(title="Transaction rate", unit="ops", w=8,
              targets=[Target("commits", 'rate(pg_stat_database_xact_commit{datname="$db"}[1m])'),
                       Target("rollbacks", 'rate(pg_stat_database_xact_rollback{datname="$db"}[1m])')],
              archived=None),
        Panel(title="Tuple operations rate", unit="ops", w=8,
              targets=[Target("inserted", 'rate(pg_stat_database_tup_inserted{datname="$db"}[1m])'),
                       Target("updated", 'rate(pg_stat_database_tup_updated{datname="$db"}[1m])'),
                       Target("deleted", 'rate(pg_stat_database_tup_deleted{datname="$db"}[1m])'),
                       Target("fetched", 'rate(pg_stat_database_tup_fetched{datname="$db"}[1m])')],
              archived=None),
        Panel(title="Buffer cache hit ratio", unit="percentunit", w=8, max=1,
              targets=[Target("hit ratio",
                              'rate(pg_stat_database_blks_hit{datname="$db"}[1m]) / '
                              '(rate(pg_stat_database_blks_hit{datname="$db"}[1m]) + '
                              'rate(pg_stat_database_blks_read{datname="$db"}[1m]))')],
              archived=None),
        Panel(title="Live rows by table", unit="short", w=8,
              targets=[Target("{{relname}}", 'pg_stat_user_tables_n_live_tup{schemaname="public"}')],
              archived=None),
        Panel(title="Per-table write rate", unit="ops", w=8,
              targets=[Target("{{relname}} ins", 'rate(pg_stat_user_tables_n_tup_ins{schemaname="public"}[1m])'),
                       Target("{{relname}} upd", 'rate(pg_stat_user_tables_n_tup_upd{schemaname="public"}[1m])'),
                       Target("{{relname}} del", 'rate(pg_stat_user_tables_n_tup_del{schemaname="public"}[1m])')],
              archived=None),
        Panel(title="Locks by mode", unit="short", w=8,
              targets=[Target("{{mode}}", 'pg_locks_count{datname="$db"}')], archived=None),
        Panel(title="Checkpoint activity", unit="ops", w=12,
              targets=[Target("timed", "rate(pg_stat_bgwriter_checkpoints_timed_total[1m])"),
                       Target("requested", "rate(pg_stat_bgwriter_checkpoints_req_total[1m])"),
                       Target("write time", "rate(pg_stat_bgwriter_checkpoint_write_time_total[1m])")],
              archived=None),
        Panel(title="Container CPU", unit="percentunit", w=6,
              targets=[Target("{{name}}", 'rate(container_cpu_usage_seconds_total{name=~"$dbc|$apic"}[1m])')],
              archived=[Target("{{run_id}} api", 'replay_step{run_id=~"$runs",axis="elapsed",metric="container_cpu"}')]),
        Panel(title="Container memory", unit="bytes", w=6,
              targets=[Target("{{name}} rss", 'container_memory_rss{name=~"$dbc|$apic"}'),
                       Target("{{name}} working set", 'container_memory_working_set_bytes{name=~"$dbc|$apic"}')],
              archived=[Target("{{run_id}} api rss", 'replay_step{run_id=~"$runs",axis="elapsed",metric="container_rss"}')]),
        # Absorbed from TO-2's jvm-dashboard.json (Task 9 deleted it; Task 10 merges its signals).
        Panel(title="Container network I/O", unit="Bps", w=24,
              targets=[Target("{{name}} rx", 'sum by (name) (rate(container_network_receive_bytes_total{name=~"$dbc|$apic"}[1m]))'),
                       Target("{{name}} tx", 'sum by (name) (rate(container_network_transmit_bytes_total{name=~"$dbc|$apic"}[1m]))')],
              archived=None),
    ]),
]
