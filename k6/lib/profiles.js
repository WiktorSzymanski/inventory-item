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
function spike() {
    const base = CONFIG.spikeBase;
    const peak = Math.round(base * CONFIG.spikeFactor);
    const stages = [
        { target: base, duration: '2m' },
        { target: peak, duration: '10s' },
        { target: peak, duration: '60s' },
        { target: base, duration: '10s' },
        { target: base, duration: '4m' },
    ];
    const total = stages.reduce((a, s) => a + durationSeconds(s.duration), 0);
    return {
        name: 'spike',
        // Phase offsets let evaluate.py measure recovery: how long after the burst ends
        // the in-flight backlog returns to its pre-spike level.
        steps: [
            { index: 0, label: 'pre', targetRate: base, stableFrom: 60, endsAt: 120 },
            { index: 1, label: 'burst', targetRate: peak, stableFrom: 140, endsAt: 190 },
            { index: 2, label: 'post', targetRate: base, stableFrom: 320, endsAt: total },
        ],
        totalSeconds: total,
        scenarios: {
            order: orderScenario({
                executor: 'ramping-arrival-rate',
                startRate: base,
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

function warmupProfile() {
    return {
        name: 'warmup',
        steps: [],
        totalSeconds: durationSeconds(CONFIG.warmupMaxDur),
        scenarios: {
            warmup: {
                executor: 'shared-iterations',
                exec: 'order',
                iterations: CONFIG.warmupIters,
                vus: CONFIG.warmupVus,
                maxDuration: CONFIG.warmupMaxDur,
                tags: { phase: 'warmup' },
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
