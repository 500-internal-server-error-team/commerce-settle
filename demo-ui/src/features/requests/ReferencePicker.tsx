import { useEffect, useRef, useState } from 'react';
import { execute } from '../../shared/api/client';
import { operations, type Json, type Operation } from '../../shared/api/contract';
import { Select } from '../../shared/ui/Select';

export function referenceSource(name: string, operation: Operation, role: string): { title: string; operation: Operation } | undefined {
  const seller = role === 'SELLER';
  const sources: Record<string, [string, string]> = {
    productId: ['상품', seller ? '/api/sellers/me/products' : '/api/products'],
    eventId: ['이벤트', seller ? '/api/sellers/me/events' : '/api/events'],
    addressId: ['배송지', '/api/buyers/me/addresses'], buyerCouponId: ['쿠폰', '/api/buyers/me/coupons'],
    orderId: ['주문', seller ? '/api/sellers/me/orders' : '/api/orders'],
    paymentId: ['결제', '/api/payments'], templateId: ['쿠폰 정책', '/api/sellers/me/coupon-templates'],
    requestId: ['반품 요청', '/api/sellers/me/refund-requests'],
    payoutId: ['지급', '/api/sellers/me/payouts'], runId: ['월간 회차', '/internal/coupon-issue-runs'],
    notificationId: ['알림', '/api/notifications'],
    jobId: ['처리 작업', operation.path.includes('payout') ? '/internal/payout-jobs' : '/internal/payment-jobs'],
    quarantineId: ['격리 내역', '/internal/settlement-quarantines'],
    deliveryId: ['알림 전달', '/internal/notification-deliveries'],
  };
  const source = sources[name];
  if (!source) return;
  const target = operations.find(op => op.path === source[1] && op.method === 'GET'
    && (op.actor === 'PUBLIC' || op.actor.includes(role)));
  return target && { title: source[0], operation: target };
}

/** List envelopes differ by owner. Keep only the actual entity identity and its own version. */
export function referenceRows(items: Json[], path: string): Record<string, Json>[] {
  return items.flatMap(item => {
    if (!item || typeof item !== 'object' || Array.isArray(item)) return [];
    let row = item;
    if (path === '/api/sellers/me/orders' && item.order && typeof item.order === 'object' && !Array.isArray(item.order)) row = item.order;
    if (path === '/internal/notification-deliveries' && item.notification && typeof item.notification === 'object' && !Array.isArray(item.notification)) {
      row = { id: item.notification.id, status: item.notification.status, version: item.version };
    }
    return typeof row.id === 'string' ? [row] : [];
  });
}

export function ReferencePicker({ source, token, choose }: {
  source: { title: string; operation: Operation }; token?: string;
  choose: (row: Record<string, Json>, source: Operation) => void;
}) {
  const [rows, setRows] = useState<Record<string, Json>[] | null>(null);
  const [page, setPage] = useState(0); const [pages, setPages] = useState(0);
  const [loading, setLoading] = useState(false); const [error, setError] = useState('');
  const [selected, setSelected] = useState('');
  const abort = useRef<AbortController | null>(null);
  useEffect(() => { setRows(null); setSelected(''); setError(''); setLoading(false); return () => abort.current?.abort(); }, [source.operation.id, token]);
  async function load(nextPage: number) {
    abort.current?.abort(); const controller = new AbortController(); abort.current = controller;
    setLoading(true); setError(''); setRows(null); setSelected('');
    try {
      const result = await execute(source.operation, { body: null, pathParameters: {},
        query: { page: String(nextPage), size: '20' }, idempotencyKey: '' }, token, controller.signal);
      if (controller.signal.aborted) return;
      if (result.status >= 300) throw new Error(result.status === 401 || result.status === 403
        ? '이 역할로 먼저 로그인하세요.' : `목록 조회 실패 (HTTP ${result.status}) · 서버 연결과 실행 기록을 확인하세요.`);
      const data = result.data;
      const object = data && typeof data === 'object' && !Array.isArray(data) ? data : {};
      const items = Array.isArray(data) ? data : object.items;
      if (!Array.isArray(items)) throw new Error('목록 응답 형식을 확인할 수 없습니다.');
      const records = referenceRows(items, source.operation.path);
      if (records.length !== items.length) throw new Error('일부 항목의 ID를 확인할 수 없습니다. 응답 명세를 확인하세요.');
      setRows(records);
      setPage(nextPage); setPages(Number(object.totalPages ?? 1)); setSelected('');
    } catch (e) { if (!controller.signal.aborted) setError(e instanceof Error ? e.message : '조회하지 못했습니다.'); }
    finally { if (abort.current === controller) setLoading(false); }
  }
  return <div className="reference-picker">
    <button type="button" className="text-button" disabled={loading} onClick={() => load(0)}>
      {loading ? '목록 확인 중…' : `${source.title} 목록 불러오기`}</button>
    {error && <small role="alert" className="error">{error}</small>}
    {rows && <><Select aria-label={`${source.title} 선택`} value={selected} disabled={loading} onChange={event => {
      setSelected(event.target.value);
      const row = rows.find(row => row.id === event.target.value); if (row) choose(row, source.operation);
    }}><option value="">{rows.length ? '실제 내역을 선택하세요' : '조회된 내역이 없습니다'}</option>
      {rows.map(row => <option key={String(row.id)} value={String(row.id)}>
        {String(row.name ?? row.targetMonth ?? row.orderNumber ?? row.recipient ?? row.status ?? source.title)} · {String(row.id)}
      </option>)}</Select>
      {!rows.length && <small>현재 계정·조회 조건에 데이터가 없습니다. 해당 항목을 먼저 등록하거나 생성하세요.</small>}
      {pages > 1 && <div className="reference-pages"><button type="button" className="text-button" disabled={loading || page === 0} onClick={() => load(page - 1)}>이전</button>
        <small>{page + 1} / {pages} 페이지</small><button type="button" className="text-button" disabled={loading || page + 1 >= pages} onClick={() => load(page + 1)}>다음</button></div>}
    </>}
  </div>;
}
