import type { Json } from '../../shared/api/contract';

/** Preserve the producing request's resource identity when a historical response is reused. */
export function responseContext(row: Record<string, Json>, sourcePath: string,
  parentParameters: Record<string, string> = {}, requestBody: Json = null): Record<string, Json> {
  const data = row.order && typeof row.order === 'object' ? row.order as Record<string, Json> : row;
  const quote = sourcePath === '/api/order-quotes' && requestBody && typeof requestBody === 'object' && !Array.isArray(requestBody)
    ? Object.fromEntries(Object.entries(requestBody).filter(([key]) => ['productId', 'quantity', 'eventId', 'buyerCouponId', 'mileageAmount', 'externalMethod'].includes(key))) : {};
  const purchase = sourcePath === '/api/order-quotes' || /\/orders(\/|$)/.test(sourcePath);
  const related = data !== row ? Object.fromEntries(Object.entries(row).filter(([key, value]) =>
    ['orderId', 'paymentId', 'requestId', 'refundId', 'payoutId', 'batchId'].includes(key) && (typeof value === 'string' || value === null))) : {};
  const next: Record<string, Json> = { ...quote, ...parentParameters, ...related, ...Object.fromEntries(Object.entries(data).filter(([key, value]) =>
    !/password|token|address|credential|secret/i.test(key)
    // Shared context carries identities and explicit transaction inputs, not another resource's editable fields.
    && (key.endsWith('Id') || ['targetMonth', 'batchDate'].includes(key)
      || purchase && ['quantity', 'mileageAmount', 'externalMethod'].includes(key))
    // A coupon's product/seller constrain applicability, not the user's current selection.
    && !(['productId', 'sellerId'].includes(key) && /\/(coupons|coupon-templates)(\/|$)/.test(sourcePath))
    && (typeof value === 'string' || typeof value === 'number' || value === null && key.endsWith('Id')))) };
  let idKey: string | undefined;
  if (data.id) {
    const resources = [
      ['coupon-issue-runs', 'runId'], ['coupon-templates', 'templateId'], ['refund-requests', 'requestId'],
      ['products', 'productId'], ['events', 'eventId'], ['payments', 'paymentId'], ['payouts', 'payoutId'],
      ['addresses', 'addressId'], ['orders', 'orderId'],
      ['coupons', 'buyerCouponId'], ['refunds', 'refundId'], ['notifications', 'notificationId'],
      ['settlement-batches', 'batchId'], ['payout-jobs', 'jobId'],
    ];
    const resource = sourcePath.split('/').filter(part => part && !part.startsWith('{')).at(-1);
    idKey = resources.find(([name]) => name === resource)?.[1]
      ?? Object.entries(parentParameters).find(([, id]) => id === data.id)?.[0];
    if (idKey) next[idKey] = data.id;
  }
  if (data.version !== undefined && idKey) {
    next[`${idKey}Version`] = data.version;
    next[`${idKey}VersionFor`] = data.id;
  }
  if (/\/inventory(?:-adjustments)?$/.test(sourcePath) && typeof data.productId === 'string' && data.version !== undefined) {
    next.inventoryProductId = data.productId; next.inventoryVersion = data.version;
  }
  if (data.version !== undefined && idKey === 'productId') next.expectedProductVersion = data.version;
  if (data.version !== undefined && idKey === 'eventId') next.expectedEventVersion = data.version;
  if (purchase && data.productVersion !== undefined) next.expectedProductVersion = data.productVersion;
  if (purchase && data.eventVersion !== undefined) next.expectedEventVersion = data.eventVersion;
  if (purchase && data.totalAmount !== undefined) next.expectedTotalAmount = data.totalAmount;
  return next;
}

export function mergeResponseContext(previous: Record<string, Json>, next: Record<string, Json>): Record<string, Json> {
  const merged = { ...previous, ...next };
  if (Object.hasOwn(next, 'productId') && next.productId !== previous.productId && !Object.hasOwn(next, 'eventId')) {
    if (previous.eventId !== undefined) merged.eventId = null;
    delete merged.eventIdVersion; delete merged.eventIdVersionFor; delete merged.expectedEventVersion;
  }
  for (const key of Object.keys(next).filter(key => key.endsWith('Id'))) {
    if (next[key] === previous[key]) continue;
    if (next[`${key}VersionFor`] !== next[key]) {
      delete merged[`${key}Version`]; delete merged[`${key}VersionFor`];
    }
    const expected = key === 'productId' ? 'expectedProductVersion' : key === 'eventId' ? 'expectedEventVersion' : null;
    if (expected && !Object.hasOwn(next, expected)) delete merged[expected];
    if ((key === 'productId' || key === 'eventId') && !Object.hasOwn(next, 'expectedTotalAmount')) delete merged.expectedTotalAmount;
  }
  if (['productId', 'eventId', 'buyerCouponId', 'quantity', 'mileageAmount', 'externalMethod'].some(key =>
    Object.hasOwn(next, key) && next[key] !== previous[key]) && !Object.hasOwn(next, 'expectedTotalAmount')) delete merged.expectedTotalAmount;
  return merged;
}
