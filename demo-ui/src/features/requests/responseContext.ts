import type { Json } from '../../shared/api/contract';

/** Preserve the producing request's resource identity when a historical response is reused. */
export function responseContext(row: Record<string, Json>, sourcePath: string,
  parentParameters: Record<string, string> = {}): Record<string, Json> {
  const data = row.order && typeof row.order === 'object' ? row.order as Record<string, Json> : row;
  const next: Record<string, Json> = { ...parentParameters, ...Object.fromEntries(Object.entries(data).filter(([key, value]) =>
    !/password|token|address/i.test(key) && (typeof value === 'string' || typeof value === 'number'))) };
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
  if (data.version !== undefined && idKey) next[`${idKey}Version`] = data.version;
  if (data.version !== undefined && idKey === 'productId') next.expectedProductVersion = data.version;
  if (data.version !== undefined && idKey === 'eventId') next.expectedEventVersion = data.version;
  if (data.productVersion !== undefined) next.expectedProductVersion = data.productVersion;
  if (data.eventVersion !== undefined) next.expectedEventVersion = data.eventVersion;
  if (data.totalAmount !== undefined) next.expectedTotalAmount = data.totalAmount;
  return next;
}
