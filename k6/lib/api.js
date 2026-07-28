// Thin HTTP wrappers. Field names match the controller DTOs exactly:
//   CreateItemRequest(id, availableQty, additionalBytesSize)
//   CreateOrderRequest(userId, items: [OrderItemRequest(itemId, quantity)])
import http from 'k6/http';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function createItem(baseUrl, id, availableQty, additionalBytesSize) {
    return http.post(
        `${baseUrl}/inventory`,
        JSON.stringify({ id, availableQty, additionalBytesSize }),
        { headers: JSON_HEADERS, tags: { op: 'create_item' }, responseType: 'text' },
    );
}

export function postOrder(baseUrl, userId, items) {
    return http.post(
        `${baseUrl}/inventory/orders`,
        JSON.stringify({ userId, items }),
        { headers: JSON_HEADERS, tags: { op: 'create_order' } },
    );
}

export function getItem(baseUrl, itemId) {
    return http.get(`${baseUrl}/inventory/${itemId}`, { tags: { op: 'get_item' } });
}

export function listItems(baseUrl, size) {
    return http.get(`${baseUrl}/inventory?size=${size}`, { tags: { op: 'list_items' } });
}
