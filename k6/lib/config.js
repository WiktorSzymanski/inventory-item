// Resolved run configuration. Every knob is an env var with a default. This file — and the
// whole k6/ directory — now lives once, on `main`, shared by every variant branch.
// There is no bench.env any more; main.js/lib.sh derive what used to live there.

export const HARNESS_VERSION = '1.0.0';

const int = (n, d) => parseInt(__ENV[n] ?? String(d), 10);
const num = (n, d) => parseFloat(__ENV[n] ?? String(d));
const str = (n, d) => __ENV[n] ?? d;
const bool = (n, d) => String(__ENV[n] ?? d).toLowerCase() === 'true';

export const CONFIG = {
    baseUrl: str('BASE_URL', 'http://localhost:8080'),
    scenario: str('SCENARIO', 'steady'),
    runId: str('RUN_ID', 'adhoc'),
    outDir: str('OUT_DIR', '/reports/adhoc'),
    seed: int('SEED', 1337),

    // ---- workload shape: the sweep axes --------------------------------------
    distinctItems: int('DISTINCT_ITEMS', 6),
    itemsPerOrder: int('ITEMS_PER_ORDER', 4),
    // Fixed, not random. A random 1..10 quantity adds run-to-run variance with no
    // scientific content, and at 4 lines/order over 6 items it drains a 1e6 stock in
    // ~24 minutes — after which a soak is benchmarking the compensation path instead.
    qtyPerLine: int('QTY_PER_LINE', 1),
    seedQty: int('SEED_QTY', 2000000000),
    // additionalBytesSize on POST /inventory. Note this rides ONLY on
    // InventoryCreatedEvent, never on InventoryReservedEvent, so it does not inflate
    // the append path. It inflates snapshot rows and PessimisticCachingRepository's
    // per-command deep copy — i.e. it is a copy-on-write / snapshot-IO lever.
    payloadBytes: int('PAYLOAD_BYTES', 0),
    // reserveDelayMs on POST /inventory: a Thread.sleep inside the aggregate on every
    // successful reserve, standing in for expensive domain logic. Unlike PAYLOAD_BYTES this
    // DOES ride the reserve path, and it caps achievable throughput hard — the lock (DB row on
    // TO, aggregate on ES) is held for the whole sleep. Ceiling is roughly
    // workers / (ITEMS_PER_ORDER x delay) on TO and DISTINCT_ITEMS / delay on ES, so at 4
    // lines x 1000ms a run tops out near 2 orders/s. Sweep 5-50ms before anything larger, and
    // lower STEP_START / RATE to match or the staircase saturates at step 0 and reads INVALID.
    reserveDelayMs: int('RESERVE_DELAY_MS', 0),
    itemPrefix: str('ITEM_PREFIX', 'item'),
    allowDupLines: bool('ALLOW_DUP_LINES', false),

    // ---- rates ---------------------------------------------------------------
    rate: int('RATE', 50),
    duration: str('DURATION', '10m'),
    stepStart: int('STEP_START', 20),
    stepInc: int('STEP_INC', 20),
    stepCount: int('STEP_COUNT', 8),
    stepRampS: int('STEP_RAMP_S', 15),
    stepPlateauS: int('STEP_PLATEAU_S', 120),
    stepTrim: num('STEP_TRIM', 0.4),
    spikeBase: int('SPIKE_BASE', 25),
    spikeFactor: num('SPIKE_FACTOR', 4),
    soakDuration: str('SOAK_DURATION', '45m'),

    // ---- warmup: fixed ITERATIONS, never a duration --------------------------
    // A duration-based warmup gives a fast variant proportionally more warmup events,
    // so variants would enter the measured window with different event-store depth,
    // snapshot count and cache state — a systematic bias against the very thing under
    // measurement. Fixed iterations make the starting state identical everywhere.
    warmupIters: int('WARMUP_ITERATIONS', 5000),
    warmupVus: int('WARMUP_VUS', 50),
    warmupMaxDur: str('WARMUP_MAX_DURATION', '5m'),

    // ---- optional read load (separate scenario, default off) -----------------
    readRate: int('READ_RATE', 0),
    readMode: str('READ_MODE', 'item'), // item | list
    readPageSize: int('READ_PAGE_SIZE', 100),

    // ---- VU pool sizing ------------------------------------------------------
    vuHeadroom: num('VU_HEADROOM', 3),
    vuCeiling: num('VU_CEILING', 25),

    // ---- legacy back-compat profile -----------------------------------------
    legacyStartRate: int('MAX_RPS_START', 100),
    legacyMaxRps: int('MAX_RPS', 300),
    legacyRamp: str('RAMP_DURATION', str('DURATION', '10m')),
    legacyVus: int('VUS', 50),
};

export function itemIds() {
    return Array.from(
        { length: CONFIG.distinctItems },
        (_, i) => `${CONFIG.itemPrefix}-${i + 1}`,
    );
}

export function validate() {
    if (!CONFIG.allowDupLines && CONFIG.itemsPerOrder > CONFIG.distinctItems) {
        throw new Error(
            `ITEMS_PER_ORDER=${CONFIG.itemsPerOrder} > DISTINCT_ITEMS=${CONFIG.distinctItems}. ` +
            `The old script silently degraded this to a shorter order, which is why past ` +
            `"1 item, 4 per order" runs were really 1-line orders. Set ALLOW_DUP_LINES=true ` +
            `to deliberately hit one aggregate more than once per order.`,
        );
    }
    if (CONFIG.itemsPerOrder < 1) throw new Error('ITEMS_PER_ORDER must be >= 1');
    if (CONFIG.distinctItems < 1) throw new Error('DISTINCT_ITEMS must be >= 1');
    if (CONFIG.qtyPerLine < 1) throw new Error('QTY_PER_LINE must be >= 1');
    if (CONFIG.qtyPerLine * CONFIG.itemsPerOrder > CONFIG.seedQty) {
        throw new Error('SEED_QTY is too small for one order');
    }
}
