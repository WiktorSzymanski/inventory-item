import { CONFIG } from './config.js';

// Parse a k6 duration string ("90s", "10m", "1h30m") into seconds. Needed so the optional
// read scenario can be sized to the write scenario, and so meta.json carries a real
// expected wall time.
export function durationSeconds(d) {
    let total = 0;
    const re = /(\d+(?:\.\d+)?)(h|m|s|ms)/g;
    let m;
    while ((m = re.exec(d)) !== null) {
        const v = parseFloat(m[1]);
        total += m[2] === 'h' ? v * 3600 : m[2] === 'm' ? v * 60 : m[2] === 'ms' ? v / 1000 : v;
    }
    if (total === 0) throw new Error(`unparsable duration: ${d}`);
    return Math.round(total);
}

// preAllocatedVUs must cover peakRate x worst-case admission latency. At ~5ms admission
// latency one VU serves ~200 iter/s, but under saturation Tomcat queues and admission
// latency climbs into seconds, so size for a ~3s worst case.
//
// maxVUs is deliberately generous: `dropped_iterations` in an arrival-rate executor means
// exactly one thing — "no free VU". If maxVUs is the binding constraint you are measuring
// k6, not the system. evaluate.py cross-checks vus_max against maxVUs for this reason.
function vus(peak) {
    return {
        preAllocatedVUs: Math.max(50, Math.ceil(peak * CONFIG.vuHeadroom)),
        maxVUs: Math.max(500, Math.ceil(peak * CONFIG.vuCeiling)),
    };
}

function orderScenario(extra) {
    return { exec: 'order', gracefulStop: '60s', tags: { phase: 'measure' }, ...extra };
}

// Optional concurrent read load. A separate scenario rather than a READ_RATIO because a
// ratio makes read and write rates non-independent and turns http_req_duration into a
// bimodal blend that distorts the arrival-rate executor's VU accounting.
function readScenario(totalSeconds) {
    if (CONFIG.readRate <= 0) return {};
    return {
        read: {
            executor: 'constant-arrival-rate',
            exec: 'read',
            tags: { phase: 'read' },
            rate: CONFIG.readRate,
            timeUnit: '1s',
            duration: `${totalSeconds}s`,
            gracefulStop: '10s',
            ...vus(CONFIG.readRate),
        },
    };
}

// ---------------------------------------------------------------- capacity staircase
function capacity() {
    const stages = [];
    const steps = [];
    let t = 0;

    for (let i = 0; i < CONFIG.stepCount; i++) {
        const target = CONFIG.stepStart + i * CONFIG.stepInc;
        stages.push({ target, duration: `${CONFIG.stepRampS}s` });
        stages.push({ target, duration: `${CONFIG.stepPlateauS}s` });

        const plateauFrom = t + CONFIG.stepRampS;
        steps.push({
            index: i,
            targetRate: target,
            startsAt: t,
            plateauFrom,
            // Discard the leading fraction of each plateau: queue transients, plus it
            // absorbs the ~0.3-1s of k6 init skew in the T0 anchoring so we never need
            // k6 to observe the host clock.
            stableFrom: plateauFrom + Math.round(CONFIG.stepPlateauS * CONFIG.stepTrim),
            endsAt: plateauFrom + CONFIG.stepPlateauS,
        });
        t = plateauFrom + CONFIG.stepPlateauS;
    }

    const peak = CONFIG.stepStart + (CONFIG.stepCount - 1) * CONFIG.stepInc;
    return {
        name: 'capacity',
        steps,
        totalSeconds: t,
        scenarios: {
            order: orderScenario({
                executor: 'ramping-arrival-rate',
                startRate: CONFIG.stepStart,
                timeUnit: '1s',
                stages,
                ...vus(peak),
            }),
            ...readScenario(t),
        },
    };
}

// ---------------------------------------------------------------- constant rate
function constantRate(name, rate, duration) {
    const total = durationSeconds(duration);
    return {
        name,
        steps: [],
        totalSeconds: total,
        scenarios: {
            order: orderScenario({
                executor: 'constant-arrival-rate',
                rate,
                timeUnit: '1s',
                duration,
                ...vus(rate),
            }),
            ...readScenario(total),
        },
    };
}

// ---------------------------------------------------------------- spike & drain
// Zero -> peak -> zero. Both idle segments carry weight:
//
//   * the leading one gives the burst a quiet baseline to be measured against, and hands
//     the settle phase a second, uncapped chance to finish draining the warmup backlog —
//     so `pre`'s in-flight level, which check_recovery uses as its floor, is the system
//     at rest rather than whatever the 60s SETTLE_S cap left behind;
//   * the trailing one lets the burst's backlog clear with nothing arriving on top of it,
//     which is what makes recovery_seconds a property of the variant instead of a
//     property of the base rate the operator happened to pick.
//
// It used to be base -> 4x base -> base, with the peak defined as SPIKE_BASE x
// SPIKE_FACTOR. That made "recovery" a measurement taken while a load was still arriving,
// and it sized the burst off a number that was itself a load level. SPIKE_PEAK names the
// burst rate outright; the campaign's `SPIKE_BASE=0.4xK SPIKE_FACTOR=4` is `SPIKE_PEAK=1.6xK`.
//
// The backlog a burst leaves behind takes far longer to clear than the 240s tail, and it is
// meant to: bench.sh drains past the load phase and defaults DRAIN_TIMEOUT to 30m for this
// scenario. The recovery series is dumped over window_full = [T0, T2], so the moment
// in-flight comes back down is found whether it lands inside the tail or inside the drain.
function spike() {
    const peak = CONFIG.spikePeak;
    const stages = [
        { target: 0, duration: '60s' },      // idle baseline
        { target: peak, duration: '10s' },   // ramp in
        { target: peak, duration: '60s' },   // burst
        { target: 0, duration: '10s' },      // ramp out
        { target: 0, duration: '240s' },     // recovery, nothing arriving
    ];
    const total = stages.reduce((a, s) => a + durationSeconds(s.duration), 0);
    return {
        name: 'spike',
        // Phase offsets let evaluate.py measure recovery: how long after the burst ends
        // the in-flight backlog returns to the idle level `pre` established.
        steps: [
            { index: 0, label: 'pre', targetRate: 0, stableFrom: 30, endsAt: 60 },
            { index: 1, label: 'burst', targetRate: peak, stableFrom: 80, endsAt: 130 },
            { index: 2, label: 'post', targetRate: 0, stableFrom: 260, endsAt: total },
        ],
        totalSeconds: total,
        scenarios: {
            order: orderScenario({
                executor: 'ramping-arrival-rate',
                startRate: 0,
                timeUnit: '1s',
                stages,
                ...vus(peak),
            }),
            ...readScenario(total),
        },
    };
}

// ---------------------------------------------------------------- setup phases
function seedProfile() {
    return {
        name: 'seed',
        steps: [],
        totalSeconds: 0,
        scenarios: {
            seed: {
                executor: 'shared-iterations',
                exec: 'seed',
                iterations: 1,
                vus: 1,
                maxDuration: '10m',
                tags: { phase: 'seed' },
            },
        },
    };
}

// Warmup delivers a fixed iteration COUNT at a fixed RATE, so both the aging depth and the
// backlog handed to T0 are identical across variants. See CONFIG.warmupRate for why the old
// 50-VU closed loop could only provide the first of those two.
//
// WARMUP_MAX_DURATION is now a validation ceiling rather than an executor setting:
// constant-arrival-rate has no maxDuration, and the shared-iterations one it replaced would
// silently stop early on hitting it — delivering fewer than WARMUP_ITERATIONS orders while
// still exiting 0, which is exactly how the fixed-iteration invariant could break unnoticed.
function warmupProfile() {
    const rate = CONFIG.warmupRate;
    if (rate <= 0) {
        throw new Error(
            'WARMUP_RATE is not set. It is a per-point calibration knob, not a harness ' +
            'default: set it in points.env for the point being run (roughly half the ' +
            'slowest variant\'s sustained rate there), or pass WARMUP_RATE=<orders/s> ' +
            'explicitly. There is deliberately no fallback — the rate that warms W-base ' +
            'overloads C11 by more than 3x, and warming up above capacity is the failure ' +
            'this replaced.',
        );
    }
    // Rounded UP, never down: the delivered count is rate x seconds + 1 (the executor fires
    // at t=0 as well), so WARMUP_ITERATIONS is a floor rather than an exact figure — 200 at
    // 100/s delivers 201. What matters is that the number is a pure function of the knobs
    // and not of how fast the variant is, which is the property the fixed-iteration warmup
    // exists to give. Expect the count in warmup/k6.log to sit slightly above the knob.
    const seconds = Math.ceil(CONFIG.warmupIters / rate);
    const cap = durationSeconds(CONFIG.warmupMaxDur);
    if (seconds > cap) {
        throw new Error(
            `warmup would run ${seconds}s (${CONFIG.warmupIters} iterations at ${rate}/s) ` +
            `but WARMUP_MAX_DURATION is ${CONFIG.warmupMaxDur} (${cap}s). Raise the cap or ` +
            `WARMUP_RATE. Lowering WARMUP_ITERATIONS also works, but it changes the state ` +
            `every variant starts from, so it has to change for a whole comparison or not ` +
            `at all.`,
        );
    }
    return {
        name: 'warmup',
        steps: [],
        totalSeconds: seconds,
        // A dropped iteration here means the VU pool, not the system, decided how many
        // warmup orders were submitted — the fixed-iteration invariant broken silently,
        // since k6 would still exit 0. Failing the threshold is what makes bench.sh die.
        thresholds: { dropped_iterations: ['count==0'] },
        scenarios: {
            warmup: {
                executor: 'constant-arrival-rate',
                exec: 'order',
                rate,
                timeUnit: '1s',
                duration: `${seconds}s`,
                gracefulStop: '60s',
                tags: { phase: 'warmup' },
                ...vus(rate),
            },
        },
    };
}

// ---------------------------------------------------------------- back-compat
// Reproduces the old reserve-load-test.js shape for `docker compose up k6`, minus the
// in-loop GET /inventory. Not thesis-grade: no clean slate, no warmup, no dump.
function legacy() {
    const total = durationSeconds(CONFIG.legacyRamp);
    return {
        name: 'legacy',
        steps: [],
        totalSeconds: total,
        scenarios: {
            order: orderScenario({
                executor: 'ramping-arrival-rate',
                startRate: CONFIG.legacyStartRate,
                timeUnit: '1s',
                stages: [{ target: CONFIG.legacyMaxRps, duration: CONFIG.legacyRamp }],
                preAllocatedVUs: 800,
                maxVUs: 2000,
            }),
        },
    };
}

function legacyVus() {
    const total = durationSeconds(CONFIG.duration);
    return {
        name: 'legacy-vus',
        steps: [],
        totalSeconds: total,
        scenarios: {
            order: orderScenario({
                executor: 'constant-vus',
                vus: CONFIG.legacyVus,
                duration: CONFIG.duration,
            }),
        },
    };
}

const BUILDERS = {
    seed: seedProfile,
    warmup: warmupProfile,
    capacity,
    steady: () => constantRate('steady', CONFIG.rate, CONFIG.duration),
    soak: () => constantRate('soak', CONFIG.rate, CONFIG.soakDuration),
    // Same shape as `steady`. The difference is entirely in how it is RUN (RATE set above
    // the measured knee) and how it is JUDGED (see thresholds.json). Giving it its own name
    // rather than reusing `steady` is what lets evaluate.py apply overload rules, and what
    // keeps the two apart in bench-results/.
    stress: () => constantRate('stress', CONFIG.rate, CONFIG.duration),
    spike,
    legacy,
    'legacy-vus': legacyVus,
};

export function buildProfile() {
    const build = BUILDERS[CONFIG.scenario];
    if (!build) {
        throw new Error(
            `unknown SCENARIO=${CONFIG.scenario}; known: ${Object.keys(BUILDERS).join(', ')}`,
        );
    }
    return build();
}
