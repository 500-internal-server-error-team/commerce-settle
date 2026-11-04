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
const taskIds = {
  products: 'get_api_products', product: 'get_api_products_productId', quote: 'post_api_order_quotes',
  order: 'post_api_orders', orders: 'get_api_orders', orderDetail: 'get_api_orders_orderId',
  payment: 'get_api_payments_paymentId', attempts: 'get_api_payments_paymentId_attempts',
  retry: 'post_api_orders_orderId_payment_retries', cancel: 'post_api_orders_orderId_cancellation',
  buyer: 'post_api_buyers', seller: 'post_api_sellers', login: 'post_api_buyer_sessions',
  account: 'get_api_accounts_me', resetRequest: 'post_api_password_reset_requests', reset: 'post_api_password_resets',
  addresses: 'get_api_buyers_me_addresses', address: 'post_api_buyers_me_addresses', editAddress: 'put_api_buyers_me_addresses_addressId',
  profile: 'get_api_sellers_me', editProfile: 'put_api_sellers_me',
  sellerProducts: 'get_api_sellers_me_products', addProduct: 'post_api_sellers_me_products',
  sellerProduct: 'get_api_sellers_me_products_productId', editProduct: 'put_api_sellers_me_products_productId',
  removeProduct: 'delete_api_sellers_me_products_productId', inventory: 'get_api_sellers_me_products_productId_inventory',
  adjustInventory: 'post_api_sellers_me_products_productId_inventory_adjustments',
  sales: 'get_api_sellers_me_orders', sale: 'get_api_sellers_me_orders_orderId',
  items: 'get_api_sellers_me_settlement_items', batches: 'get_api_sellers_me_settlement_batches',
  payouts: 'get_api_sellers_me_payouts', payout: 'get_api_sellers_me_payouts_payoutId',
  payoutJobs: 'get_internal_payout_jobs',
  returnRequest: 'post_api_orders_orderId_refund_requests', returnDetail: 'get_api_refund_requests_requestId',
  returns: 'get_api_sellers_me_refund_requests', decision: 'post_api_sellers_me_refund_requests_requestId_decision',
  receipt: 'post_api_sellers_me_refund_requests_requestId_receipt', withdraw: 'post_api_refund_requests_requestId_withdrawal',
  refund: 'get_api_refunds_refundId', closeRefund: 'post_internal_refund_requests_requestId_closure',
  closureConfirmation: 'post_api_refund_requests_requestId_closure_confirmations',
  coupons: 'get_api_buyers_me_coupons', campaigns: 'get_api_sellers_me_coupon_templates',
  campaign: 'get_api_sellers_me_coupon_templates_templateId', issue: 'post_api_sellers_me_coupon_templates_templateId_issues',
  closeCampaign: 'post_api_sellers_me_coupon_templates_templateId_closure',
  mileage: 'get_api_buyers_me_mileage', mileageEntries: 'get_api_buyers_me_mileage_entries',
  addEvent: 'post_api_sellers_me_events', publishEvent: 'post_api_sellers_me_events_eventId_publication',
  sellerEvent: 'get_api_sellers_me_events_eventId', closeEvent: 'post_api_sellers_me_events_eventId_closure',
  events: 'get_api_events', gauge: 'get_api_events_eventId_gauge',
  notifications: 'get_api_notifications', notification: 'get_api_notifications_notificationId',
} as const;

// Each conceptual screen opens an existing operation; observing a state never fabricates it.
const flowTasks: Record<string, (keyof typeof taskIds)[]> = {
  'account.buyer': ['buyer', 'login', 'address', 'products'],
  'account.seller': ['seller', 'editProfile', 'sellerProducts', 'addProduct'],
  'account.login': ['login', 'login', 'account', 'login'],
  'account.password': ['resetRequest', 'resetRequest', 'reset', 'login'],
  'account.reauth': ['orderDetail', 'login', 'orderDetail', 'orderDetail'],
  'account.address': ['addresses', 'editAddress', 'addresses', 'order'],
  'account.profile': ['profile', 'editProfile', 'profile', 'payout'],
  'account.denied': ['account', 'login', 'orders', 'account'],
  'buy.normal': ['products', 'order', 'orderDetail', 'orderDetail'],
  'buy.waiting': ['orderDetail', 'payment', 'orderDetail', 'orders'],
  'buy.declined': ['payment', 'retry', 'payment', 'orderDetail'],
  'buy.changed': ['product', 'product', 'quote', 'order'],
  'buy.stock': ['products', 'product', 'products', 'order'],
  'buy.methods': ['order', 'quote', 'order', 'payment'],
  'sell.normal': ['sellerProducts', 'sales', 'items', 'payout'],
  'sell.product': ['sellerProducts', 'editProduct', 'adjustInventory', 'sellerProduct'],
  'sell.remove': ['sellerProduct', 'removeProduct', 'sellerProduct', 'sellerProducts'],
  'sell.eligible': ['sales', 'items', 'items', 'batches'],
  'sell.pending': ['batches', 'payout', 'payout', 'payouts'],
  'sell.holiday': ['batches', 'payout', 'payout', 'payout'],
  'sell.failed': ['payout', 'payout', 'payoutJobs', 'payouts'],
  'refund.unpaid': ['orderDetail', 'cancel', 'payment', 'orderDetail'],
  'refund.cancelwait': ['orderDetail', 'orderDetail', 'payment', 'orderDetail'],
  'refund.timeout': ['orderDetail', 'orderDetail', 'payment', 'orderDetail'],
  'refund.preship': ['orderDetail', 'returnRequest', 'refund', 'orderDetail'],
  'refund.return': ['returnRequest', 'returns', 'refund', 'refund'],
  'refund.withdraw': ['returnDetail', 'withdraw', 'returnDetail', 'orderDetail'],
  'refund.rejected': ['returns', 'decision', 'returnDetail', 'orderDetail'],
  'refund.unknown': ['returnDetail', 'refund', 'refund', 'refund'],
  'refund.failed': ['refund', 'refund', 'closureConfirmation', 'closeRefund'],
  'refund.receipt': ['returns', 'receipt', 'inventory', 'inventory'],
  'refund.closed': ['orderDetail', 'orderDetail', 'orders', 'returnDetail'],
  'benefit.coupons': ['coupons', 'coupons', 'order', 'orders'],
  'benefit.campaign': ['campaigns', 'issue', 'coupons', 'closeCampaign'],
  'benefit.budget': ['campaign', 'campaign', 'coupons', 'coupons'],
  'benefit.mixed': ['mileage', 'order', 'payment', 'refund'],
  'benefit.allmileage': ['mileage', 'order', 'payment', 'refund'],
  'benefit.insufficient': ['order', 'mileage', 'mileage', 'quote'],
  'benefit.invalid': ['coupons', 'quote', 'quote', 'order'],
  'benefit.restore': ['orders', 'refund', 'coupons', 'coupons'],
  'benefit.eventowner': ['addEvent', 'publishEvent', 'sellerEvent', 'closeEvent'],
  'benefit.eventbuyer': ['events', 'order', 'payment', 'gauge'],
  'benefit.eventclosed': ['gauge', 'gauge', 'orders', 'events'],
  'history.buyer': ['orders', 'orderDetail', 'attempts', 'orderDetail'],
  'history.seller': ['sales', 'batches', 'payouts', 'sales'],
  'history.notifications': ['notifications', 'notification', 'orders', 'orderDetail'],
  'history.mileage': ['mileage', 'mileageEntries', 'payment', 'orders'],
  'history.empty': ['orders', 'sales', 'items', 'coupons'],
  'history.unavailable': ['orderDetail', 'orderDetail', 'orderDetail', 'orderDetail'],
};

export function workflow(scenario: Scenario, role: string) {
  return scenario.screens.map((screen, index) => {
    let task = flowTasks[scenario.id]?.[index];
    if (role === 'SELLER' && screen[0] === '양쪽') {
      if (task === 'orders') task = 'sales';
      if (task === 'orderDetail') task = 'sale';
    }
    const id = task === 'login' && role === 'SELLER' ? 'post_api_seller_sessions' : task && taskIds[task];
    return { screen, operation: operations.find(op => op.id === id) };
  });
}
export function startingStep(id: string) {
  return id === 'buy.waiting' || id === 'refund.unknown' ? 1 : id === 'buy.changed' ? 2 : 0;
}
export function actions(group: string, related: string[] = []) {
  return operations.filter(op => endpoints[group]?.includes(op.path) || related.includes(op.id));
}
export function requirements(id: string): string[] {
  const notes = ['각 흐름의 결과는 실제 입력과 서버 응답으로 확인하세요. 화면에 표시된 단계는 설계된 흐름입니다.'];
  if (/normal|eligible|holiday|timeout|closed|restore/.test(id)) notes.push('기한·마감에 도달한 실제 데이터가 필요합니다. 업무 시간 이동 API는 아직 없습니다.');
  if (/waiting|unknown|failed|declined|cancelwait|unavailable/.test(id)) notes.push('대기·거절·장애 흐름을 시연하려면 해당 상태의 데이터를 백엔드에 준비해야 합니다.');
  if (/password/.test(id)) notes.push('복구 토큰은 기존 서버의 전달 경로에서 확인합니다. 요청 응답에 토큰이 제공되지는 않습니다.');
  return notes;
}
