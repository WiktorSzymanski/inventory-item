// Single entry point for every scenario. SCENARIO selects the profile; all other
// variance lives in env vars, so this file is byte-identical on all 11 variant branches.
//
// The load path is deliberately FIRE-AND-FORGET. POST /inventory/orders returns 202
// Accepted after persisting only OrderCreatedEvent — the reservation happens
// asynchronously in the saga. So k6 cannot observe end-to-end latency without polling,
// and polling would add read load proportional to the test rate. End-to-end latency is
// therefore taken from the server-side order_e2e_time histogram (see k6/bench/dump.py).
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';

import { CONFIG, itemIds, validate } from './lib/config.js';
import { buildProfile } from './lib/profiles.js';
import { emitSummary } from './lib/summary.js';
import { createItem, getItem, listItems, postOrder } from './lib/api.js';
import { buildOrder, pickItem, rng } from './lib/workload.js';

validate();
const PROFILE = buildProfile();

export const options = {
    scenarios: PROFILE.scenarios,
    // Response bodies are never inspected on the hot path. Parsing them was measurable
    // k6 CPU in the old script and risked making the load generator a co-bottleneck.
    discardResponseBodies: true,
    summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
    // No latency thresholds here on purpose. k6 only sees admission latency, so a
    // threshold on it would assert something meaningless. The PASS/FAIL/INVALID verdict
    // is produced post-run by k6/bench/evaluate.py from the Prometheus dump.
    thresholds: {
        'checks{phase:measure}': ['rate>0.99'],
        // A profile may add its own. The warmup uses this to assert it actually delivered
        // WARMUP_ITERATIONS orders: a threshold breach is the only signal that reaches
        // bench.sh, because k6 exits 0 on a scenario that simply ran short.
        ...(PROFILE.thresholds || {}),
    },
};

const accepted = new Counter('orders_accepted');
const nonAccepted = new Counter('orders_non202');

export function seed() {
    for (const id of itemIds()) {
        const res = createItem(CONFIG.baseUrl, id, CONFIG.seedQty, CONFIG.payloadBytes, CONFIG.reserveDelayMs);
        // 409 means a previous run already created it, which is fine — bench.sh truncates
        // before every measured run, so a 409 here only happens on an ad-hoc invocation.
        if (res.status !== 201 && res.status !== 409) {
            fail(`seed ${id} failed: ${res.status} ${res.body}`);
        }
    }
    console.log(
        `seeded ${CONFIG.distinctItems} items, qty=${CONFIG.seedQty}, ` +
        `payload=${CONFIG.payloadBytes}B, reserve_delay=${CONFIG.reserveDelayMs}ms`,
    );
}

export function order() {
    const rand = rng(__VU);
    const userId = `user-${__VU}`;
    const res = postOrder(CONFIG.baseUrl, userId, buildOrder(rand, userId).items);

    // 202 is the only success. 422 and 409 are unreachable on this path: the only
    // exception ever thrown in the app is ItemAlreadyExistsException, and only from
    // POST /inventory. Out-of-stock surfaces as status=REJECTED on the order projection,
    // not as an HTTP error — so it is counted server-side via order_e2e_time{outcome}.
    if (res.status === 202) {
        accepted.add(1);
    } else {
        nonAccepted.add(1);
    }
    check(res, { 'order accepted (202)': (r) => r.status === 202 });
}

export function read() {
    const rand = rng(__VU);
    const res = CONFIG.readMode === 'list'
        ? listItems(CONFIG.baseUrl, CONFIG.readPageSize)
        : getItem(CONFIG.baseUrl, pickItem(rand));
    check(res, { 'read ok': (r) => r.status === 200 });
}

export function handleSummary(data) {
    return emitSummary(data, PROFILE);
}
