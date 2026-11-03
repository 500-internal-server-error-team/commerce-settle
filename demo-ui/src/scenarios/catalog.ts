import source from '../generated/catalog.json';
import { operations } from '../shared/api/contract';

type Screen = [string, string, string, string, ...unknown[]];
interface Case { label: string; screens: Screen[]; note: string; links: string[][] }
export interface Scenario extends Case { id: string; group: string; groupTitle: string }
export const groups = [
  ['buy', '구매·결제', '01'], ['sell', '판매·정산', '02'], ['refund', '취소·반품', '03'],
  ['account', '계정·설정', '04'], ['benefit', '쿠폰·마일리지', '05'],
  ['event', '선착순 이벤트', '06'], ['history', '내역·알림', '07'],
] as const;
export const scenarios: Scenario[] = Object.entries(source).flatMap(([group, value]) =>
  Object.entries(value.cases).map(([key, item]) => ({ ...(item as unknown as Case), id: `${group}.${key}`,
    group: key.startsWith('event') ? 'event' : group,
    groupTitle: key.startsWith('event') ? '선착순 이벤트' : value.title })));

const endpoints: Record<string, string[]> = {
  buy: ['/api/products', '/api/products/{productId}', '/api/order-quotes', '/api/orders', '/api/orders/{orderId}',
    '/api/payments', '/api/payments/{paymentId}', '/api/payments/{paymentId}/attempts', '/api/orders/{orderId}/payment-retries'],
  sell: ['/api/sellers/me/products', '/api/sellers/me/products/{productId}', '/api/sellers/me/products/{productId}/inventory',
    '/api/sellers/me/products/{productId}/inventory-adjustments', '/api/sellers/me/orders', '/api/sellers/me/orders/{orderId}',
    '/api/sellers/me/orders/{orderId}/shipments', '/api/sellers/me/settlement-items', '/api/sellers/me/settlement-batches',
    '/api/sellers/me/payouts', '/api/sellers/me/payouts/{payoutId}', '/internal/settlement-batches',
    '/internal/payout-jobs', '/internal/payout-jobs/{jobId}/replays'],
  refund: ['/api/orders', '/api/orders/{orderId}', '/api/orders/{orderId}/cancellation', '/api/orders/{orderId}/refund-requests',
    '/api/refund-requests/{requestId}', '/api/refund-requests/{requestId}/withdrawal', '/api/refund-requests/{requestId}/closure-confirmations',
    '/api/sellers/me/refund-requests', '/api/sellers/me/refund-requests/{requestId}/decision',
    '/api/sellers/me/refund-requests/{requestId}/receipt', '/api/refunds/{refundId}', '/internal/refund-requests/{requestId}/closure'],
  account: ['/api/buyers', '/api/sellers', '/api/buyer-sessions', '/api/seller-sessions', '/api/sessions/current', '/api/accounts/me',
    '/api/password-reset-requests', '/api/password-resets', '/api/buyers/me/addresses', '/api/buyers/me/addresses/{addressId}', '/api/sellers/me'],
  benefit: ['/api/buyers/me/coupons', '/api/buyers/me/mileage', '/api/buyers/me/mileage/entries',
    '/api/sellers/me/coupon-templates', '/api/sellers/me/coupon-templates/{templateId}',
    '/api/sellers/me/coupon-templates/{templateId}/issues', '/api/sellers/me/coupon-templates/{templateId}/closure', '/api/order-quotes'],
  event: ['/api/sellers/me/products', '/api/sellers/me/events', '/api/sellers/me/events/{eventId}',
    '/api/sellers/me/events/{eventId}/publication', '/api/sellers/me/events/{eventId}/closure',
    '/api/events', '/api/events/{eventId}', '/api/events/{eventId}/gauge', '/api/order-quotes', '/api/orders'],
  history: ['/api/orders', '/api/orders/{orderId}', '/api/payments', '/api/payments/{paymentId}/attempts', '/api/refunds/{refundId}',
    '/api/notifications', '/api/notifications/{notificationId}', '/api/buyers/me/mileage/entries',
    '/api/sellers/me/settlement-items', '/api/sellers/me/settlement-batches', '/api/sellers/me/payouts'],
  monthly: ['/internal/coupon-issue-runs', '/internal/coupon-issue-runs/{runId}',
    '/internal/coupon-issue-runs/{runId}/targets', '/internal/coupon-issue-runs/{runId}/replacements', '/api/buyers/me/coupons'],
};
export function actions(group: string) {
  return operations.filter(op => endpoints[group]?.includes(op.path));
}
export function requirements(id: string): string[] {
  const notes = ['화면 연결은 실행 검증 완료를 뜻하지 않습니다. 입력과 서버 응답으로 결과를 확인합니다.'];
  if (/normal|eligible|holiday|timeout|closed|restore/.test(id)) notes.push('기한·마감에 도달한 실제 데이터가 필요합니다. 업무 시간 이동 API는 아직 없습니다.');
  if (/waiting|unknown|failed|declined|cancelwait|unavailable/.test(id)) notes.push('해당 대기·거절·장애 상태를 실제 백엔드에서 준비해야 합니다. 화면에서 성공·실패를 만들지 않습니다.');
  if (/password/.test(id)) notes.push('복구 토큰은 기존 서버의 전달 경로에서 확인합니다. 요청 응답에 토큰이 제공되지는 않습니다.');
  return notes;
}
