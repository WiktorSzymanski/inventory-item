import { CONFIG, HARNESS_VERSION } from './config.js';

// Writes machine-readable artifacts next to the human summary. summary.json is the
// authoritative CLIENT-side source (the Prometheus remote-write series are computed per
// push interval, so a run-wide percentile cannot be derived from them). profile.json
// carries the resolved config and the step offsets that dump.py converts into absolute
// window boundaries.
export function emitSummary(data, profile) {
    const out = {};
    const dir = CONFIG.outDir;

    out[`${dir}/summary.json`] = JSON.stringify(data, null, 2);
    out[`${dir}/profile.json`] = JSON.stringify(
        {
            harness_version: HARNESS_VERSION,
            scenario: profile.name,
            run_id: CONFIG.runId,
            expected_seconds: profile.totalSeconds,
            steps: profile.steps,
            // Recorded so evaluate.py can check vus_max against the ceiling actually in
            // force, rather than against a default it would otherwise have to guess.
            vu_limits: Object.fromEntries(
                Object.entries(profile.scenarios).map(([k, v]) => [
                    k,
                    { preAllocatedVUs: v.preAllocatedVUs ?? v.vus ?? null, maxVUs: v.maxVUs ?? null },
                ]),
            ),
            config: CONFIG,
        },
        null,
        2,
    );

    out.stdout = textSummary(data, profile);
    return out;
}

function fmt(n, unit = '', digits = 2) {
    if (n === undefined || n === null || Number.isNaN(n)) return 'n/a';
    return `${n.toFixed(digits)}${unit}`;
}

function textSummary(data, profile) {
    const m = data.metrics ?? {};
    const iterations = m.iterations?.values?.count ?? 0;
    const dropped = m.dropped_iterations?.values?.count ?? 0;
    const accepted = m.orders_accepted?.values?.count ?? 0;
    const non202 = m.orders_non202?.values?.count ?? 0;
    const vusMax = m.vus_max?.values?.max ?? 0;
    const reqDur = m.http_req_duration?.values ?? {};
    const failed = m.http_req_failed?.values?.rate ?? 0;

    const lines = [
        '',
        `  scenario           ${profile.name}   run_id=${CONFIG.runId}`,
        `  workload           distinct=${CONFIG.distinctItems} lines/order=${CONFIG.itemsPerOrder} ` +
        `qty/line=${CONFIG.qtyPerLine} payload=${CONFIG.payloadBytes}B ` +
        `reserve_delay=${CONFIG.reserveDelayMs}ms read_rate=${CONFIG.readRate}`,
        '',
        `  iterations         ${iterations}`,
        `  orders accepted    ${accepted} (202)`,
        `  orders non-202     ${non202}`,
        `  dropped iterations ${dropped}` +
        (iterations > 0 ? `  (${fmt((dropped / (iterations + dropped)) * 100, '%')})` : ''),
        `  vus_max reached    ${vusMax}`,
        `  http_req_failed    ${fmt(failed * 100, '%')}`,
        `  admission latency  p50=${fmt(reqDur.med, 'ms')} p95=${fmt(reqDur['p(95)'], 'ms')} ` +
        `p99=${fmt(reqDur['p(99)'], 'ms')}`,
        '',
        '  NOTE: admission latency is the 202-Accepted time, NOT order completion time.',
        '        End-to-end latency comes from the order_e2e_time histogram in the',
        '        Prometheus dump (dump.json), never from this summary.',
        '',
    ];
    return lines.join('\n');
}
