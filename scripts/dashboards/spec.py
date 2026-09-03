"""Single source of truth for both Grafana dashboards.

Every metric appears exactly once, as a `targets` expression written against a Prometheus
that is scraping the stack. Both dashboards are generated from that one set: build.py emits
it as-is for the live dashboard, and runs.py rewrites every selector with a PromQL `offset`
to point the same panels at one archived run.
"""
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


# `job=~`, not `job=`, everywhere -- and the same on every hand-written selector below.
#
# On the live dashboard $job is a query variable that resolves to the single value `inventory`,
# so `job=~"inventory"` and `job="inventory"` are the same query and nothing changes there.
#
# bench-runs is why it has to be the regex form. Its $job is a CONSTANT (query variables resolve
# against the anchor window, which holds no data), but the archive it reads is not uniform: a
# variant that gives actuator its own connector is scraped under `inventory-mgmt` rather than
# `inventory` -- see monitoring/prometheus/prometheus.yml -- and each run records which one it
# used in meta.json's `prom_job`. runs.py builds the constant as the alternation of every
# prom_job in the run set, so one dashboard matches both. With `job=` that constant would be a
# literal job name that no run has, and every panel would render "No data": exactly what
# happened to the two TO-2-fix-A runs, where 90 of 91 $job-filtered targets returned nothing and
# the dashboard read as a missing snapshot rather than a one-word label mismatch.
#
# Widening the matcher can only over-match if two API jobs carry the same metric at the same
# instant, which needs two API containers scraped at once; the stack runs one. Verified against
# the 12-run phase-1 archive: no instant of any run has both jobs present.
def _q(quantile, metric, by, job=True, extra=""):
    labels = ['job=~"$job"'] if job else []
    if extra:
        labels.append(extra)
    selector = "{" + ",".join(labels) + "}"
    return (f'histogram_quantile({quantile}, sum(rate({metric}{selector}[1m])) by ({by}))')


SECTIONS = [
    Section("HTTP", [
        Panel(
            title="Request rate by endpoint & method",
            unit="reqps", w=12,
            description="sum(rate(http_server_requests_seconds_count)) by (uri, method). Replaces the "
                        "separate per-family throughput panels the three old dashboards each had.",
            targets=[Target("{{method}} {{uri}}",
                            'sum(rate(http_server_requests_seconds_count{job=~"$job"}[1m])) by (uri, method)')],
        ),
        Panel(
            title="POST /inventory/orders by status",
            unit="reqps", w=12,
            description="Admission outcome.",
            targets=[Target("{{status}}",
                            'sum(rate(http_server_requests_seconds_count{job=~"$job",method="POST",uri="/inventory/orders"}[1m])) by (status)')],
        ),
        Panel(
            title="Request latency — p50 / p95 / p99",
            unit="s", w=12,
            targets=[Target("{{method}} {{uri}} p50", _q(0.50, "http_server_requests_seconds_bucket", "le, uri, method")),
                     Target("{{method}} {{uri}} p95", _q(0.95, "http_server_requests_seconds_bucket", "le, uri, method")),
                     Target("{{method}} {{uri}} p99", _q(0.99, "http_server_requests_seconds_bucket", "le, uri, method"))],
        ),
        Panel(
            title="HTTP error rate (4xx / 5xx)",
            unit="reqps", w=12,
            targets=[Target("{{status}} {{uri}}",
                            'sum(rate(http_server_requests_seconds_count{job=~"$job",status=~"[45].."}[1m])) by (uri, status)')],
        ),
    ]),
    Section("Orders & domain", [
        Panel(
            title="Offered vs accepted vs terminal",
            unit="reqps", w=24, h=9,
            description="One axis, orders/s. Dashed = asked of the system, solid = done by it.",
            targets=[Target("accepted (202)",
                            'sum(rate(http_server_requests_seconds_count{job=~"$job",method="POST",uri="/inventory/orders",status="202"}[1m]))'),
                     Target("terminal", 'sum(rate(order_e2e_time_seconds_count{job=~"$job"}[1m]))')],
        ),
        Panel(
            title="In-flight orders (saturation)",
            unit="short", w=12,
            description="Admitted minus terminal. Monotonic growth on a constant-rate plateau is saturation.",
            targets=[Target("in-flight",
                            '(sum(http_server_requests_seconds_count{job=~"$job",method="POST",uri="/inventory/orders",status="202"}) or vector(0))'
                            ' - (sum(order_e2e_time_seconds_count{job=~"$job"}) or vector(0))')],
        ),
        Panel(
            title="Order e2e latency by outcome — p50 / p95 / p99",
            unit="s", w=12,
            targets=[Target("{{outcome}} p50", _q(0.50, "order_e2e_time_seconds_bucket", "le, outcome")),
                     Target("{{outcome}} p95", _q(0.95, "order_e2e_time_seconds_bucket", "le, outcome")),
                     Target("{{outcome}} p99", _q(0.99, "order_e2e_time_seconds_bucket", "le, outcome"))],
        ),
        Panel(
            title="Order processing time — p50 / p95 (TO family)",
            unit="s", w=12,
            targets=[Target("p50", _q(0.50, "order_processing_time_seconds_bucket", "le")),
                     Target("p95", _q(0.95, "order_processing_time_seconds_bucket", "le"))],
        ),
        Panel(
            title="Order queue wait — p50 / p95 (TO family)",
            unit="s", w=12,
            targets=[Target("p50", _q(0.50, "order_queue_wait_seconds_bucket", "le")),
                     Target("p95", _q(0.95, "order_queue_wait_seconds_bucket", "le"))],
        ),
        Panel(
            title="Orders completed by outcome & reason (TO family)",
            unit="ops", w=12,
            targets=[Target("{{outcome}} {{reason}}",
                            'sum by (outcome, reason) (rate(orders_completed_total{job=~"$job"}[1m]))')],
        ),
        Panel(
            title="Business exception rate by type",
            unit="ops", w=12,
            targets=[Target("{{type}}", 'sum by (type) (rate(inventory_exception_total{job=~"$job"}[1m]))')],
        ),
        Panel(
            title="Optimistic locking — append success vs conflict",
            unit="ops", w=12,
            targets=[Target("append success", 'sum(rate(inventory_append_success_total{job=~"$job"}[1m]))'),
                     Target("retry", 'sum(rate(inventory_optimistic_retry_total{job=~"$job"}[1m]))'),
                     Target("exhausted", 'sum(rate(inventory_optimistic_exhausted_total{job=~"$job"}[1m]))')],
        ),
        Panel(
            title="Events processed by type",
            unit="ops", w=12,
            targets=[Target("{{eventType}}", 'sum by (eventType) (rate(es_events_processed_total{job=~"$job"}[1m]))')],
        ),
        Panel(
            title="Publish lag by event type — p50 / p95 / p99",
            unit="s", w=12,
            targets=[Target("{{eventType}} p50", _q(0.50, "publish_lag_seconds_bucket", "le, eventType")),
                     Target("{{eventType}} p95", _q(0.95, "publish_lag_seconds_bucket", "le, eventType")),
                     Target("{{eventType}} p99", _q(0.99, "publish_lag_seconds_bucket", "le, eventType"))],
        ),
        # Split by aggregate as well as phase, because two unrelated populations land in these
        # buckets: OrderAggregate is loaded from the store once per order and InventoryItem once per
        # line, and neither carried a label to tell them apart. A branch that caches InventoryItem
        # therefore does not move this panel's p50 so much as change which population the p50
        # describes — ES-4 reads as "1-event Order load", ES-2 as "snapshot + tail InventoryItem
        # load", and comparing the two numbers compares different work.
        # A run archived before the tag existed still resolves: those buckets carry no `aggregate`
        # label, so the grouping yields one series per phase exactly as it did before.
        # aggregate="unknown" is a delta read that identified no aggregate — the cache-repair probe,
        # not a load. See TimedEventStorageEngine on the ES branches.
        # `path` (ES-4 and later) names WHO asked, and adds no series: it is a label on groups this
        # panel already drew, verified against the archive — the ES-4 capacity run returns 13 series
        # with the grouping and 13 without. What it fixes is a legend that lied. `InventoryItem ·
        # events` reads as a cold write-path miss, but on a caching branch it is
        # PessimisticCachingRepository.catchUp reading the delta AFTER that command's append already
        # failed — the cost of the conflict, not of the write — and the snapshotter's fallback replay
        # pools into the same line. path=command is the write path, repair and snapshot are not.
        Panel(
            title="State load time by aggregate, path and phase — p50 / p95",
            unit="s", w=12,
            targets=[Target("{{aggregate}} · {{path}} · {{phase}} p50",
                            _q(0.50, "state_load_time_seconds_bucket", "le, phase, aggregate, path")),
                     Target("{{aggregate}} · {{path}} · {{phase}} p95",
                            _q(0.95, "state_load_time_seconds_bucket", "le, phase, aggregate, path"))],
        ),
        # Counts, because the panel above cannot answer "did the write path read the store at all".
        # A percentile of a phase with no samples does not draw a zero, it stops drawing, which on a
        # timeseries is indistinguishable from a scrape gap. `total` is emitted once per store round
        # trip end to end, so its rate IS store reads/s, and the cache-hit arm (`copy`) is excluded
        # by construction — a branch whose cache absorbs the write path has NO InventoryItem·command
        # line here at all. Measured on the phase-1 archive: ES-2 1427/s against ES-4 0/s, which is
        # the copy-on-write cache result in one panel.
        # Empty on the TO family, and correctly so: TO has no event store and its state_load_time
        # carries source="db_fetch" with no phase, so phase="total" matches nothing there.
        Panel(
            title="Store round trips by aggregate and path",
            unit="ops", w=24,
            description="Aggregate loads that actually went to the event store, once per round trip. "
                        "ES only — see the panel description in spec.py.",
            targets=[Target("{{aggregate}} · {{path}}",
                            'sum by (aggregate, path) (rate(state_load_time_seconds_count{job=~"$job",phase="total"}[1m]))')],
        ),
        # Committed only, so the series means what it meant before the outcome tag existed and a
        # replayed pre-tag run still resolves (those buckets carry no outcome label, and !="conflict"
        # selects an absent label where ="committed" would not). ES emits no outcome label and so
        # lands here unchanged. The conflict arm gets its own panel below rather than being folded
        # in: mixing them would move this panel's p99 for reasons that are not write cost.
        Panel(
            title="State persist time by source — p50 / p95 / p99 (committed)",
            unit="s", w=12,
            targets=[Target("{{source}} p50", _q(0.50, "state_persist_time_seconds_bucket", "le, source", extra='outcome!="conflict"')),
                     Target("{{source}} p95", _q(0.95, "state_persist_time_seconds_bucket", "le, source", extra='outcome!="conflict"')),
                     Target("{{source}} p99", _q(0.99, "state_persist_time_seconds_bucket", "le, source", extra='outcome!="conflict"'))],
        ),
        # The write time of attempts that blocked on the inventory_state row lock and then lost on
        # @Version. Recording only successes dropped exactly these, which is why the committed
        # series improves as contention worsens — read the two panels together, never one alone.
        Panel(
            title="State persist time — losing attempts (TO family)",
            unit="s", w=24,
            targets=[Target("conflict p50", _q(0.50, "state_persist_time_seconds_bucket", "le", extra='outcome="conflict"')),
                     Target("conflict p95", _q(0.95, "state_persist_time_seconds_bucket", "le", extra='outcome="conflict"')),
                     Target("conflict p99", _q(0.99, "state_persist_time_seconds_bucket", "le", extra='outcome="conflict"'))],
        ),
        Panel(
            title="Projection lag — p50 / p95 / p99",
            unit="s", w=12,
            targets=[Target("inventory p50", _q(0.50, "projection_lag_seconds_bucket", "le")),
                     Target("inventory p95", _q(0.95, "projection_lag_seconds_bucket", "le")),
                     Target("order p95", _q(0.95, "order_projection_lag_seconds_bucket", "le"))],
        ),
        Panel(
            title="Aggregate cache — hit vs miss vs catch-up",
            unit="ops", w=12,
            targets=[Target("hit", 'sum(rate(inventory_opt_cache_hit_total{job=~"$job"}[1m]))'),
                     Target("miss", 'sum(rate(inventory_opt_cache_miss_total{job=~"$job"}[1m]))'),
                     Target("catch-up", 'sum(rate(inventory_opt_catchup_total{job=~"$job"}[1m]))')],
        ),
        # The cache repair, which runs between a conflict and its retry. Split by outcome because the
        # probe fires on EVERY rollback and usually finds nothing — pooled, "noop" is the median and
        # the replay it is there to measure disappears. Read `applied` against the `copy` phase on the
        # state-load panel: together they are what a load costs on a branch that caches state, the way
        # snapshot+events+replay are on one that does not.
        # Full width: this section tiles in 24-wide rows and every other panel here is a half, so a
        # 23rd half-width panel would leave a hole. test_spec enforces it.
        Panel(
            title="Cache catch-up duration by outcome — p50 / p95",
            unit="s", w=24,
            targets=[Target("{{outcome}} p50", _q(0.50, "inventory_opt_catchup_duration_seconds_bucket", "le, outcome")),
                     Target("{{outcome}} p95", _q(0.95, "inventory_opt_catchup_duration_seconds_bucket", "le, outcome"))],
        ),
        Panel(
            title="Outbox backlog (TO family)",
            unit="short", w=12,
            targets=[Target("backlog", 'outbox_backlog{job=~"$job"}')],
        ),
        Panel(
            title="Outbox write time — p50 / p95 (TO family)",
            unit="s", w=12,
            targets=[Target("p50", _q(0.50, "outbox_write_time_seconds_bucket", "le")),
                     Target("p95", _q(0.95, "outbox_write_time_seconds_bucket", "le"))],
        ),
        # Replaces "Order worker — queue depth & active threads (TO family)": a general executor
        # panel covering pool size too, absorbed from TO-2's jvm-dashboard.json (Task 9 deleted
        # it; Task 10 merges its signal). The pinned-name TO-only panel would otherwise sit
        # alongside a superset of itself.
        Panel(
            title="Executor pools — threads & queue",
            unit="short", w=12,
            targets=[Target("{{name}} active", 'executor_active_threads{job=~"$job"}'),
                     Target("{{name}} pool size", 'executor_pool_size_threads{job=~"$job"}'),
                     Target("{{name}} queued", 'executor_queued_tasks{job=~"$job"}')],
        ),
        Panel(
            title="Saga outcome — completed vs failed (ES family)",
            unit="ops", w=24,
            targets=[Target("completed", 'sum(rate(saga_completed_total{job=~"$job"}[1m]))'),
                     Target("command failed", 'sum(rate(saga_command_failed_total{job=~"$job"}[1m]))')],
        ),
    ]),
    Section("JVM", [
        Panel(title="Heap memory", unit="bytes", w=12,
              targets=[Target("used", 'sum(jvm_memory_used_bytes{job=~"$job",area="heap"})'),
                       Target("max", 'sum(jvm_memory_max_bytes{job=~"$job",area="heap"})'),
                       Target("committed", 'sum(jvm_memory_committed_bytes{job=~"$job",area="heap"})')]),
        Panel(title="Non-heap memory by pool", unit="bytes", w=12,
              targets=[Target("{{id}}", 'sum(jvm_memory_used_bytes{job=~"$job",area="nonheap"}) by (id)')]),
        Panel(title="CPU", unit="percentunit", w=12, max=1,
              targets=[Target("process", 'avg(process_cpu_usage{job=~"$job"})'),
                       Target("system", 'avg(system_cpu_usage{job=~"$job"})')]),
        # avg/max only, no quantiles: Micrometer publishes buckets for a Timer solely when the
        # meter is listed under management.metrics.distribution.percentiles-histogram, and
        # jvm.gc.pause is on no branch's list, so jvm_gc_pause_seconds_bucket does not exist.
        Panel(title="GC pause duration — avg / max", unit="s", w=12,
              targets=[Target("avg",
                             'rate(jvm_gc_pause_seconds_sum{job=~"$job"}[1m]) / rate(jvm_gc_pause_seconds_count{job=~"$job"}[1m])'),
                       Target("max", 'jvm_gc_pause_seconds_max{job=~"$job"}')]),
        Panel(title="JVM threads", unit="short", w=8,
              targets=[Target("live", 'jvm_threads_live_threads{job=~"$job"}'),
                       Target("daemon", 'jvm_threads_daemon_threads{job=~"$job"}'),
                       Target("peak", 'jvm_threads_peak_threads{job=~"$job"}')]),
        Panel(title="Loaded classes", unit="short", w=8,
              targets=[Target("loaded classes", 'jvm_classes_loaded_classes{job=~"$job"}')]),
        Panel(title="Process uptime", unit="s", w=8,
              targets=[Target("uptime", 'process_uptime_seconds{job=~"$job"}')]),
        # Absorbed from TO-2's jvm-dashboard.json (Task 9 deleted it; Task 10 merges its signals).
        Panel(title="System load vs CPU cores", unit="short", w=8,
              targets=[Target("load 1m", 'system_load_average_1m{job=~"$job"}'),
                       Target("cpu cores", 'system_cpu_count{job=~"$job"}')]),
        Panel(title="Threads by state", unit="short", w=8,
              targets=[Target("{{state}}", 'jvm_threads_states_threads{job=~"$job"}')]),
        Panel(title="In-flight HTTP requests", unit="short", w=8,
              targets=[Target("in-flight", 'sum(http_server_requests_active_seconds_gcount{job=~"$job"})')]),
        Panel(title="GC allocation & promotion rate", unit="Bps", w=8,
              targets=[Target("allocation", 'rate(jvm_gc_memory_allocated_bytes_total{job=~"$job"}[1m])'),
                       Target("promotion", 'rate(jvm_gc_memory_promoted_bytes_total{job=~"$job"}[1m])')]),
        Panel(title="GC overhead", unit="percentunit", w=8, max=1,
              targets=[Target("overhead", 'jvm_gc_overhead{job=~"$job"}')]),
        Panel(title="Live data after GC", unit="bytes", w=8,
              targets=[Target("live data (old gen)", 'jvm_gc_live_data_size_bytes{job=~"$job"}'),
                       Target("old gen max", 'jvm_gc_max_data_size_bytes{job=~"$job"}')]),
        Panel(title="Log events by level", unit="ops", w=12,
              targets=[Target("{{level}}", 'sum by (level) (rate(logback_events_total{job=~"$job"}[1m]))')]),
        Panel(title="Spring Data repository invocations (top 10)", unit="ops", w=12,
              targets=[Target("{{repository}}.{{method}}",
                              'topk(10, sum by (repository, method) (rate(spring_data_repository_invocations_seconds_count{job=~"$job"}[1m])))')]),
    ]),
    Section("Spring pools", [
        Panel(title="HikariCP connections", unit="short", w=8,
              targets=[Target("active", 'hikaricp_connections_active{job=~"$job"}'),
                       Target("pending", 'hikaricp_connections_pending{job=~"$job"}'),
                       Target("max", 'hikaricp_connections_max{job=~"$job"}')]),
        # Absorbed from TO-2's jvm-dashboard.json and ES-1's inventory-es-dashboard.json
        # (Task 9 deleted both; Task 10 merges their signals).
        Panel(title="HikariCP — acquire & usage time", unit="s", w=8,
              targets=[Target("acquire avg",
                              'rate(hikaricp_connections_acquire_seconds_sum{job=~"$job"}[1m]) / rate(hikaricp_connections_acquire_seconds_count{job=~"$job"}[1m])'),
                       Target("acquire max", 'hikaricp_connections_acquire_seconds_max{job=~"$job"}'),
                       Target("usage avg",
                              'rate(hikaricp_connections_usage_seconds_sum{job=~"$job"}[1m]) / rate(hikaricp_connections_usage_seconds_count{job=~"$job"}[1m])'),
                       Target("usage max", 'hikaricp_connections_usage_seconds_max{job=~"$job"}')]),
        Panel(title="HikariCP — connection timeouts", unit="ops", w=8,
              targets=[Target("timeouts", 'rate(hikaricp_connections_timeout_total{job=~"$job"}[1m])')]),
        # The two halves of the same question, for both families: a lane is capped either by its
        # THREADS or by the CONNECTIONS those threads borrow, and reading one without the other
        # is how a saturated pool gets mistaken for a slow database.
        #
        # Both are broken out per pool, which the older "HikariCP connections" panel above is not:
        # its targets carry no legend label, so two pools render as two anonymous series. That was
        # harmless while every branch had one pool; it stopped being harmless once ES grew
        # axon-jdbc-pool alongside the primary. TO-3 also briefly had app-pool/write-pool
        # (2026-08-15 to 2026-08-17); per-pool legends outlive that and cost nothing.
        #
        # sum()-wrapped on purpose. Unaggregated, each expression returns one series PER REPLICA as
        # soon as REPLICAS>1, and the per-pool legend then repeats with no way to tell the copies
        # apart. `by (pool)` / `by (name)` keeps one line per pool across any replica count.
        #
        # Deliberately no tomcat_threads_*: it publishes nothing on either family, which is why
        # test_spec.NEVER_COLLECTED blocks it. The HTTP lane's connection cost is still visible
        # here — on both families the accept transaction runs on the Tomcat thread, so it shows up
        # as active connections on the pool serving it.
        #
        # Coverage is uneven by branch, and that is a property of the branches rather than of the
        # panel: executor_* exists wherever a ThreadPoolTaskExecutor bean does, plus TO-1/TO-2/TO-3/
        # TO-4, which bind it by hand because their merged order pool is not one; saga_pool_* only on
        # ES-4-NullLock-mod ({pool="command"|"retry"}) and ES-4-NullLock-A, which since the
        # 400-connection envelope publishes all three of {pool="saga-worker"|"command"|"retry"}.
        # Axon's pools are uninstrumented on the base ES branches, so an ES run that shows Hikari
        # connections but no thread series is reporting correctly, not broken.
        #
        # THE "order-retry" TARGET BELOW IS DEAD AS OF 2026-08-18 and should be removed: TO-1, TO-2,
        # TO-3 and TO-4 merged the retry pool into the worker pool, so NO branch publishes
        # order_retry_pool_active any more — there is no separate pool that can be active, and the
        # retried attempts are inside the order-worker executor_* series instead.
        # order_retry_pool_queued does still exist on those four but means only "in backoff", so
        # executor_queued_tasks minus it is the ready backlog.
        Panel(title="Busy threads by pool (TO & ES)", unit="short", w=12,
              targets=[Target("{{name}}", 'sum by (name) (executor_active_threads{job=~"$job"})'),
                       Target("order-retry", 'sum(order_retry_pool_active{job=~"$job"})'),
                       Target("saga {{pool}}", 'sum by (pool) (saga_pool_active{job=~"$job"})')]),
        Panel(title="Connections in use by pool (TO & ES)", unit="short", w=12,
              targets=[Target("{{pool}} active", 'sum by (pool) (hikaricp_connections_active{job=~"$job"})'),
                       Target("{{pool}} pending", 'sum by (pool) (hikaricp_connections_pending{job=~"$job"})'),
                       Target("{{pool}} max", 'sum by (pool) (hikaricp_connections_max{job=~"$job"})')]),
    ]),
    Section("PostgreSQL", [
        Panel(title="Database size", unit="bytes", w=8,
              targets=[Target("size", 'pg_database_size_bytes{datname="$db"}')]),
        Panel(title="WAL size", unit="bytes", w=8,
              targets=[Target("wal", "pg_wal_size_bytes")]),
        Panel(title="Active connections by state", unit="short", w=8,
              targets=[Target("{{state}}", 'pg_stat_activity_count{datname="$db"}')]),
        Panel(title="Transaction rate", unit="ops", w=8,
              targets=[Target("commits", 'rate(pg_stat_database_xact_commit{datname="$db"}[1m])'),
                       Target("rollbacks", 'rate(pg_stat_database_xact_rollback{datname="$db"}[1m])')]),
        Panel(title="Tuple operations rate", unit="ops", w=8,
              targets=[Target("inserted", 'rate(pg_stat_database_tup_inserted{datname="$db"}[1m])'),
                       Target("updated", 'rate(pg_stat_database_tup_updated{datname="$db"}[1m])'),
                       Target("deleted", 'rate(pg_stat_database_tup_deleted{datname="$db"}[1m])'),
                       Target("fetched", 'rate(pg_stat_database_tup_fetched{datname="$db"}[1m])')]),
        Panel(title="Buffer cache hit ratio", unit="percentunit", w=8, max=1,
              targets=[Target("hit ratio",
                              'rate(pg_stat_database_blks_hit{datname="$db"}[1m]) / '
                              '(rate(pg_stat_database_blks_hit{datname="$db"}[1m]) + '
                              'rate(pg_stat_database_blks_read{datname="$db"}[1m]))')]),
        Panel(title="Live rows by table", unit="short", w=8,
              targets=[Target("{{relname}}", 'pg_stat_user_tables_n_live_tup{schemaname="public"}')]),
        Panel(title="Per-table write rate", unit="ops", w=8,
              targets=[Target("{{relname}} ins", 'rate(pg_stat_user_tables_n_tup_ins{schemaname="public"}[1m])'),
                       Target("{{relname}} upd", 'rate(pg_stat_user_tables_n_tup_upd{schemaname="public"}[1m])'),
                       Target("{{relname}} del", 'rate(pg_stat_user_tables_n_tup_del{schemaname="public"}[1m])')]),
        Panel(title="Locks by mode", unit="short", w=8,
              targets=[Target("{{mode}}", 'pg_locks_count{datname="$db"}')]),
        # THE PANEL ABOVE CANNOT SHOW THE NOTIFY COMMIT LOCK, and the two below exist because of
        # it. NOTIFY-in-transaction serialises commits on a SHARED object lock
        # (locktype=object, classid=1262, objid=0, AccessExclusiveLock) held from pre-commit until
        # after the commit's WAL fsync. Being shared, its pg_locks.database is 0, so the stock
        # collector's `LEFT JOIN ON pg_database.oid = tmp2.database` drops it: measured, 39
        # backends blocked while accessexclusivelock above read 0. Both series come from
        # monitoring/postgres-exporter/queries.yaml -- see that file for the mechanism.
        #
        # No $db filter on either, deliberately: the lock is one per CLUSTER, not per database.
        #
        # These are gauges sampled at the 5s scrape, i.e. instantaneous queue depth. That is the
        # right shape (during the TO-2-fix-A collapse the queue stood for 24 minutes), but never
        # derive a rate from them and do not expect sub-scrape bursts to appear.
        Panel(title="NOTIFY commit-serialization lock", unit="short", w=12,
              targets=[Target("waiting", 'pg_notify_commit_lock_waiting'),
                       Target("held", 'pg_notify_commit_lock_held')]),
        # Corroborates the panel above without having to trust the classid: a backend blocked on
        # that lock reports Lock/object. Unlike the pair above this query IS grouped, so its
        # series legitimately VANISH when nothing waits -- read a gap here as zero, not as broken.
        Panel(title="Backends by wait event", unit="short", w=12,
              targets=[Target("{{type}}/{{event}}", 'pg_wait_backends')]),
        Panel(title="Checkpoint activity", unit="ops", w=12,
              targets=[Target("timed", "rate(pg_stat_bgwriter_checkpoints_timed_total[1m])"),
                       Target("requested", "rate(pg_stat_bgwriter_checkpoints_req_total[1m])"),
                       Target("write time", "rate(pg_stat_bgwriter_checkpoint_write_time_total[1m])")]),
        # name!="" on all three: cadvisor writes that label EXPLICITLY on every cgroup it
        # cannot attribute to a container, so an unresolved $dbc/$apic collapses the matcher
        # to name=~"|" and selects the host -- the machine root, every systemd unit, the
        # desktop session -- rather than selecting nothing. That is how the 2026-08-22
        # campaign's reports came out full of whole-machine CPU and RSS while looking
        # perfectly reasonable. Both variables are constants now, so this cannot arise from
        # variable resolution any more; the guard stays because being wrong here is silent
        # and being empty here is not.
        Panel(title="Container CPU", unit="percentunit", w=6,
              targets=[Target("{{name}}", 'rate(container_cpu_usage_seconds_total{name=~"$dbc|$apic",name!=""}[1m])')]),
        Panel(title="Container memory", unit="bytes", w=6,
              targets=[Target("{{name}} rss", 'container_memory_rss{name=~"$dbc|$apic",name!=""}'),
                       Target("{{name}} working set", 'container_memory_working_set_bytes{name=~"$dbc|$apic",name!=""}')]),
        # Absorbed from TO-2's jvm-dashboard.json (Task 9 deleted it; Task 10 merges its signals).
        Panel(title="Container network I/O", unit="Bps", w=24,
              targets=[Target("{{name}} rx", 'sum by (name) (rate(container_network_receive_bytes_total{name=~"$dbc|$apic",name!=""}[1m]))'),
                       Target("{{name}} tx", 'sum by (name) (rate(container_network_transmit_bytes_total{name=~"$dbc|$apic",name!=""}[1m]))')]),
    ]),
]
