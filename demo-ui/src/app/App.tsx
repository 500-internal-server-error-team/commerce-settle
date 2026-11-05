import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { actions, groups, requirements, scenarios, startingStep, workflow } from '../scenarios/catalog';
import { operations, redact, type Json, type Operation } from '../shared/api/contract';
import { execute, journal, RequestInputError, type Execution, type RequestValues } from '../shared/api/client';
import { OperationForm } from '../features/requests/OperationForm';
import { mergeResponseContext, responseContext } from '../features/requests/responseContext';
import { useEventGauge } from '../features/requests/useEventGauge';
import { ResultView } from '../features/results/ResultView';
import { Experiments, type DemoInfo } from '../features/experiments/Experiments';
import { DataFlow } from '../features/flow/DataFlow';
import { Select, ArrowIcon } from '../shared/ui/Select';
import { actorLabel } from '../shared/ui/labels';

type Role = 'BUYER' | 'SELLER' | 'OPERATOR';
interface Session { token: string; accountId: string; name: string; epoch: number }
interface Recovery { operation: Operation; values: RequestValues; accountId?: string }
const EMPTY_CONTEXT: Record<string, Json> = {};
const roleLabels = { BUYER: '구매자', SELLER: '판매자', OPERATOR: '운영자' };
const kst = (date: string) => new Date(date).toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul', hour12: false });

export function App() {
  const [role, setRole] = useState<Role>('BUYER'); const [group, setGroup] = useState('buy');
  const [caseId, setCase] = useState('buy.normal'); const [search, setSearch] = useState('');
  const [operationId, setOperation] = useState('get_api_products');
  const [stepIndex, setStepIndex] = useState(0);
  const [sessions, setSessions] = useState<Partial<Record<Role, Session>>>({});
  const [signupLogins, setSignupLogins] = useState<Partial<Record<Role, { email: string; password: string }>>>({});
  const sessionsRef = useRef(sessions); sessionsRef.current = sessions;
  const [context, setContext] = useState<Partial<Record<Role, Record<string, Json>>>>({});
  const [events, setEvents] = useState<Partial<Record<Role, Execution[]>>>({});
  const [recoveries, setRecoveries] = useState<Partial<Record<Role, Record<string, Recovery>>>>({});
  const [selected, setSelected] = useState<string>(); const [pending, setPending] = useState(false);
  const [notice, setNotice] = useState(''); const [panel, setPanel] = useState<'requests' | 'experiments' | null>('requests');
  const abort = useRef<AbortController | null>(null); const client = useQueryClient();
  const activeMutation = useRef<{ controller: AbortController; actor: Role; saved: Recovery } | null>(null);
  const info = useQuery({ queryKey: ['demo-info'], queryFn: async () => {
    const r = await fetch('/demo/info'); if (!r.ok) throw new Error('로컬 데모 제어 서버를 시작하세요.'); return r.json() as Promise<DemoInfo>;
  }, retry: false });
  const scenario = scenarios.find(s => s.id === caseId);
  const steps = scenario ? workflow(scenario, role) : [];
  const visibleActions = actions(group, [operationId, ...steps.flatMap(step => step.operation ? [step.operation.id] : [])]);
  const operation = operations.find(o => o.id === operationId) ?? visibleActions[0];
  const history = events[role] ?? []; const record = history.find(e => e.id === selected);
  const session = sessions[role];
  const gauge = useEventGauge(operation, record, [role, session?.accountId ?? 'public', session?.epoch ?? 0], session?.token);
  const current = gauge.current;
  const allowed = !!operation && (operation.actor === 'PUBLIC' || operation.actor.includes(role)
    && (role === 'OPERATOR' ? !!info.data?.operatorServices.includes(operation.owner) : !!session));
  const mutationPending = pending && operation?.method !== 'GET';

  useEffect(() => () => abort.current?.abort(), []);
  function selectTask(id: string, index = -1, preferredRole = role) {
    if (mutationPending) {
      setNotice('현재 변경 요청의 응답을 확인 중입니다. 입력과 재요청 키를 유지하도록 응답을 확인한 뒤 다른 작업을 선택하세요.');
      return false;
    }
    abort.current?.abort(); setPending(false); setSelected(undefined); setNotice('');
    const task = operations.find(op => op.id === id);
    const nextRole = task && task.actor !== 'PUBLIC' && !task.actor.includes(preferredRole)
      ? task.actor.includes('SELLER') ? 'SELLER' : task.actor.includes('BUYER') ? 'BUYER' : 'OPERATOR' : preferredRole;
    setRole(nextRole); setOperation(id); setStepIndex(index);
    return true;
  }
  function navigate(next: string, id?: string, preferredRole = role) {
    const target = scenarios.find(s => id ? s.id === id : s.group === next);
    const index = startingStep(target?.id ?? '');
    const task = target ? workflow(target, preferredRole)[index] : undefined;
    if (selectTask(task?.operation?.id ?? actions(next)[0]?.id ?? '', target ? index : -1,
      task?.screen[0] === '판매자' ? 'SELLER' : task?.screen[0] === '구매자' ? 'BUYER' : preferredRole)) {
      setGroup(next); setCase(target?.id ?? '');
    }
  }
  function switchRole(next: Role) {
    navigate(next === 'SELLER' ? 'sell' : next === 'OPERATOR' ? 'monthly' : 'buy', undefined, next);
  }
  async function run(values: RequestValues) {
    if (!operation || pending) return;
    const actor = role; const before = sessions[actor]; const op: Operation = operation;
    const recovery = recoveries[actor]?.[op.id];
    if (recovery && (recovery.accountId !== before?.accountId || JSON.stringify(recovery.values) !== JSON.stringify(values))) {
      setNotice('미확정 원요청과 입력·키가 다릅니다. 원요청을 다시 채워 재조회·재요청하거나, 결과를 확인한 뒤 보존을 해제하세요.'); return;
    }
    const dispatched = structuredClone(values);
    function preserveRequest() {
      if (op.idempotent && op.method !== 'GET') setRecoveries(old => ({ ...old,
        [actor]: { ...old[actor], [op.id]: { operation: op, values: dispatched, accountId: before?.accountId } } }));
    }
    const controller = new AbortController(); abort.current = controller; setPending(true); setSelected(undefined); setNotice('');
    activeMutation.current = op.method !== 'GET'
      ? { controller, actor, saved: { operation: op, values: dispatched, accountId: before?.accountId } } : null;
    try {
      const call = () => execute(op, dispatched, before?.token, controller.signal);
      const result = op.method === 'GET' ? await client.fetchQuery({
        queryKey: ['actor', actor, before?.accountId ?? 'public', before?.epoch ?? 0, op.id, values], queryFn: call, staleTime: 0,
      }) : await call();
      if (controller.signal.aborted || sessionsRef.current[actor]?.epoch !== before?.epoch) return;
      if (result.status >= 500) preserveRequest();
      else if (result.status >= 200 && result.status < 300) releaseRecovery(actor, op.id);
      const event = journal(op, values, actor, result);
      setEvents(old => ({ ...old, [actor]: [event, ...(old[actor] ?? [])].slice(0, 60) })); setSelected(event.id);
      if (result.status >= 200 && result.status < 300 && result.data && typeof result.data === 'object') {
        const data = result.data as Record<string, Json>;
        const signupRole = op.method === 'POST' && op.path === '/api/buyers' ? 'BUYER'
          : op.method === 'POST' && op.path === '/api/sellers' ? 'SELLER' : undefined;
        const submitted = values.body && typeof values.body === 'object' && !Array.isArray(values.body) ? values.body : undefined;
        if (signupRole && data.actorKind === signupRole && typeof submitted?.email === 'string' && typeof submitted.password === 'string') {
          setSignupLogins(old => ({ ...old, [signupRole]: { email: submitted.email as string, password: submitted.password as string } }));
          setNotice('가입했습니다. 아래의 가입한 계정으로 로그인 버튼을 누르면 같은 입력정보가 연결됩니다.');
        }
        if (op.path.endsWith('-sessions') && typeof data.token === 'string') {
          const account = data.account as Record<string, Json>;
          if (!account || account.actorKind !== actor) {
            setNotice('응답 계정 종류가 현재 역할과 다릅니다. 해당 역할에서 다시 로그인하세요.'); return;
          }
          client.removeQueries({ queryKey: ['actor', actor] });
          setRecoveries(old => ({ ...old, [actor]: Object.fromEntries(Object.entries(old[actor] ?? {})
            .filter(([, saved]) => saved.accountId === String(account.id))) }));
          setSessions(old => ({ ...old, [actor]: { token: data.token as string, accountId: String(account.id), name: String(account.displayName), epoch: Date.now() } }));
          setSignupLogins(old => ({ ...old, [actor]: undefined }));
          setEvents(old => ({ ...old, [actor]: [] })); setContext(old => ({ ...old, [actor]: {} })); setNotice('로그인했습니다. 이 역할의 계정과 세션을 사용합니다.');
        }
      }
      if (result.status >= 200 && result.status < 300) {
        if (op.path === '/api/sessions/current') clearActor(actor);
        else if (op.method !== 'GET') client.invalidateQueries({ queryKey: ['actor', actor] });
      }
      if (result.status === 401 || result.status === 403) setNotice(op.actor === 'OPERATOR'
        ? '운영자 인증이 거절됐습니다. 제어 서버의 해당 서비스 운영자 자격 설정을 확인하세요.'
        : '인증이 거절됐습니다. 현재 거래 ID를 보존하고 해당 역할로 다시 로그인한 뒤 조회하세요.');
      else if (result.status === 409) setNotice('상태·버전·조건 충돌입니다. 최신 결과를 조회하고 변경된 값을 확인하세요.');
      else if (result.status === 202) setNotice('요청이 접수됐습니다. 최종 완료는 해당 거래 조회로 확인하세요.');
    } catch (error) {
      if (controller.signal.aborted || sessionsRef.current[actor]?.epoch !== before?.epoch) return;
      if (error instanceof RequestInputError) setNotice(error.message);
      else if (!(error instanceof DOMException && error.name === 'AbortError')) {
        preserveRequest(); setNotice('응답을 확인하지 못했습니다. 거래 실패로 단정하지 말고 원거래를 조회하세요.');
      }
    } finally {
      if (activeMutation.current?.controller === controller) activeMutation.current = null;
      if (abort.current === controller) setPending(false);
    }
  }
  function stopWaiting() {
    const active = activeMutation.current; if (!active) return;
    if (active.saved.operation.idempotent) setRecoveries(old => ({ ...old,
      [active.actor]: { ...old[active.actor], [active.saved.operation.id]: active.saved } }));
    active.controller.abort(); activeMutation.current = null; setPending(false);
    setNotice(active.saved.operation.idempotent
      ? '응답 대기만 중단했습니다. 이미 접수된 거래가 취소된 것은 아닙니다. 원거래를 조회하고, 같은 작업은 보존한 입력과 키로 확인하세요.'
      : '응답 대기만 중단했습니다. 이미 접수된 처리가 취소된 것은 아닙니다. 다시 실행하기 전에 해당 계정과 처리 상태를 조회하세요.');
  }
  function releaseRecovery(actor: Role, id: string) {
    setRecoveries(old => {
      const next = { ...old[actor] }; delete next[id]; return { ...old, [actor]: next };
    });
  }
  function clearActor(actor: Role) {
    abort.current?.abort(); client.cancelQueries({ queryKey: ['actor', actor] }); client.removeQueries({ queryKey: ['actor', actor] });
    setSessions(old => ({ ...old, [actor]: undefined })); setEvents(old => ({ ...old, [actor]: [] })); setContext(old => ({ ...old, [actor]: {} }));
    setSignupLogins(old => ({ ...old, [actor]: undefined }));
    setRecoveries(old => ({ ...old, [actor]: undefined }));
  }
  function selectRow(row: Record<string, Json>) {
    if (mutationPending) {
      setNotice('현재 변경 요청의 응답을 확인 중입니다. 입력과 재요청 키를 보존하려면 응답을 확인한 뒤 결과를 연결하세요.'); return;
    }
    if (!current || current.status < 200 || current.status >= 300) {
      setNotice('오류 응답은 입력에 연결하지 않습니다. 조회 조건을 확인하고 정상 응답에서 선택하세요.'); return;
    }
    const request = current?.request as RequestValues | undefined;
    const next = responseContext(row, current?.path ?? '', request?.pathParameters, request?.body);
    setContext(old => ({ ...old, [role]: mergeResponseContext(old[role] ?? {}, next) })); setNotice('선택한 응답의 ID·버전을 입력에 연결했습니다. 실행 전 값을 확인하세요.');
  }
  return <div className={`shell ${panel ? 'with-panel' : ''}`}>
    <header className="topbar"><a className="brand" href="#" onClick={e => { e.preventDefault(); navigate('buy'); }}>
      <svg className="brand-mark" width="30" height="32" viewBox="0 0 30 32" aria-hidden="true" focusable="false">
        <rect width="30" height="32" rx="7" fill="currentColor" />
        <path d="M17 11c-1-1-2-1.5-3.5-1.5a6.5 6.5 0 0 0 0 13c1.5 0 2.5-.5 3.5-1.5" fill="none" stroke="white" strokeWidth="3" strokeLinecap="round" />
        <circle cx="22" cy="21" r="1.5" fill="white" />
      </svg><span>commerce<span className="brand-light">settle</span></span></a>
      <div className="account-control"><span>실행 계정</span><div className="role-tabs" aria-label="API 실행 계정">{(['BUYER', 'SELLER'] as Role[]).map(r => <button key={r} className={role === r ? 'active' : ''} aria-pressed={role === r} onClick={() => switchRole(r)}>{roleLabels[r]}</button>)}</div></div>
      <div className="top-actions"><span className="account-name">{role === 'OPERATOR' ? '운영 도구 사용 중' : session?.name ?? '로그인 전'}</span>
        {session && <button className="text-button" disabled={mutationPending} onClick={() => clearActor(role)}>로컬 세션 지우기</button>}
        <button className="secondary tool-button" aria-pressed={role === 'OPERATOR'} onClick={() => switchRole('OPERATOR')}>운영 도구</button>
        <button className="secondary" onClick={() => setPanel(panel === 'experiments' ? null : 'experiments')}>실험·모니터링 <ArrowIcon /></button></div></header>
    <aside className="sidebar"><div className="sidebar-heading">시연 흐름 <span>{scenarios.length}</span></div>
      <input className="search" aria-label="흐름 검색" placeholder="흐름 검색…" value={search} onChange={e => setSearch(e.target.value)} />
      <nav>{groups.map(([id, title, number]) => <div key={id} className="nav-group"><button className={group === id ? 'nav-item active' : 'nav-item'} onClick={() => navigate(id)}><span>{number}</span>{title}<small>{scenarios.filter(s => s.group === id).length}</small></button>
        {(group === id || search) && scenarios.filter(s => s.group === id && (!search || s.label.includes(search))).map(s => <button key={s.id} className={`case-link ${caseId === s.id ? 'active' : ''}`} onClick={() => navigate(id, s.id)}>{s.label}</button>)}</div>)}
        <button className={`nav-item ${group === 'monthly' ? 'active' : ''}`} onClick={() => navigate('monthly')}><span>08</span>월 랭킹·자동 발급</button></nav>
      <div className="sidebar-foot"><span className="live-dot" /> 실제 API 연결<span>결과는 서버 응답으로 확인</span></div></aside>
    <main id="main"><div className="breadcrumb">{roleLabels[role]} <span>/</span> {scenario?.groupTitle ?? '월간 혜택'}</div>
      <div className="page-heading"><div><h1>{group === 'monthly' ? '월 랭킹과 쿠폰 발급' : scenario?.label}</h1><p>{group === 'monthly' ? '마감 시점에 고정한 선정 순위와 실제 발급 결과를 확인합니다.' : '입력부터 결과까지, 실제 거래의 흐름을 확인하세요.'}</p></div>
        <button className="secondary" onClick={() => setPanel(panel === 'requests' ? null : 'requests')}>요청·응답 {panel === 'requests' ? '접기' : '보기'}</button></div>
      {info.error && <div role="alert" className="notice error">{import.meta.env.DEV
        ? '운영·부하 제어 서버에 연결하지 못했습니다. 일반 API는 개발 프록시를 통해 계속 사용할 수 있습니다.'
        : `${info.error.message} · 통합 실행 주소는 http://127.0.0.1:4174입니다.`}</div>}
      {notice && <div role="status" className="notice">{notice}</div>}
      {mutationPending && <div className="notice"><p>응답이 계속 오지 않으면 대기를 중단하고 원거래를 조회할 수 있습니다. 거래 취소 기능은 아닙니다.</p>
        <button className="secondary" onClick={stopWaiting}>{operation.idempotent ? '응답 대기 중단 · 원요청 보존' : '응답 대기 중단'}</button></div>}
      {Object.values(recoveries[role] ?? {}).map(saved => <div className="notice" key={saved.operation.id} aria-label="미확정 요청 보존">
        <p>{saved.operation.title}의 원래 입력과 키를 현재 계정 메모리에 보존했습니다. 조회 화면을 다녀와도 복구할 수 있습니다. 자동으로 재전송하지 않습니다.</p>
        <div className="quick-actions"><button className="secondary" disabled={pending} onClick={() => {
          if (selectTask(saved.operation.id)) setRecoveries(old => ({ ...old, [role]: { ...old[role],
            [saved.operation.id]: { ...saved, values: structuredClone(saved.values) } } }));
        }}>원요청 다시 채우기</button>
          <button className="text-button" disabled={pending} onClick={() => releaseRecovery(role, saved.operation.id)}>결과 확인 완료 · 보존 해제</button></div>
        <p className="caption">원거래 결과를 확인한 뒤에만 보존을 해제하세요. 로컬 세션을 지우거나 다른 계정으로 로그인하면 보존값도 지워집니다.</p>
      </div>)}
      {(['BUYER', 'SELLER'] as const).map(actor => signupLogins[actor] && <div className="notice" key={actor}>
        <p>{roleLabels[actor]} 가입정보가 준비됐습니다. 현재 열린 화면에서만 로그인에 연결하고, 로그인에 성공하면 비웁니다.</p>
        <button className="secondary" disabled={pending} onClick={() => navigate('account', 'account.login', actor)}>가입한 {roleLabels[actor]}로 로그인</button>
      </div>)}
      {scenario && <><div className="steps" aria-label="흐름별 작업 선택">{steps.map(({ screen, operation: task }, index) => <button key={index}
        aria-current={stepIndex === index ? 'step' : undefined} disabled={!task}
        onClick={() => task && selectTask(task.id, index, screen[0] === '판매자' ? 'SELLER' : screen[0] === '구매자' ? 'BUYER' : role)}>
        <span>{String(index + 1).padStart(2, '0')}</span><strong>{screen[1]}</strong><small>{task ? `${actorLabel(task.actor)} · ${task.title}` : '연결된 작업 없음'}</small></button>)}</div>
        <p className="caption step-help">단계는 작업을 선택하는 버튼입니다. 입력을 확인한 뒤 아래의 ‘요청 실행’을 눌러야 실제 API를 호출합니다. 선택 표시는 거래 완료를 뜻하지 않습니다.</p></>}
      {caseId === 'buy.normal' && <section className="learning-start" aria-label="처음 구매하기 준비">
        <h2>처음에는 정상 주문 1건부터</h2>
        <p>계정이 없으면 아래 가입 버튼부터 시작하세요. 가입 폼의 연습용 가입값 채우기로 값을 만들고, 가입 뒤 같은 정보로 로그인할 수 있습니다. 판매자로 상품·재고를 준비한 뒤 구매자로 주문합니다.</p>
        <div className="quick-actions"><button onClick={() => navigate('account', 'account.seller', 'SELLER')}>판매자 가입부터 시작</button>
          <button onClick={() => navigate('account', 'account.buyer', 'BUYER')}>구매자 가입부터 시작</button>
          <button onClick={() => navigate('sell', 'sell.product', 'SELLER')}>판매자로 상품·재고 준비</button>
          <button onClick={() => { navigate('account', 'account.login', 'BUYER'); }}>구매자 로그인</button>
          <button onClick={() => { navigate('account', 'account.address', 'BUYER'); selectTask('post_api_buyers_me_addresses', -1, 'BUYER'); }}>구매자 배송지 등록</button></div>
        <p className="caption">외부 결제에는 준비된 모의 금융 시험계정이 필요합니다. 주문 생성 응답은 결제 확정이 아니며, 이후 내 주문·내 결제를 조회합니다. 배송·구매확정·지급은 실제 기한과 백엔드 처리기가 준비되어야 합니다.</p>
      </section>}
      <details className="prerequisites"><summary>시연 준비 조건과 정책</summary><p>{scenario?.note}</p>{requirements(caseId).map(note => <p key={note}>{note}</p>)}
        {group === 'monthly' && <p>전월 구매확정·미환불 거래의 외부 결제액으로 상위 100위와 동점자를 선정합니다. 매월 1일 00:00 마감·00:10 자동 발급. 수동 발급·업무 시간 이동 기능은 없습니다.</p>}</details>
      <section className="work-card"><div className="card-heading"><div><span className="overline">실제 작업</span><h2>{operation?.title ?? '작업 선택'}</h2></div><span className="badge neutral">{operation ? actorLabel(operation.actor) : ''}</span></div>
        <p className="caption execution-guide">시연자 한 명이 이 화면에서 구매·판매 계정을 전환해 사용합니다. 계정 선택과 로그인은 별도이며, 운영 도구는 서버 운영 자격을 사용합니다.</p>
        <label className="operation-select">할 작업<Select value={operationId} onChange={e => selectTask(e.target.value)}>{visibleActions.map(op => <option key={op.id} value={op.id}>{op.method} · {op.title}</option>)}</Select></label>
        <div className="quick-actions">{visibleActions.filter(op => op.method === 'GET').slice(0, 5).map(op => <button key={op.id} className={op.id === operationId ? 'chip selected' : 'chip'} onClick={() => selectTask(op.id)}>{op.title}</button>)}</div>
        {operation && <div className="execution-readiness" aria-label="실행 준비">
          {operation.actor === 'PUBLIC' ? <p>로그인 없이 실행할 수 있습니다. 입력을 확인한 뒤 <strong>요청 실행</strong>을 누르세요.</p>
            : role === 'OPERATOR' ? <p><strong>운영자 전용 작업입니다.</strong> {allowed ? '서버에 설정한 운영 자격으로 실행합니다.' : '현재 이 서비스의 운영 자격을 확인하지 못했습니다. 구매자·판매자 로그인으로 대신할 수 없습니다.'}</p>
            : !allowed ? <><p><strong>{roleLabels[role]} 로그인이 필요합니다.</strong> 로그인한 뒤 필요한 ID를 생성·조회 응답에서 선택하세요.</p>
              <button className="secondary" disabled={pending} onClick={() => navigate('account', 'account.login', role)}>{roleLabels[role]}로 로그인·가입</button></>
            : <p>현재 {roleLabels[role]} 계정으로 실행합니다. 필요한 값을 확인한 뒤 <strong>요청 실행</strong>을 누르세요.</p>}
        </div>}
        {operation && <OperationForm key={`${role}:${operation.id}:${session?.epoch ?? 0}`} operation={operation} context={context[role] ?? EMPTY_CONTEXT} pending={pending} allowed={allowed} run={run} role={role} token={session?.token}
          loginValues={operation.path === (role === 'SELLER' ? '/api/seller-sessions' : '/api/buyer-sessions') ? signupLogins[role] : undefined}
          recoveryValues={recoveries[role]?.[operation.id]?.values} />}
        {!pending && current && current.path === operation?.path && current.method === operation?.method && <div className="request-outcome" aria-label="요청 응답 확인">
          <strong className={current.status < 300 ? 'http-ok' : 'http-error'}>HTTP {current.status} · {current.elapsedMs}ms</strong>
          <span>{current.status === 202 ? '마지막 응답은 접수입니다. 최종 완료는 거래 조회로 확인하세요.' : '마지막으로 받은 응답입니다.'}</span><a href="#server-result">받은 결과 보기</a>
        </div>}
        {role !== 'OPERATOR' && <button className="text-button login-link" onClick={() => navigate('account', 'account.login')}>로그인·가입 화면 열기</button>}
      </section>
      <section className="work-card result-section" id="server-result"><div className="card-heading"><div><span className="overline">서버에서 확인한 결과</span><h2>{current?.operation ?? '아직 실행한 작업이 없습니다'}</h2></div>{current && <span className={`badge ${current.status < 300 ? 'success' : 'failure'}`}>HTTP {current.status}</span>}</div>
        {operation?.path.endsWith('/gauge') && current?.path === operation.path && <p className="caption">정상 조회 후 1초마다 갱신합니다. 입력 오류가 있으면 값을 고친 뒤 다시 실행하세요. 백그라운드에서는 중단합니다.</p>}
        {gauge.error && <p role="alert" className="notice error">게이지를 다시 확인하지 못했습니다. 아래는 마지막 관측값이며 현재 판매 가능 여부는 주문 요청에서 다시 검증합니다.</p>}
        {current ? <><p className="caption">{kst(current.at)} KST · <span title="요청 시작부터 프록시·서버 왕복과 응답 본문 수신·해석까지의 브라우저 관측 시간입니다. 서버 내부 처리시간이나 비동기 거래의 최종 완료시간이 아닙니다.">응답 확인 {current.elapsedMs}ms</span> · {roleLabels[current.actor as Role] ?? current.actor} · {current.status === 202 ? '접수 응답 · 최종 완료 아님' : '해당 요청의 응답'}</p>
          <ResultView data={current.response as Json} select={selectRow} />
          {current.status === 200 && current.path === '/api/products' && current.response && typeof current.response === 'object'
            && 'items' in current.response && Array.isArray(current.response.items) && current.response.items.length === 0 && <div className="learning-start">
              <p>판매 상품이 없습니다. 판매자로 로그인해 상품을 등록하고 판매 가능한 재고를 준비한 뒤 기본 구매로 돌아오세요.</p>
              <button onClick={() => { navigate('sell', 'sell.product', 'SELLER'); selectTask('post_api_sellers_me_products', -1, 'SELLER'); }}>판매자로 상품 등록하기</button></div>}
        </> : <div className="empty"><span className="empty-icon"><ArrowIcon /></span><strong>선택한 작업의 응답을 확인하세요</strong><p>필요한 값을 입력한 뒤 요청을 실행하세요. 이전 결과는 실행 기록에서 다시 선택할 수 있습니다.<br />로그인이 필요한 작업은 해당 역할로 먼저 로그인하세요.</p></div>}</section>
      {operation && <DataFlow key={`${role}:${session?.epoch ?? 0}`} operation={operation} record={pending ? undefined : current} pending={pending} info={info.data} />}
    </main>
    {panel && <aside className="inspector"><div className="inspector-tabs"><button className={panel === 'requests' ? 'active' : ''} onClick={() => setPanel('requests')}>요청·응답</button><button className={panel === 'experiments' ? 'active' : ''} onClick={() => setPanel('experiments')}>실험·모니터링</button><button aria-label="패널 닫기" onClick={() => setPanel(null)}><svg className="icon" width="16" height="16" viewBox="0 0 16 16" aria-hidden="true"><path d="m4 4 8 8m0-8-8 8" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" /></svg></button></div>
      {panel === 'experiments' ? <Experiments info={info.data} /> : <><div className="journal-heading"><h2>실행 기록</h2><span>{history.length}건 · {roleLabels[role]}</span></div>
        <p className="caption inspector-caption">현재 역할에서 실행한 요청입니다. 비동기 작업의 최종 완료는 거래 조회에서 확인하세요.</p>
        <div className="journal">{history.map(e => <button key={e.id} className={`journal-item ${current?.id === e.id ? 'selected' : ''}`} onClick={() => setSelected(e.id)}><span className={e.status < 300 ? 'http-ok' : 'http-error'}>{e.status}</span><span><strong>{e.operation}</strong><small>{kst(e.at)} · {e.elapsedMs}ms</small></span></button>)}</div>
        {current && <div className="payload"><h3>보낸 값</h3><pre>{JSON.stringify(redact(current.request), null, 2)}</pre><h3>받은 결과</h3><pre>{JSON.stringify(redact(current.response), null, 2)}</pre></div>}
      </> }</aside>}
  </div>;
}
