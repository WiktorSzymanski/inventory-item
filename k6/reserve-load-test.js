import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MAX_RPS = parseInt(__ENV.MAX_RPS || '300');
const RAMP_DURATION = __ENV.RAMP_DURATION || '10m';
const ITEMS_PER_ORDER = parseInt(__ENV.ITEMS_PER_ORDER || '1');

const ordersMade = new Counter('orders_made');
const insufficientStock = new Counter('insufficient_stock');
const orderDuration = new Trend('order_duration', true);

const SEED_ITEMS = [
    { id: 'item-1', availableQty: 1000000, additionalBytesSize: 1048576},
    // { id: 'item-2', availableQty: 1000000 },
    // { id: 'item-3', availableQty: 1000000 },
    // { id: 'item-4', availableQty: 1000000 },
    // { id: 'item-5', availableQty: 1000000 },
    // { id: 'item-6', availableQty: 1000000 },
    // { id: 'item-7', availableQty: 1000000 },
    // { id: 'item-8', availableQty: 1000000 },
    // { id: 'item-9', availableQty: 1000000 },
    // { id: 'item-10', availableQty: 1000000 },
];

export function setup() {
    for (const item of SEED_ITEMS) {
        const res = http.post(
            `${BASE_URL}/inventory`,
            JSON.stringify(item),
            { headers: { 'Content-Type': 'application/json' } },
        );
        if (res.status === 201) {
            console.log(`Created item ${item.id} with qty ${item.availableQty}`);
        } else if (res.status === 409) {
            console.log(`Item ${item.id} already exists, skipping`);
        } else {
            console.error(`Failed to create item ${item.id}: ${res.status} ${res.body}`);
        }
    }
    sleep(2);
}

export const options = {
    scenarios: {
        order: {
            executor: 'ramping-arrival-rate',
            startRate: 100,
            timeUnit: '1s',
            preAllocatedVUs: 800,
            maxVUs: 2000,
            stages: [
                { target: 300, duration: '10m' },
            ],
        },
    },
};

export default function () {
    const listRes = http.get(`${BASE_URL}/inventory?size=100`);

    if (!check(listRes, { 'inventory list ok': r => r.status === 200 })) {
        console.error(`GET /inventory failed: ${listRes.status}`);
        sleep(1);
        return;
    }

    const allItems = listRes.json('content');

    if (!allItems || allItems.length === 0) {
        console.warn('No items in inventory — sleeping 1s');
        sleep(1);
        return;
    }

    // Pick ITEMS_PER_ORDER distinct items at random
    const shuffled = allItems.slice().sort(() => Math.random() - 0.5);
    const picked = shuffled.slice(0, Math.min(ITEMS_PER_ORDER, shuffled.length));
    const orderItems = picked.map(item => ({
        itemId: item.itemId,
        quantity: Math.floor(Math.random() * 10) + 1,
    }));

    const orderRes = http.post(
        `${BASE_URL}/inventory/orders`,
        JSON.stringify({ userId: `user-${__VU}`, items: orderItems }),
        { headers: { 'Content-Type': 'application/json' } },
    );

    orderDuration.add(orderRes.timings.duration);

    if (orderRes.status === 202) {
        check(orderRes, { 'order ok': () => true });
        ordersMade.add(1);
        return;
    }

    if (orderRes.status === 422) {
        check(orderRes, { 'order ok': () => false });
        insufficientStock.add(1);
        return;
    }

    if (orderRes.status === 409) {
        check(orderRes, { 'order ok': () => false });
        console.warn(`VU ${__VU} iter ${__ITER}: optimistic lock conflict`);
        return;
    }

    check(orderRes, { 'order ok': () => false });
    console.error(`VU ${__VU} iter ${__ITER}: unexpected status ${orderRes.status} body=${orderRes.body}`);
}
