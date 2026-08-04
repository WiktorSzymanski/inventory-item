import { CONFIG, itemIds } from './config.js';

// Deterministic per-VU RNG (xorshift32). Seeding on SEED ^ VU means the item-selection
// sequence is byte-for-byte identical across variants for a given SEED, which removes a
// real source of run-to-run variance from every A/B comparison.
export function rng(vu) {
    let s = ((CONFIG.seed ^ (vu * 2654435761)) >>> 0) || 1;
    return () => {
        s ^= s << 13; s >>>= 0;
        s ^= s >>> 17;
        s ^= s << 5; s >>>= 0;
        return s / 4294967296;
    };
}

const IDS = itemIds();

// Partial Fisher-Yates: unbiased and O(k). The old script used
// `slice().sort(() => Math.random() - 0.5)` which is both statistically biased and
// O(n log n) over a 100-element array on every iteration — measurable k6 CPU at 600/s.
export function buildOrder(rand, userId) {
    const pool = IDS.slice();
    const lines = [];
    const k = Math.min(CONFIG.itemsPerOrder, pool.length);

    for (let i = 0; i < k; i++) {
        const j = i + Math.floor(rand() * (pool.length - i));
        const tmp = pool[i]; pool[i] = pool[j]; pool[j] = tmp;
        lines.push({ itemId: pool[i], quantity: CONFIG.qtyPerLine });
    }

    // Only reachable with ALLOW_DUP_LINES=true (config.validate rejects it otherwise).
    if (CONFIG.allowDupLines) {
        while (lines.length < CONFIG.itemsPerOrder) {
            lines.push({
                itemId: IDS[Math.floor(rand() * IDS.length)],
                quantity: CONFIG.qtyPerLine,
            });
        }
    }

    return { userId, items: lines };
}

export function pickItem(rand) {
    return IDS[Math.floor(rand() * IDS.length)];
}
