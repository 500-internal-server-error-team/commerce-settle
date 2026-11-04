import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { actions, groups, requirements, scenarios } from '../scenarios/catalog';
import { operations, redact, type Json, type Operation } from '../shared/api/contract';
import { execute, journal, type Execution, type RequestValues } from '../shared/api/client';
import { OperationForm } from '../features/requests/OperationForm';
import { responseContext } from '../features/requests/responseContext';
import { useEventGauge } from '../features/requests/useEventGauge';
import { ResultView } from '../features/results/ResultView';
import { Experiments, type DemoInfo } from '../features/experiments/Experiments';
import { Select, ArrowIcon } from '../shared/ui/Select';

type Role = 'BUYER' | 'SELLER' | 'OPERATOR';
interface Session { token: string; accountId: string; name: string; epoch: number }
const EMPTY_CONTEXT: Record<string, Json> = {};
const roleLabels = { BUYER: '구매자', SELLER: '판매자', OPERATOR: '발표자' };
const kst = (date: string) => new Date(date).toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul', hour12: false });

export function App() {
  const [role, setRole] = useState<Role>('BUYER'); const [group, setGroup] = useState('buy');
  const [caseId, setCase] = useState('buy.normal'); const [search, setSearch] = useState('');
  const [operationId, setOperation] = useState('get_api_products');
  const [sessions, setSessions] = useState<Partial<Record<Role, Session>>>({});
  const sessionsRef = useRef(sessions); sessionsRef.current = sessions;
  const [context, setContext] = useState<Partial<Record<Role, Record<string, Json>>>>({});
  const [events, setEvents] = useState<Partial<Record<Role, Execution[]>>>({});
  const [selected, setSelected] = useState<string>(); const [pending, setPending] = useState(false);
  const [notice, setNotice] = useState(''); const [panel, setPanel] = useState<'requests' | 'experiments' | null>('requests');
  const abort = useRef<AbortController | null>(null); const client = useQueryClient();
  const info = useQuery({ queryKey: ['demo-info'], queryFn: async () => {
    const r = await fetch('/demo/info'); if (!r.ok) throw new Error('로컬 데모 제어 서버를 시작하세요.'); return r.json() as Promise<DemoInfo>;
  }, retry: false });
  const scenario = scenarios.find(s => s.id === caseId);
  const visibleActions = actions(group);
  const operation = operations.find(o => o.id === operationId) ?? visibleActions[0];
  const history = events[role] ?? []; const record = history.find(e => e.id === selected) ?? history[0];
  const session = sessions[role];
  const gauge = useEventGauge(operation, record, [role, session?.accountId ?? 'public', session?.epoch ?? 0], session?.token);
  const current = gauge.current;
  const allowed = !!operation && (operation.actor === 'PUBLIC' || operation.actor.includes(role)
    && (role === 'OPERATOR' ? !!info.data?.operatorServices.includes(operation.owner) : !!session));

  useEffect(() => () => abort.current?.abort(), []);
  function navigate(next: string, id?: string) {
    setGroup(next); const first = scenarios.find(s => s.group === next);
    setCase(id ?? first?.id ?? ''); setOperation(actions(next)[0]?.id ?? ''); setNotice('');
  }
  function switchRole(next: Role) {
    abort.current?.abort(); setPending(false); setRole(next); setSelected(undefined); setNotice('');
    navigate(next === 'SELLER' ? 'sell' : next === 'OPERATOR' ? 'monthly' : 'buy');
  }
  async function run(values: RequestValues) {
    if (!operation || pending) return;
    const actor = role; const before = sessions[actor]; const op: Operation = operation;
    const controller = new AbortController(); abort.current = controller; setPending(true); setNotice('');
    try {
      const call = () => execute(op, values, before?.token, controller.signal);
      const result = op.method === 'GET' ? await client.fetchQuery({
        queryKey: ['actor', actor, before?.accountId ?? 'public', before?.epoch ?? 0, op.id, values], queryFn: call, staleTime: 0,
      }) : await call();
      if (sessionsRef.current[actor]?.epoch !== before?.epoch) return;
      const event = journal(op, values, actor, result);
      setEvents(old => ({ ...old, [actor]: [event, ...(old[actor] ?? [])].slice(0, 60) })); setSelected(event.id);
      if (result.status >= 200 && result.status < 300 && result.data && typeof result.data === 'object') {
        const data = result.data as Record<string, Json>;
        if (op.path.endsWith('-sessions') && typeof data.token === 'string') {
          const account = data.account as Record<string, Json>;
          if (!account || account.actorKind !== actor) {
            setNotice('응답 계정 종류가 현재 역할과 다릅니다. 해당 역할에서 다시 로그인하세요.'); return;
          }
          client.removeQueries({ queryKey: ['actor', actor] });
          setSessions(old => ({ ...old, [actor]: { token: data.token as string, accountId: String(account.id), name: String(account.displayName), epoch: Date.now() } }));
          setEvents(old => ({ ...old, [actor]: [] })); setContext(old => ({ ...old, [actor]: {} })); setNotice('로그인했습니다. 이 역할의 계정과 세션을 사용합니다.');
        }
      }
      if (result.status >= 200 && result.status < 300) {
        if (op.path === '/api/sessions/current') clearActor(actor);
        else if (op.method !== 'GET') client.invalidateQueries({ queryKey: ['actor', actor] });
      }
      if (result.status === 401) setNotice('인증이 거절됐습니다. 현재 거래 ID를 보존하고 해당 역할로 다시 로그인한 뒤 재조회하세요.');
      else if (result.status === 409) setNotice('상태·버전·조건 충돌입니다. 최신 결과를 조회하고 변경된 값을 확인하세요.');
      else if (result.status === 202) setNotice('요청이 접수됐습니다. 최종 완료는 해당 거래 조회로 확인하세요.');
    } catch (error) {
      if (!(error instanceof DOMException && error.name === 'AbortError')) setNotice('응답을 확인하지 못했습니다. 거래 실패로 단정하지 말고 원거래를 조회하세요.');
    } finally { if (abort.current === controller) setPending(false); }
  }
  function clearActor(actor: Role) {
    abort.current?.abort(); client.cancelQueries({ queryKey: ['actor', actor] }); client.removeQueries({ queryKey: ['actor', actor] });
    setSessions(old => ({ ...old, [actor]: undefined })); setEvents(old => ({ ...old, [actor]: [] })); setContext(old => ({ ...old, [actor]: {} }));
  }
  function selectRow(row: Record<string, Json>) {
    const next = responseContext(row, current?.path ?? '', (current?.request as RequestValues | undefined)?.pathParameters);
    setContext(old => ({ ...old, [role]: { ...old[role], ...next } })); setNotice('선택한 응답의 ID·버전을 입력에 연결했습니다. 실행 전 값을 확인하세요.');
  }
  return <div className={`shell ${panel ? 'with-panel' : ''}`}>
    <header className="topbar"><a className="brand" href="#" onClick={e => { e.preventDefault(); navigate('buy'); }}>
      <svg className="brand-mark" width="30" height="32" viewBox="0 0 30 32" aria-hidden="true" focusable="false">
        <rect width="30" height="32" rx="7" fill="currentColor" />
        <path d="M17 11c-1-1-2-1.5-3.5-1.5a6.5 6.5 0 0 0 0 13c1.5 0 2.5-.5 3.5-1.5" fill="none" stroke="white" strokeWidth="3" strokeLinecap="round" />
        <circle cx="22" cy="21" r="1.5" fill="white" />
      </svg><span>commerce<span className="brand-light">settle</span></span></a>
      <div className="role-tabs" aria-label="사용자 역할">{(['BUYER', 'SELLER', 'OPERATOR'] as Role[]).map(r => <button key={r} className={role === r ? 'active' : ''} aria-pressed={role === r} onClick={() => switchRole(r)}>{roleLabels[r]}</button>)}</div>
      <div className="top-actions"><span className="account-name">{role === 'OPERATOR' ? '로컬 제어' : session?.name ?? '로그인 전'}</span>
        {session && <button className="text-button" onClick={() => clearActor(role)}>로컬 세션 지우기</button>}
        <button className="secondary" onClick={() => setPanel(panel === 'experiments' ? null : 'experiments')}>실험·모니터링 <ArrowIcon /></button></div></header>
    <aside className="sidebar"><div className="sidebar-heading">시연 흐름 <span>{scenarios.length}</span></div>
      <input className="search" aria-label="흐름 검색" placeholder="흐름 검색…" value={search} onChange={e => setSearch(e.target.value)} />
      <nav>{groups.map(([id, title, number]) => <div key={id} className="nav-group"><button className={group === id ? 'nav-item active' : 'nav-item'} onClick={() => navigate(id)}><span>{number}</span>{title}<small>{scenarios.filter(s => s.group === id).length}</small></button>
        {(group === id || search) && scenarios.filter(s => s.group === id && (!search || s.label.includes(search))).map(s => <button key={s.id} className={`case-link ${caseId === s.id ? 'active' : ''}`} onClick={() => navigate(id, s.id)}>{s.label}</button>)}</div>)}
        <button className={`nav-item ${group === 'monthly' ? 'active' : ''}`} onClick={() => navigate('monthly')}><span>08</span>월 랭킹·자동 발급</button></nav>
      <div className="sidebar-foot"><span className="live-dot" /> 실제 API 연결<span>화면 연결 ≠ 실행 검증</span></div></aside>
    <main id="main"><div className="breadcrumb">{roleLabels[role]} <span>/</span> {scenario?.groupTitle ?? '월간 혜택'}</div>
      <div className="page-heading"><div><h1>{group === 'monthly' ? '월 랭킹과 쿠폰 발급' : scenario?.label}</h1><p>{group === 'monthly' ? '마감 시점에 고정한 선정 순위와 실제 발급 결과를 확인합니다.' : '입력부터 결과까지, 실제 거래의 흐름을 확인하세요.'}</p></div>
        <button className="secondary" onClick={() => setPanel(panel === 'requests' ? null : 'requests')}>요청·응답 {panel === 'requests' ? '접기' : '보기'}</button></div>
      {info.error && <div role="alert" className="notice error">{info.error.message} · UI는 5173, 제어 서버는 4174 포트를 사용합니다.</div>}
      {notice && <div role="status" className="notice">{notice}</div>}
      {scenario && <div className="steps" aria-label="설계된 화면 흐름">{scenario.screens.map((screen, index) => <div key={index}><span>{String(index + 1).padStart(2, '0')}</span><strong>{screen[1]}</strong><small>{screen[0]}</small></div>)}</div>}
      <details className="prerequisites"><summary>시연 준비 조건과 정책</summary><p>{scenario?.note}</p>{requirements(caseId).map(note => <p key={note}>{note}</p>)}
        {group === 'monthly' && <p>전월 구매확정·미환불 거래의 외부 결제액으로 상위 100위와 동점자를 선정합니다. 매월 1일 00:00 마감·00:10 자동 발급. 수동 발급·업무 시간 이동 기능은 없습니다.</p>}</details>
      <section className="work-card"><div className="card-heading"><div><span className="overline">실제 작업</span><h2>{operation?.title ?? '작업 선택'}</h2></div><span className="badge neutral">{operation?.actor}</span></div>
        <label className="operation-select">할 작업<Select value={operationId} onChange={e => setOperation(e.target.value)}>{visibleActions.map(op => <option key={op.id} value={op.id}>{op.method} · {op.title}</option>)}</Select></label>
        <div className="quick-actions">{visibleActions.filter(op => op.method === 'GET').slice(0, 5).map(op => <button key={op.id} className={op.id === operationId ? 'chip selected' : 'chip'} onClick={() => setOperation(op.id)}>{op.title}</button>)}</div>
        {operation && <OperationForm key={`${role}:${operation.id}:${session?.epoch ?? 0}`} operation={operation} context={context[role] ?? EMPTY_CONTEXT} pending={pending} allowed={allowed} run={run} role={role} token={session?.token} />}
        <button className="text-button login-link" onClick={() => { setGroup('account'); setCase('account.login'); setOperation(role === 'SELLER' ? 'post_api_seller_sessions' : 'post_api_buyer_sessions'); }}>역할별 로그인·가입 화면 열기</button>
      </section>
      <section className="work-card result-section"><div className="card-heading"><div><span className="overline">서버에서 확인한 결과</span><h2>{current?.operation ?? '아직 실행한 작업이 없습니다'}</h2></div>{current && <span className={`badge ${current.status < 300 ? 'success' : 'failure'}`}>HTTP {current.status}</span>}</div>
        {operation?.path.endsWith('/gauge') && current?.path === operation.path && <p className="caption">선택한 이벤트를 1초마다 조회합니다. 백그라운드에서는 중단합니다.</p>}
        {gauge.error && <p role="alert" className="notice error">게이지를 다시 확인하지 못했습니다. 아래는 마지막 관측값이며 현재 판매 가능 여부는 주문 요청에서 다시 검증합니다.</p>}
        {current ? <><p className="caption">{kst(current.at)} KST · {current.elapsedMs}ms · {current.actor} · {current.status === 202 ? '접수 응답 · 최종 완료 아님' : '해당 요청의 응답'}</p>
          <ResultView data={current.response as Json} select={selectRow} /></> : <div className="empty"><span className="empty-icon"><ArrowIcon /></span><strong>실제 응답이 여기에 표시됩니다</strong><p>역할로 로그인한 뒤 조회하거나 작업을 실행하세요.<br />예시 금액이나 가짜 완료 결과는 넣지 않습니다.</p></div>}</section>
    </main>
    {panel && <aside className="inspector"><div className="inspector-tabs"><button className={panel === 'requests' ? 'active' : ''} onClick={() => setPanel('requests')}>요청·응답</button><button className={panel === 'experiments' ? 'active' : ''} onClick={() => setPanel('experiments')}>실험·관측</button><button aria-label="패널 닫기" onClick={() => setPanel(null)}><svg className="icon" width="16" height="16" viewBox="0 0 16 16" aria-hidden="true"><path d="m4 4 8 8m0-8-8 8" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" /></svg></button></div>
      {panel === 'experiments' ? <Experiments info={info.data} /> : <><div className="journal-heading"><h2>실행 기록</h2><span>{history.length}건 · {roleLabels[role]}</span></div>
        <p className="caption inspector-caption">현재 역할의 관측 기록입니다. 내부 이벤트 발행·소비 완료를 추정하지 않습니다.</p>
        <div className="journal">{history.map(e => <button key={e.id} className={`journal-item ${current?.id === e.id ? 'selected' : ''}`} onClick={() => setSelected(e.id)}><span className={e.status < 300 ? 'http-ok' : 'http-error'}>{e.status}</span><span><strong>{e.operation}</strong><small>{kst(e.at)} · {e.elapsedMs}ms</small></span></button>)}</div>
        {current && <div className="payload"><h3>보낸 값</h3><pre>{JSON.stringify(redact(current.request), null, 2)}</pre><h3>받은 결과</h3><pre>{JSON.stringify(redact(current.response), null, 2)}</pre></div>}
      </> }</aside>}
  </div>;
}
