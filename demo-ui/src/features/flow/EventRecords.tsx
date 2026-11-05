import { useEffect, useRef, useState } from 'react';
import type { Execution } from '../../shared/api/client';
import type { DemoInfo } from '../experiments/Experiments';
import { eventContracts, producerApplication, consumerApplication } from './flowModel';
import { observeEvents, owners, requestReferences, resultTarget, uuid, type EventRecord, type Observation, type ResultTarget } from './eventRecords';

const labels: Record<string, string> = { PENDING: '발행 대기', RUNNING: '발행 시도 중', RETRY: '재시도 대기', PUBLISHED: '발행 확인',
  RECEIVED: '수신 저장', WAITING: '선행 상태 대기', APPLIED: '이벤트 반영', REVIEW_REQUIRED: '확인 필요' };
const kst = (value: string) => new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul', hour12: false });
const resultLabels: Record<ResultTarget, string> = { order: '주문 결과 조회로 이동', payment: '구매자 결제 조회로 이동',
  refund: '구매자 환불 조회로 이동', 'settlement-item': '판매자 정산 목록으로 이동' };
function Position({ row }: { row: EventRecord }) {
  return <><code>{row.topic}</code><span>파티션 {row.partition ?? '미확인'} · offset {row.offset ?? '미확인'}</span></>;
}
function State({ row }: { row: EventRecord }) {
  return <><strong>{labels[row.status] ?? row.status} <code>{row.status}</code></strong><span>{kst(row.updatedAt)} KST · 시도 {row.attempts}회</span>
    {row.lastErrorCode && <code>{row.lastErrorCode}</code>}{row.resultRef && <span>반영 결과 ID <code>{row.resultRef}</code></span>}</>;
}

export function EventRecords({ record, info, onSelectEvent, onInspectResult }: { record?: Execution; info?: DemoInfo; onSelectEvent: (name: string) => void;
  onInspectResult?: (target: ResultTarget, id: string) => void }) {
  const seed = requestReferences(record).join(',');
  const [input, setInput] = useState(seed); const [observation, setObservation] = useState<Observation>();
  const [selected, select] = useState(''); const [loading, setLoading] = useState(false); const [error, setError] = useState('');
  const abort = useRef<AbortController | null>(null); const generation = useRef(0);
  const configured = info?.operatorServices.join(',');
  useEffect(() => {
    generation.current++; abort.current?.abort(); setLoading(false); setInput(seed); setObservation(undefined); select(''); setError('');
    return () => { generation.current++; abort.current?.abort(); };
  }, [record?.id, seed]);
  useEffect(() => {
    generation.current++; abort.current?.abort(); setLoading(false); setObservation(undefined); select(''); setError('');
  }, [configured]);
  async function load() {
    const ids = [...new Set(input.split(/[\s,]+/).filter(Boolean).map(id => id.toLowerCase()))];
    if (!ids.length || ids.length > 20 || ids.some(id => !uuid.test(id))) { setError('거래·이벤트 UUID를 쉼표로 구분해 1~20개 입력하세요.'); return; }
    if (!info) { setError('제어 서버의 운영자 설정을 아직 확인할 수 없습니다.'); return; }
    abort.current?.abort(); const controller = new AbortController(); abort.current = controller;
    const current = ++generation.current; setLoading(true); setError(''); setObservation(undefined); select('');
    try {
      const next = await observeEvents(ids, info.operatorServices, controller.signal);
      if (controller.signal.aborted || current !== generation.current) return;
      setObservation(next);
      const first = next.records[0]; if (first) { select(first.eventId); onSelectEvent(first.eventType); }
    } catch (e) { if (!controller.signal.aborted && current === generation.current) setError(e instanceof Error ? e.message : '관측하지 못했습니다.'); }
    finally { if (current === generation.current) setLoading(false); }
  }
  const eventIds = [...new Set(observation?.records.map(item => item.eventId) ?? [])];
  const rows = observation?.records.filter(item => item.eventId === selected) ?? [];
  const outboxes = rows.filter(item => item.kind === 'OUTBOX'); const inboxes = rows.filter(item => item.kind === 'INBOX');
  const event = eventContracts.find(item => item.name === rows[0]?.eventType);
  const consumers = [...new Set([...(event?.consumers ?? []), ...inboxes.map(item => item.consumer?.replace(/\.v1$/, '')).filter((name): name is string => !!name)])];
  return <section className="event-observation" aria-label="실제 이벤트 기록">
    <div className="flow-heading"><div><h3>실제 이벤트 기록</h3><p>선택한 HTTP 요청의 거래 ID로 각 서비스의 Outbox·Inbox를 읽습니다.</p></div><span className="badge neutral">수동 조회 · 읽기 전용</span></div>
    <div className="event-query"><label>연결할 거래·이벤트 ID<input aria-label="연결할 거래·이벤트 ID" value={input} disabled={loading}
      placeholder="주문·결제·환불·지급·이벤트 UUID" onChange={e => { setInput(e.target.value); setObservation(undefined); setError(''); }} /></label>
      <button className="primary" aria-label="실제 이벤트 기록 조회" disabled={loading || !info} onClick={() => { void load(); }}>{loading ? '기록 조회 중' : '실제 기록 조회'}</button>
      {loading && <button onClick={() => { generation.current++; abort.current?.abort(); setLoading(false); setError('조회 대기를 중단했습니다. 업무 요청은 변경하지 않았습니다.'); }}>조회 중단</button>}</div>
    {!info && <p className="caption">제어 서버 설정 확인 전입니다. 서비스별 조회 가능 여부는 아직 알 수 없습니다.</p>}
    {!seed && !observation && <p className="caption">거래 단건의 응답·실행 기록을 선택하면 ID를 채웁니다. 목록·계정·상품 ID로 전체 거래를 조회하지 않습니다.</p>}
    {error && <p role="alert" className="notice error">{error}</p>}
    {observation && <>
      <div className="event-sources">{owners.map(owner => { const source = observation.sources[owner]; return <div key={owner}>
        <strong>{owner} {source.status === 'error' ? '조회 실패' : source.status === 'unconfigured' ? '설정 없음' : '조회 완료'}</strong>
        <span>{source.detail}{source.observedAt && ` · ${kst(source.observedAt)} KST`}</span>{source.truncated && <span>50개 상한 · 일부 기록 생략</span>}</div>; })}</div>
      {(observation.limited || owners.some(owner => observation.sources[owner].truncated)) && <p role="status" className="notice">연결 조회 상한에 도달해 추가 기록·참조를 모두 조회하지 못했습니다. 확인할 거래·eventId를 좁혀 다시 조회하세요.</p>}
      {!eventIds.length ? <p className="empty-event-records">{owners.some(owner => observation.sources[owner].status === 'ok')
        ? '조회된 서비스 범위에서 이벤트 기록을 찾지 못했습니다. 아직 생성·발행 전이거나 연결되지 않은 ID일 수 있습니다.'
        : '조회에 성공한 서비스가 없어 이벤트 기록을 확인하지 못했습니다.'} 조회 실패·설정 없음은 미수신 판정이 아닙니다.</p>
        : <div className="event-record-browser"><nav aria-label="관측한 이벤트">{eventIds.map(id => { const item = observation.records.find(row => row.eventId === id)!;
          return <button key={id} aria-pressed={selected === id} onClick={() => { select(id); onSelectEvent(item.eventType); }}><strong>{item.eventType}</strong><code>{id}</code></button>; })}</nav>
          <div className="event-record-detail"><h4>{rows[0]?.eventType}</h4><p>eventId <code>{selected}</code></p>
            <ol className="event-record-path"><li><h4>Outbox · 발행 앱의 DB</h4>{outboxes.length ? outboxes.map(row => <div className="event-record" key={`${row.owner}:${row.id}`}>
              <b>{producerApplication(row.owner)} · {row.owner} DB</b><State row={row} /></div>) : <p>발행 측 Outbox 기록은 이 관측에서 확인하지 못했습니다.</p>}</li>
              <li><h4>Kafka · 발행 위치</h4>{outboxes.map(row => <div className="event-record" key={`${row.owner}:${row.id}`}><Position row={row} /></div>)}
                <p className="caption">위치는 DB에 저장된 발행 결과입니다. Kafka를 직접 조회한 값은 아닙니다. 재발행하면 최초 수신 위치와 달라질 수 있습니다.</p></li>
              <li><h4>Inbox · 소비 앱의 DB</h4>{consumers.map(consumer => { const received = inboxes.filter(item => item.consumer === `${consumer}.v1`);
                const owner = consumer === 'commerce-notifications' ? 'commerce' : consumer;
                const source = observation.sources[owner as keyof Observation['sources']];
                return <div className="event-consumer" key={consumer}><b>{consumerApplication(consumer)} · <code>{consumer}.v1</code></b>
                  {received.length ? received.map(row => { const target = resultTarget(row); return <div className="event-record" key={row.id}><State row={row} /><Position row={row} />
                    {target && onInspectResult && <button className="secondary" onClick={() => onInspectResult(target, row.resultRef!)}>{resultLabels[target]}</button>}</div>; })
                    : <p>{source?.status === 'ok' && !source.truncated && !observation.limited ? '이 관측에서 수신 기록 없음' : '조회 실패·설정 또는 범위 상한 때문에 수신 여부 미확인'}</p>}</div>;
              })}</li></ol>
            <p className="caption">APPLIED는 수신 이벤트의 반영 상태입니다. 결제·환불·지급의 최종 성공은 해당 거래 조회로 확인하세요. 서비스별 관측 시각이 달라 하나의 원자적 실행 추적은 아닙니다.</p>
          </div></div>}
    </>}
  </section>;
}
