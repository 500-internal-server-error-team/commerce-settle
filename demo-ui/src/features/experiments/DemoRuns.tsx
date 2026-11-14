import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';

interface Run {
  id: string; sellerId: string; productId: string; eventId: string | null;
  deliverySeconds: number; refundSeconds: number; frozenAt: string | null;
  orderIds: string[]; payableOrderIds: string[]; refundedOrderIds: string[];
  pendingCount: number; excludedCount: number;
}
interface Settlement {
  id: string; cutoffAt: string; batchStatus: string; itemCount: number; refundedCount: number;
  batches: { id: string; netAmount: number; status: string; payoutStatus: string | null }[];
}
type Options = { sellerId: string; productId: string; eventId: string | null; deliverySeconds: number; refundSeconds: number };
const uuid = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/i;

async function request<T>(operationId: string, runId?: string, body?: unknown, idempotencyKey?: string, query: Record<string, string> = {}): Promise<T> {
  const response = await fetch('/demo/requests', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ operationId, pathParameters: runId ? { runId } : {}, query, body: body ?? null, idempotencyKey }) });
  const result = await response.json();
  const status = response.ok ? result.upstreamStatus : response.status;
  const data = response.ok ? result.data : result;
  if (status < 200 || status >= 300) throw Object.assign(new Error(String(data?.detail ?? '시연 요청 결과를 확인하지 못했습니다. 같은 요청으로 다시 확인하세요.')), { status });
  return data as T;
}

export function DemoRuns({ services }: { services: string[] }) {
  const configured = services.includes('commerce') && services.includes('settlement');
  const client = useQueryClient();
  const [options, setOptions] = useState<Options>({ sellerId: '', productId: '', eventId: null, deliverySeconds: 5, refundSeconds: 10 });
  const [progress, setProgress] = useState<{ processed: number; waiting: number } | null>(null);
  const [runId, setRunId] = useState(''); const [lookup, setLookup] = useState('');
  const [pendingCreate, setPendingCreate] = useState<Options | null>(null);
  const keys = useRef<Record<string, string>>({});
  const capabilities = useQuery({ queryKey: ['demo-run-capabilities'], enabled: configured, retry: false,
    queryFn: async () => {
      const [commerce, settlement] = await Promise.all([
        request<{ enabled: boolean }>('get_internal_demo_capabilities'),
        request<{ enabled: boolean }>('get_internal_demo_settlement_capabilities'),
      ]); return commerce.enabled && settlement.enabled;
    } });
  const enabled = configured && capabilities.data === true;
  const run = useQuery({ queryKey: ['demo-run', runId], enabled: enabled && uuid.test(runId), retry: false,
    queryFn: () => request<Run>('get_internal_demo_run', runId), refetchInterval: 2000 });
  const settlement = useQuery({ queryKey: ['demo-settlement', runId], enabled: enabled && uuid.test(runId), retry: false,
    queryFn: async () => {
      try { return await request<Settlement>('get_internal_demo_settlement_run', runId); }
      catch (e) { if ((e as { status?: number }).status === 404) return null; throw e; }
    }, refetchInterval: 2000 });
  const action = useMutation({ mutationFn: async (step: 'create' | 'freeze' | 'shipments' | 'processing' | 'settlement') => {
    const key = keys.current[step] ??= crypto.randomUUID();
    if (step === 'create') {
      const snapshot = pendingCreate ?? { ...options }; setPendingCreate(snapshot);
      const created = await request<Run>('post_internal_demo_runs', undefined, snapshot, key);
      setRunId(created.id); setLookup(created.id); setPendingCreate(null);
    } else if (step === 'settlement') {
      await request<Settlement>('post_internal_demo_settlement_runs', undefined, { runId }, key);
    } else if (step === 'freeze') {
      await request<Run>('post_internal_demo_run_freeze',runId,{},key);
    } else {
      let after: string | null = null;
      const totals={processed:0,waiting:0};setProgress(totals);
      do {
        const page: { nextCursor: string | null; processedCount: number; waitingCount: number } = await request(`post_internal_demo_run_${step}`,runId,{},key,after ? { after } : {});
        after=page.nextCursor;totals.processed+=page.processedCount;totals.waiting+=page.waitingCount;setProgress({...totals});
      } while(after);
    }
    delete keys.current[step];
    await Promise.all([client.invalidateQueries({ queryKey: ['demo-run', runId] }), client.invalidateQueries({ queryKey: ['demo-settlement', runId] })]);
  } });
  const locked = !!runId || !!pendingCreate;
  const error = action.error ?? run.error ?? settlement.error ?? capabilities.error;
  return <section aria-label="회차별 시연">
    <h3>회차별 시연</h3>
    <p>새 주문에 적용할 배송·환불 기간을 정합니다. 실제 서버 시각은 유지하며, 회차를 바꿔도 이미 만든 주문의 기한은 유지합니다.</p>
    {!enabled && <p className="caption">{configured ? capabilities.isPending ? '서버의 시연 모드를 확인하는 중입니다.' : '전용 환경에서 Commerce·Settlement의 시연 모드를 켜야 합니다.' : '서버에 Commerce·Settlement 운영 자격을 먼저 설정하세요.'}</p>}
    <form aria-label="시연 회차 생성" onSubmit={e => { e.preventDefault(); action.mutate('create'); }}>
      <fieldset disabled={!enabled || locked || action.isPending} className="fields">
        <label>판매자 ID<input required value={options.sellerId} pattern={uuid.source} onChange={e => setOptions({ ...options, sellerId: e.target.value })} /></label>
        <label>상품 ID<input required value={options.productId} pattern={uuid.source} onChange={e => setOptions({ ...options, productId: e.target.value })} /></label>
        <label>이벤트 ID (선택)<input value={options.eventId ?? ''} pattern={uuid.source} onChange={e => setOptions({ ...options, eventId: e.target.value || null })} /></label>
        <label>배송 대기 (초)<input required type="number" min="1" max="3600" step="1" value={options.deliverySeconds} onChange={e => setOptions({ ...options, deliverySeconds: Number(e.target.value) })} /></label>
        <label>환불 기간 (초)<input required type="number" min="1" max="3600" step="1" value={options.refundSeconds} onChange={e => setOptions({ ...options, refundSeconds: Number(e.target.value) })} /></label>
      </fieldset>
      <button type="submit" className="primary" disabled={!enabled || !!runId || action.isPending}>{pendingCreate ? '같은 회차 생성 요청 다시 확인' : '시연 회차 만들기'}</button>
      {locked && <button type="button" disabled={action.isPending} onClick={() => { setRunId(''); setLookup(''); setPendingCreate(null); setProgress(null); keys.current = {}; action.reset(); }}>새 회차 준비</button>}
    </form>
    <p className="caption">주문 생성의 시연 회차 ID에 아래 ID를 넣으세요. 매진·결제·환불 처리가 끝난 뒤 대상을 고정하고 발송합니다.</p>
    <form aria-label="기존 시연 회차 조회" onSubmit={e => { e.preventDefault(); setRunId(lookup); action.reset(); }}>
      <label>기존 회차 ID<input required pattern={uuid.source} value={lookup} onChange={e => setLookup(e.target.value)} /></label>
      <button disabled={!enabled || action.isPending}>회차 불러오기</button>
    </form>
    {run.data && <div className="run" aria-live="polite">
      <strong>배송 {run.data.deliverySeconds}초 · 환불 {run.data.refundSeconds}초</strong><code>{run.data.id}</code>
      <p>{run.data.frozenAt ? '대상 고정됨' : '새 주문 접수 가능'} · 구매확정 대기 {run.data.pendingCount}건 · 정산 준비 {run.data.payableOrderIds.length}건 · 환불 {run.data.refundedOrderIds.length}건 · 제외 {run.data.excludedCount}건</p>
      <div className="monitor-links">
        <button disabled={action.isPending || !!run.data.frozenAt} onClick={() => action.mutate('freeze')}>회차 대상 고정</button>
        <button disabled={action.isPending || !run.data.frozenAt} onClick={() => action.mutate('shipments')}>발송 시작</button>
        <button disabled={action.isPending || !run.data.frozenAt} onClick={() => action.mutate('processing')}>도래한 기한 처리</button>
        <button disabled={action.isPending || !run.data.frozenAt || run.data.pendingCount > 0 || run.data.payableOrderIds.length === 0} onClick={() => action.mutate('settlement')}>정산 실행·재시작</button>
      </div>
      <p className="caption">배송 예정·환불 마감 전에는 다음 상태로 넘어가지 않습니다. 한 번에 50건씩 이어서 처리합니다. 미도래 항목은 기다린 뒤 다시 처리하세요.</p>
    </div>}
    {settlement.data && <div className="run"><strong>정산 배치: {settlement.data.batchStatus}</strong>
      <p>마감 {new Date(settlement.data.cutoffAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })} · 대상 {settlement.data.itemCount}건 · 환불 제외 {settlement.data.refundedCount}건</p>
      {settlement.data.batches.map(b => <p key={b.id}>정산액 {b.netAmount.toLocaleString('ko-KR')}원 · 모의 지급 {b.payoutStatus ?? '대기'}</p>)}
    </div>}
    {progress && <p className="caption" aria-live="polite">처리 확인 {progress.processed}건 · 기한 대기 {progress.waiting}건</p>}
    {error && <p role="alert" className="error">{error.message}</p>}
    {action.isPending && <p role="status">요청 결과를 확인하는 중입니다. 같은 회차와 요청을 유지합니다.</p>}
  </section>;
}
