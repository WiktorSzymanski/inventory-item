import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = parseInt(__ENV.VUS || '10');
const DURATION = __ENV.DURATION || '1m';

const reservationsMade = new Counter('reservations_made');
const insufficientStock = new Counter('insufficient_stock');
const reservationDuration = new Trend('reservation_duration', true);

const SEED_ITEMS = [
    { id: 'item-1', availableQty: 1000000 },
    // { id: 'item-2', availableQty: 10000 },
    // { id: 'item-3', availableQty: 10000 },
    // { id: 'item-4', availableQty: 10000 },
    // { id: 'item-5', availableQty: 10000 },
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
        reserve: {
            executor: 'constant-vus',
            vus: VUS,
            duration: DURATION,
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

    const items = listRes.json('content');

    if (!items || items.length === 0) {
        console.warn('No items in inventory — sleeping 1s');
        sleep(1);
        return;
    }

    const item = items[Math.floor(Math.random() * items.length)];
    const quantity = Math.floor(Math.random() * 10) + 1;
    const reservationId = `res-${__VU}-${__ITER}`;

    const reserveRes = http.post(
        `${BASE_URL}/inventory/reserve`,
        JSON.stringify({ id: item.itemId, reservationId, quantity }),
        { headers: { 'Content-Type': 'application/json' } },
    );

    reservationDuration.add(reserveRes.timings.duration);

    if (reserveRes.status === 202) {
        check(reserveRes, { 'reservation ok': () => true });
        reservationsMade.add(1);
        return;
    }

    if (reserveRes.status === 422) {
        check(reserveRes, { 'reservation ok': () => false });
        insufficientStock.add(1);
        return;
    }

    if (reserveRes.status === 409) {
        check(reserveRes, { 'reservation ok': () => false });
        console.warn(`VU ${__VU} iter ${__ITER}: conflict for reservationId ${reservationId}`);
        return;
    }

    check(reserveRes, { 'reservation ok': () => false });
    console.error(`VU ${__VU} iter ${__ITER}: unexpected status ${reserveRes.status}`);
}
