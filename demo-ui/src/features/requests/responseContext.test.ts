import { expect, it } from 'vitest';
import { responseContext } from './responseContext';

it('주문 입력으로 이동한 후에도 상품 조회 응답의 ID를 상품으로 연결한다', () => {
  const context = responseContext({ id: 'product-1', version: 7 }, '/api/products');
  expect(context.productId).toBe('product-1');
  expect(context).not.toHaveProperty('orderId');
  expect(context.productIdVersion).toBe(7);
  expect(context.expectedProductVersion).toBe(7);
  expect(context).not.toHaveProperty('expectedVersion');
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
