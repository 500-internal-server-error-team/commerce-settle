import { execute, type Execution } from '../../shared/api/client';
import { operations } from '../../shared/api/contract';

export const owners = ['commerce', 'payment', 'settlement'] as const;
export type Owner = typeof owners[number];
export const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const referenceKeys = new Set(['orderId', 'paymentId', 'refundId', 'requestId', 'payoutId', 'batchId', 'eventId']);
export interface EventRecord {
  owner: Owner; kind: 'OUTBOX' | 'INBOX'; id: string; eventId: string; eventType: string; aggregateId: string;
  status: string; topic: string; consumer: string | null; partition: number | null; offset: number | string | null;
  attempts: number; lastErrorCode: string | null; resultRef: string | null; createdAt: string; updatedAt: string; referenceIds: string[];
}
export interface SourceState { status: 'ok' | 'error' | 'unconfigured'; detail: string; observedAt?: string; truncated?: boolean }
export interface Observation { records: EventRecord[]; sources: Record<Owner, SourceState>; limited: boolean; calls: number }
export type ResultTarget = 'order' | 'payment' | 'refund' | 'settlement-item';
/** resultRef has a different meaning for each handler, even within one owner. */
export function resultTarget(row: EventRecord): ResultTarget | undefined {
  if (!row.resultRef || row.kind !== 'INBOX') return;
  if (row.owner === 'commerce' && row.consumer === 'commerce.v1'
    && ['PaymentAccepted', 'PaymentCancellationRejected', 'PaymentConfirmed', 'PaymentDeclined', 'PaymentCancelled',
      'RefundAccepted', 'RefundCompleted', 'RefundFailed'].includes(row.eventType)) return 'order';
  if (row.owner === 'payment' && row.consumer === 'payment.v1') {
    if (['OrderPaymentRequested', 'PaymentCancellationRequested'].includes(row.eventType)) return 'payment';
    if (['RefundApproved', 'RefundOrderRestored', 'RefundReservationResult', 'RefundLedgerReversed', 'RefundReservationReleased'].includes(row.eventType)) return 'refund';
  }
  if (row.owner === 'settlement' && row.consumer === 'settlement.v1'
    && ['PaymentConfirmed', 'PurchaseConfirmed'].includes(row.eventType)) return 'settlement-item';
}

/** Account/product/address IDs must not turn a single-transaction inspection into a broad account search. */
export function requestReferences(record?: Execution): string[] {
  if (!record) return [];
  const found = new Set<string>();
  const visit = (value: unknown) => {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return;
    for (const [key, item] of Object.entries(value)) {
      if (referenceKeys.has(key) && typeof item === 'string' && uuid.test(item)) found.add(item.toLowerCase());
      else if (item && typeof item === 'object' && !Array.isArray(item)) visit(item);
    }
  };
  visit(record.request); visit(record.response);
  if (/\/(orders|payments|refunds|refund-requests|payouts|settlement-batches)(\/|$)/.test(record.path)) {
    const result = record.response as Record<string, unknown> | null;
    if (typeof result?.id === 'string' && uuid.test(result.id)) found.add(result.id.toLowerCase());
    const order = result?.order as Record<string, unknown> | undefined;
    if (typeof order?.id === 'string' && uuid.test(order.id)) found.add(order.id.toLowerCase());
  }
  return [...found];
}

export async function observeEvents(ids: string[], configured: string[], signal: AbortSignal): Promise<Observation> {
  const sources = Object.fromEntries(owners.map(owner => [owner, { status: 'unconfigured', detail: '운영자 자격 미설정' }])) as Record<Owner, SourceState>;
  const active = new Set(owners.filter(owner => configured.includes(owner)));
  const found = new Map<string, EventRecord>();
  const known = new Set(ids.map(id => id.toLowerCase()));
  let next = [...known]; let calls = 0; let limited = false;
  for (let round = 0; round < 3 && next.length && active.size; round++) {
    const batch = next; const discovered = new Set<string>();
    await Promise.all(owners.filter(owner => active.has(owner)).map(async owner => {
      const operation = operations.find(op => op.id === `get_internal_${owner}_event_snapshot`);
      try {
        if (!operation) throw new Error('이 버전에 조회 API가 없습니다.');
        calls++;
        const result = await execute(operation, { pathParameters: {}, query: { ids: batch.join(',') }, body: null, idempotencyKey: '' }, undefined, signal);
        if (signal.aborted) return;
        if (result.status !== 200) throw new Error(`HTTP ${result.status} · 서비스 연결과 운영자 자격을 확인하세요.`);
        const data = result.data as Record<string, unknown> | null;
        if (!data || !Array.isArray(data.records) || data.records.length > 50 || typeof data.truncated !== 'boolean' || typeof data.observedAt !== 'string') {
          throw new Error('관측 응답 형식을 확인할 수 없습니다.');
        }
        const records = data.records as Omit<EventRecord, 'owner'>[];
        if (records.some(item => !item || !['OUTBOX', 'INBOX'].includes(item.kind) || !uuid.test(item.id)
          || !uuid.test(item.eventId) || !uuid.test(item.aggregateId) || typeof item.eventType !== 'string'
          || typeof item.status !== 'string' || !Array.isArray(item.referenceIds))) throw new Error('이벤트 기록 형식을 확인할 수 없습니다.');
        for (const item of records) {
          const entry = { ...item, owner }; found.set(`${owner}:${item.kind}:${item.id}`, entry);
          for (const reference of [item.eventId, item.aggregateId, item.resultRef, ...item.referenceIds]) {
            if (typeof reference === 'string' && uuid.test(reference)) discovered.add(reference.toLowerCase());
          }
        }
        const prior = sources[owner];
        sources[owner] = { status: 'ok', detail: '조회 완료', observedAt: data.observedAt, truncated: !!prior.truncated || data.truncated };
      } catch (error) {
        if (signal.aborted) return;
        active.delete(owner); // 같은 수동 관측에서 실패한 소스를 자동 재시도하지 않는다.
        sources[owner] = { ...sources[owner], status: 'error', detail: error instanceof Error ? error.message : '조회하지 못했습니다.' };
      }
    }));
    if (signal.aborted) throw new DOMException('Observation aborted', 'AbortError');
    next = [];
    for (const reference of [...discovered].sort()) if (!known.has(reference)) {
      if (known.size >= 20 || round === 2) { limited = true; continue; }
      known.add(reference); next.push(reference);
    }
  }
  return { records: [...found.values()].sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.eventId.localeCompare(b.eventId)), sources, limited, calls };
}
