import { useEffect, useRef, useState } from 'react';
import { execute } from '../../shared/api/client';
import { operations, type Json, type Operation } from '../../shared/api/contract';
import { Select } from '../../shared/ui/Select';
import { codeLabel } from '../../shared/ui/labels';

const emptyGuidance: Record<string, string> = {
  상품: '선택할 상품이 없습니다. 판매자로 상품을 등록하고 판매 가능한 재고를 준비하세요.',
  주문: '아직 이 계정의 주문이 없습니다. 판매 상품을 선택해 주문을 접수하세요.',
  결제: '아직 이 구매자의 결제가 없습니다. 주문 접수 뒤 결제 처리가 시작되어야 표시됩니다. 먼저 내 주문 상태를 확인하세요.',
  '반품 요청': '이 계정의 취소·반품 요청이 없습니다. 해당 주문 상세에서 요청을 접수하세요.',
  환불: '아직 이 구매자의 금융 환불이 없습니다. 취소·반품 요청의 승인과 처리 상태를 먼저 확인하세요.',
  배송지: '등록한 배송지가 없습니다. 계정·설정에서 배송지를 먼저 등록하세요.',
  쿠폰: '선택할 보유 쿠폰이 없습니다. 사용 안 함으로 구매하거나 쿠폰 발급 결과를 확인하세요.',
};
const moneyLabel = (value: Json | undefined) => typeof value === 'number' ? `${value.toLocaleString('ko-KR')}원`
  : typeof value === 'string' && /^\d+$/.test(value) ? `${BigInt(value).toLocaleString('ko-KR')}원` : '';

export function referenceSource(name: string, operation: Operation, role: string): { title: string; operation: Operation } | undefined {
  const seller = role === 'SELLER';
  const sources: Record<string, [string, string]> = {
    productId: ['상품', seller ? '/api/sellers/me/products' : '/api/products'],
    eventId: ['이벤트', seller ? '/api/sellers/me/events' : '/api/events'],
    addressId: ['배송지', '/api/buyers/me/addresses'], buyerCouponId: ['쿠폰', '/api/buyers/me/coupons'],
    orderId: ['주문', seller ? '/api/sellers/me/orders' : '/api/orders'],
    paymentId: ['결제', '/api/payments'], templateId: ['쿠폰 캠페인', '/api/sellers/me/coupon-templates'],
    requestId: ['반품 요청', seller ? '/api/sellers/me/refund-requests' : '/api/refund-requests'],
    refundId: ['환불', '/api/refunds'],
    payoutId: ['지급', '/api/sellers/me/payouts'], runId: ['월간 회차', '/internal/coupon-issue-runs'],
    notificationId: ['알림', '/api/notifications'],
    jobId: ['처리 작업', operation.path.includes('payout') ? '/internal/payout-jobs' : '/internal/payment-jobs'],
    quarantineId: ['격리 내역', '/internal/settlement-quarantines'],
    deliveryId: ['알림 전달', '/internal/notification-deliveries'],
  };
  if (name === 'quarantineId') sources[name] = ['격리 내역', operation.owner === 'payment' ? '/internal/payment-quarantines' : '/internal/settlement-quarantines'];
  if (name === 'eventId' && operation.path.startsWith('/internal/')) return;
  if (name === 'deliveryId' && operation.path.includes('quarantine')) return;
  if (name === 'runId' && operation.path.includes('reconciliations')) return;
  if (name === 'runId' && operation.path.includes('inbox-rejudgements')) sources[name] = ['재판정 회차', '/internal/settlement-inbox-rejudgements'];
  else if (name === 'runId' && !operation.path.includes('coupon-issue-runs')) return;
  if (name === 'jobId' && !['payment', 'settlement'].includes(operation.owner)) return;
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

export function ReferencePicker({ source, token, choose, onUnauthorized, enabled, value }: {
  source: { title: string; operation: Operation }; token?: string;
  choose: (row: Record<string, Json>, source: Operation) => void;
  onUnauthorized?: (token: string) => void;
  enabled: boolean; value?: string;
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
        query: { page: String(nextPage), size: '20' }, idempotencyKey: '' }, token, controller.signal, onUnauthorized);
      if (controller.signal.aborted) return;
      if (result.status >= 300) throw new Error(result.status === 401 || result.status === 403
        ? source.operation.actor === 'OPERATOR' ? '제어 서버의 해당 서비스 운영자 자격 설정을 확인하세요.' : '이 역할로 먼저 로그인하세요.'
        : `목록을 조회하지 못했습니다 (HTTP ${result.status}). 서버 연결과 실행 기록을 확인하세요.`);
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
    <button type="button" className="secondary" disabled={loading || !enabled} onClick={() => load(0)}>
      {loading ? '목록 확인 중…' : `${source.title} 목록 불러오기`}</button>
    {error && <small role="alert" className="error">{error}</small>}
    {rows && <><Select aria-label={`${source.title} 선택`} value={value ?? selected} disabled={loading} onChange={event => {
      setSelected(event.target.value);
      const row = rows.find(row => row.id === event.target.value); if (row) choose(row, source.operation);
    }}><option value="">{rows.length ? '실제 내역을 선택하세요' : '조회된 내역이 없습니다'}</option>
      {value && !rows.some(row => row.id === value) && <option value={value}>이전 결과에서 연결한 {source.title}</option>}
      {rows.map(row => { const id = String(row.id); const amount = row.totalAmount ?? row.grossAmount ?? row.externalAmount ?? row.amount ?? row.discountAmount;
        const title = row.name ?? row.productName ?? row.targetMonth ?? row.orderNumber ?? row.recipient ?? source.title;
        const amountLabel = source.title === '환불' ? `외부 ${moneyLabel(row.externalAmount) || '—'} · 마일리지 ${moneyLabel(row.mileageAmount) || '—'}` : moneyLabel(amount);
        const date = row.validUntil ?? row.endsAt ?? row.acceptedAt ?? row.createdAt;
        const description = [String(title), row.kind ? codeLabel(String(row.kind)) : '', row.status ? codeLabel(String(row.status)) : '', amountLabel,
          typeof date === 'string' ? `${row.validUntil ? '사용 기한' : row.endsAt ? '종료' : row.acceptedAt ? '접수' : '생성'} ${new Date(date).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}` : '', `#${id.length === 36 ? id.slice(0, 8) : id}`].filter(Boolean).join(' · ');
        return <option key={id} value={id} title={id}>{description}</option>; })}</Select>
      {!rows.length && <small>{emptyGuidance[source.title] ?? '현재 계정·조건에 맞는 내역이 없습니다. 조회 조건과 시연 준비 조건을 확인하세요.'}</small>}
      {pages > 1 && <div className="reference-pages"><button type="button" className="text-button" disabled={loading || page === 0} onClick={() => load(page - 1)}>이전</button>
        <small>{page + 1} / {pages} 페이지</small><button type="button" className="text-button" disabled={loading || page + 1 >= pages} onClick={() => load(page + 1)}>다음</button></div>}
    </>}
  </div>;
}
