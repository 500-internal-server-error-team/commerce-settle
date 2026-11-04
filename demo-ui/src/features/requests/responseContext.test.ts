import { expect, it } from 'vitest';
import { mergeResponseContext, responseContext } from './responseContext';

it('주문 입력으로 이동한 후에도 상품 조회 응답의 ID를 상품으로 연결한다', () => {
  const context = responseContext({ id: 'product-1', version: 7 }, '/api/products');
  expect(context.productId).toBe('product-1');
  expect(context).not.toHaveProperty('orderId');
  expect(context.productIdVersion).toBe(7);
  expect(context.expectedProductVersion).toBe(7);
  expect(context).not.toHaveProperty('expectedVersion');
});
it('상품을 참조하는 다른 응답으로 ID가 바뀌면 이전 상품 버전·견적을 지운다', () => {
  const before = responseContext({ id: 'product-A', version: 7 }, '/api/products');
  const next = responseContext({ id: 'event-B', productId: 'product-B', version: 2 }, '/api/events');
  const merged = mergeResponseContext({ ...before, expectedTotalAmount: 10000 }, next);
  expect(merged.productId).toBe('product-B');
  expect(merged).not.toHaveProperty('productIdVersion');
  expect(merged).not.toHaveProperty('expectedProductVersion');
  expect(merged).not.toHaveProperty('expectedTotalAmount');
  expect(merged.eventIdVersionFor).toBe('event-B');
});
it('재고 조회 뒤 조정 성공 응답의 새 버전으로 같은 상품의 재고 pair를 갱신한다', () => {
  const before = responseContext({ productId: 'product-A', version: 3 }, '/api/sellers/me/products/{productId}/inventory');
  const next = responseContext({ productId: 'product-A', version: 4 }, '/api/sellers/me/products/{productId}/inventory-adjustments');
  expect(mergeResponseContext(before, next)).toMatchObject({ inventoryProductId: 'product-A', inventoryVersion: 4 });
});
it('일반 주문의 null 이벤트 ID는 이전 행사 선택과 버전을 지운다', () => {
  const before = responseContext({ id: 'event-A', version: 7 }, '/api/events');
  const next = responseContext({ id: 'order-B', eventId: null }, '/api/orders');
  expect(mergeResponseContext(before, next)).toMatchObject({ eventId: null });
  expect(mergeResponseContext(before, next)).not.toHaveProperty('eventIdVersion');
});
it('견적을 만든 실제 요청의 상품·조건을 가격·버전과 함께 연결한다', () => {
  const before = responseContext({ id: 'product-A', version: 7 }, '/api/products');
  const next = responseContext({ totalAmount: 12000, productVersion: 3, eventVersion: null }, '/api/order-quotes', {},
    { productId: 'product-B', quantity: 2, eventId: null, buyerCouponId: null, mileageAmount: 0, externalMethod: 'CREDIT_CARD', token: 'private' });
  expect(mergeResponseContext(before, next)).toMatchObject({ productId: 'product-B', quantity: 2, expectedProductVersion: 3, expectedTotalAmount: 12000 });
  expect(next).not.toHaveProperty('token');
});

it('월간 대상과 결제 시도를 선택해도 부모 회차·결제 ID를 덮어쓰지 않는다', () => {
  const target = responseContext({ id: 'target-1', buyerId: 'buyer-1' },
    '/internal/coupon-issue-runs/{runId}/targets', { runId: 'run-1' });
  expect(target.runId).toBe('run-1');
  const attempt = responseContext({ id: 'attempt-1', status: 'APPROVED' },
    '/api/payments/{paymentId}/attempts', { paymentId: 'payment-1' });
  expect(attempt.paymentId).toBe('payment-1');
});

it('서버가 반환한 주문과 가격·이벤트 버전을 다음 작업에 전달한다', () => {
  const context = responseContext({ order: { id: 'order-1', productId: 'product-1', productVersion: 3,
    eventVersion: 2, totalAmount: 11000, token: 'private' } }, '/api/orders');
  expect(context).toMatchObject({ orderId: 'order-1', productId: 'product-1', expectedProductVersion: 3,
    expectedEventVersion: 2, expectedTotalAmount: 11000 });
  expect(context).not.toHaveProperty('token');
});
