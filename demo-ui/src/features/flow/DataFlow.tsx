import { useId, useState } from 'react';
import { operations, redact, type Operation } from '../../shared/api/contract';
import type { Execution } from '../../shared/api/client';
import type { DemoInfo } from '../experiments/Experiments';
import { Select, ArrowIcon } from '../../shared/ui/Select';
import { EventRecords } from './EventRecords.tsx';
import type { ResultTarget, FlowInspection } from './eventRecords';
import { producerApplication, consumerApplication, database, eventContracts, requestRoute, authenticationRoute, loginStages, protectedStages, type FlowNode } from './flowModel';

function Node({ node, x, y, width = 152, selected, onSelect }: { node: FlowNode; x: number; y: number;
  width?: number; selected: boolean; onSelect: (node: FlowNode) => void }) {
  return <g className={`flow-node ${selected ? 'is-selected' : ''}`} role="button" tabIndex={0}
    aria-label={`${node.title} · ${node.id.startsWith('login-') ? '로그인 발급 경로' : node.id.startsWith('protected-') ? '보호 요청 인증 경로' : node.subtitle}`} aria-pressed={selected} onClick={() => onSelect(node)}
    onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelect(node); } }}>
    <rect x={x} y={y} width={width} height="76" rx="8" />
    <text x={x + 16} y={y + 30} className="flow-node-title">{node.title}</text>
    <text x={x + 16} y={y + 54} className="flow-node-subtitle">{node.subtitle}</text>
  </g>;
}
function Arrow({ d, marker, returning = false }: { d: string; marker: string; returning?: boolean }) {
  return <path d={d} className={`flow-wire ${returning ? 'flow-return' : ''}`} markerEnd={`url(#${marker})`} />;
}
function Markers({ id }: { id: string }) {
  return <defs><marker id={id} markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
    <path d="M0 0 8 3 0 6" fill="currentColor" /></marker></defs>;
}

function AuthenticationDiagram({ nodes, protectedRequest, active, select, id }: { nodes: FlowNode[]; protectedRequest: boolean; active: FlowNode; select: (node: FlowNode) => void; id: string }) {
  const marker = `${id}-${protectedRequest ? 'protected' : 'login'}`;
  const redisOwner = protectedRequest ? 350 : 604;
  return <div className="flow-scroll" tabIndex={0} role="region" aria-label={protectedRequest ? '보호 요청 검증 도식 · 좌우로 스크롤' : '로그인 세션 발급 도식 · 좌우로 스크롤'}>
    <svg viewBox="0 0 820 390" className="flow-diagram" role="group" aria-label={protectedRequest ? '보호 요청의 인증 설계' : '로그인과 세션 발급 설계'}>
      <Markers id={marker} />
      <rect className="flow-zone" x="250" y="235" width="548" height="139" rx="12" />
      <text x="270" y="259" className="flow-zone-label">{protectedRequest ? 'Gateway 세션 검증 · 서비스 DB 접근' : 'Commerce가 접근하는 인증 저장소'}</text>
      {[0, 1].map(index => <g key={index}><Arrow d={`M${220 + index * 254} 105 H${274 + index * 254}`} marker={marker} />
        <Arrow d={`M${274 + index * 254} 132 H${220 + index * 254}`} marker={marker} returning /></g>)}
      <Arrow d={`M${redisOwner} 156 V210 H350 V280`} marker={marker} />
      <Arrow d={`M400 280 V225 H${redisOwner + 30} V156`} marker={marker} returning />
      <Arrow d="M674 156 V280" marker={marker} /><Arrow d="M710 280 V156" marker={marker} returning />
      <text x="427" y="202" className="flow-edge-label">{protectedRequest ? '세션·가드 확인' : 'gate·제한·세션 저장'}</text>
      <text x="713" y="210" className="flow-edge-label">{protectedRequest ? '업무 DB' : '계정 확인'}</text>
      <text x="35" y="192" className="flow-edge-label">{protectedRequest ? '검증한 요청 전달 / 업무 응답' : '로그인 요청 / 세션 토큰 응답'}</text>
      {nodes.slice(0, 3).map((node, index) => <Node key={node.id} node={node} x={20 + index * 254} y={80} width={200} selected={active.id === node.id} onSelect={select} />)}
      <Node node={nodes[3]} x={528} y={280} width={200} selected={active.id === nodes[3].id} onSelect={select} />
      <Node node={nodes[4]} x={274} y={280} width={200} selected={active.id === nodes[4].id} onSelect={select} />
    </svg>
  </div>;
}

export function DataFlow({ operation, record, pending, info, authenticated = false, inspection, onPickRecord, onStartTrade, onInspectResult }: { operation: Operation; record?: Execution;
  pending: boolean; info?: DemoInfo; authenticated?: boolean; inspection?: FlowInspection; onPickRecord?: () => void; onStartTrade?: () => void;
  onInspectResult?: (target: ResultTarget, id: string) => void }) {
  const [view, setView] = useState<'auto' | 'map' | 'sequence' | 'auth'>('auto');
  const [selectedNode, selectNode] = useState<FlowNode>();
  const [eventName, setEventName] = useState(eventContracts.find(e => e.producer === operation.owner)?.name ?? eventContracts[0].name);
  const [consumerName, setConsumerName] = useState('');
  const id = useId().replace(/:/g, '');
  // The selected journal entry owns both the rendered result and this route, even after choosing another task.
  const observedOperation = record && operations.find(op => op.method === record.method && op.path === record.path);
  const displayed = observedOperation || operation;
  const nodes = requestRoute(displayed, import.meta.env.DEV);
  const isLogin = displayed.method === 'POST' && ['/api/buyer-sessions', '/api/seller-sessions'].includes(displayed.path);
  const currentView = view === 'auto' ? isLogin ? 'auth' : 'map' : view;
  const authNodes = authenticationRoute(displayed);
  const protectedNodes = authenticationRoute(displayed, true);
  const selectableNodes = currentView === 'auth' ? [...authNodes, ...protectedNodes] : [...nodes, ...(displayed.actor !== 'PUBLIC' && displayed.actor !== 'OPERATOR' ? [redis] : [])];
  const head = nodes.slice(0, -1); const db = nodes.at(-1)!;
  const gap = head.length === 4 ? 204 : 306;
  const serviceX = 20 + gap * (head.length - 1);
  const active = selectedNode && selectableNodes.find(node => node.id === selectedNode.id) || selectableNodes[0];
  const event = eventContracts.find(e => e.name === eventName) ?? eventContracts[0];
  const consumer = event.consumers.includes(consumerName) ? consumerName : event.consumers[0];
  const topic = `commerce-settle.${event.producer}.v1`;
  const receivingDb = database(consumer === 'commerce-notifications' ? 'commerce' : consumer);
  const producingApp = producerApplication(event.producer);
  const consumingApp = consumerApplication(consumer);
  const monitoring = info?.links.filter(link => /zipkin|kafka/i.test(link.title)) ?? [];
  const eventNodes: FlowNode[] = [
    { id: 'outbox', title: `${database(event.producer)} DB`, subtitle: '업무 상태 + Outbox', detail: '' },
    { id: 'relay', title: 'Outbox Relay', subtitle: '앱 안의 Kafka 전송 코드', detail: '' },
    { id: 'kafka', title: 'Kafka 토픽', subtitle: topic, detail: '' },
    { id: 'consumer', title: 'Kafka Consumer', subtitle: `Listener · ${consumer}.v1`, detail: '' },
    { id: 'inbox', title: `${receivingDb} DB`, subtitle: 'Inbox + 처리 내용', detail: '' },
  ];
  return <section className="work-card data-flow" id="data-flow" aria-label="데이터 흐름">
    <div className="flow-heading"><div><h2>데이터 흐름</h2><p>요청의 서비스 경계와 응답 뒤 이어지는 이벤트를 살펴보세요.</p></div>
      <span className="badge neutral">관측과 설계 구분</span></div>
    <div className="flow-call"><div><code>{displayed.method}</code> <code>{displayed.path}</code></div>
      <span className={pending ? '' : record ? record.status < 300 ? 'http-ok' : 'http-error' : ''} aria-live="polite">
        {pending ? '응답 확인 중' : record ? `HTTP ${record.status} · ${record.elapsedMs}ms` : '표시할 HTTP 응답 기록 없음'}</span>
    </div>
    <EventRecords record={pending ? undefined : record} info={info} inspection={pending ? undefined : inspection} onPickRecord={onPickRecord} onStartTrade={onStartTrade} onInspectResult={pending ? undefined : onInspectResult} onSelectEvent={name => {
      if (eventContracts.some(event => event.name === name)) { setEventName(name); setConsumerName(''); }
    }} />
    <div className="flow-switch" aria-label="흐름 보기 방식">
      <button aria-pressed={currentView === 'map'} onClick={() => setView('map')}>인프라 지도</button>
      <button aria-pressed={currentView === 'sequence'} onClick={() => setView('sequence')}>시간순 왕복</button>
      <button aria-pressed={currentView === 'auth'} onClick={() => setView('auth')}>로그인·인증</button>
    </div>
    {currentView === 'auth' ? <div className="authentication-flow" role="region" aria-label="로그인·세션 인증 경로">
      <div className="auth-flow-status"><strong>로그인과 세션 발급</strong><span className="badge neutral">{authenticated ? '브라우저에 세션 보관 중' : '브라우저 세션 없음'}</span></div>
      <p className="caption">아래는 구매자·판매자의 로그인 설계입니다. {isLogin ? '선택한 로그인 요청의 내부 통과 여부는 관측하지 않습니다.' : '현재 선택한 API의 실행 경로와는 구분해서 살펴보세요.'} 보관한 세션의 유효성은 각 요청에서 서버가 확인합니다.</p>
      {isLogin && record && record.status >= 400 && <p className="notice error">로그인 요청에서 HTTP {record.status}를 받았습니다. 응답과 추적 ID로 원인을 확인하세요. 이 도식은 내부 실패 단계를 확정하지 않습니다.</p>}
      <AuthenticationDiagram nodes={authNodes} protectedRequest={false} active={active} select={selectNode} id={id} />
      <ol className="auth-flow-stages">{loginStages.map(([title, detail], index) => <li key={title}><span>{String(index + 1).padStart(2, '0')}</span><div><strong>{title}</strong><p>{detail}</p></div></li>)}</ol>
      <details className="protected-flow"><summary>로그인 이후 · 보호 요청의 인증 과정</summary>
        <p className="caption">Gateway 검증을 통과한 뒤 소유 서비스가 권한과 업무 상태를 검사합니다. 회원가입은 DB 계정 생성이며, 위 로그인 과정은 별도로 실행합니다.</p>
        <AuthenticationDiagram nodes={protectedNodes} protectedRequest active={active} select={selectNode} id={id} />
        <ol className="auth-flow-stages">{protectedStages.map(([title, detail], index) => <li key={title}><span>{String(index + 1).padStart(2, '0')}</span><div><strong>{title}</strong><p>{detail}</p></div></li>)}</ol>
      </details>
    </div> : <div className="flow-scroll" tabIndex={0} role="region" aria-label="요청 경로 도식 · 작은 화면에서 좌우로 스크롤">
      {currentView === 'map' ? <svg viewBox="0 0 820 355" className="flow-diagram" role="group" aria-label="요청의 인프라 지도">
        <Markers id={`${id}-http`} />
        <rect className="flow-zone" x="202" y="28" width="596" height="308" rx="12" />
        <text className="flow-zone-label" x="222" y="51">로컬 중계 · 백엔드 / 실제 내부 통과 여부는 미관측</text>
        {head.slice(1).map((node, index) => <g key={node.id}>
          <Arrow d={`M${20 + gap * index + 152} 110 H${20 + gap * (index + 1)}`} marker={`${id}-http`} />
          <Arrow d={`M${20 + gap * (index + 1)} 142 H${20 + gap * index + 152}`} returning marker={`${id}-http`} />
        </g>)}
        <Arrow d={`M${serviceX + 56} 166 V244`} marker={`${id}-http`} />
        <Arrow d={`M${serviceX + 96} 244 V166`} returning marker={`${id}-http`} />
        <text className="flow-edge-label" x={serviceX - 42} y="207">DB 접근 분기</text>
        {head.map((node, index) => <Node key={node.id} node={node} x={20 + gap * index} y={90} selected={active.id === node.id} onSelect={selectNode} />)}
        <Node node={db} x={serviceX} y={244} selected={active.id === 'db'} onSelect={selectNode} />
        {displayed.actor !== 'PUBLIC' && displayed.actor !== 'OPERATOR' && <>
          <Arrow d="M484 166 V244" marker={`${id}-http`} />
          <Arrow d="M518 244 V166" returning marker={`${id}-http`} />
          <Node node={redis} x={428} y={244} selected={active.id === 'redis'} onSelect={selectNode} />
          <text className="flow-edge-label" x="359" y="207">세션 검증</text>
        </>}
      </svg> : <svg viewBox="0 0 820 440" className="flow-diagram" role="group" aria-label="시간순 요청·응답 · 조건부 설계">
        <Markers id={`${id}-sequence`} />
        {nodes.map((node, index) => <line key={node.id} x1={96 + index * 153} x2={96 + index * 153} y1="96" y2="418" className="flow-lifeline" />)}
        {nodes.slice(1).map((node, index) => <g key={node.id}>
          <Arrow d={`M${96 + index * 153} ${140 + index * 36} H${96 + (index + 1) * 153}`} marker={`${id}-sequence`} />
          <text x={108 + index * 153} y={130 + index * 36} className="flow-edge-label">{node.id === 'db' ? '필요할 때 DB 접근' : node.id === 'service' ? '소유 API로' : 'HTTP 요청'}</text>
          <Arrow d={`M${96 + (index + 1) * 153} ${296 + (nodes.length - 2 - index) * 36} H${96 + index * 153}`} returning marker={`${id}-sequence`} />
          <text x={108 + index * 153} y={286 + (nodes.length - 2 - index) * 36} className="flow-edge-label">{node.id === 'db' ? '조회·저장 결과' : '응답'}</text>
        </g>)}
        {nodes.map((node, index) => <Node key={node.id} node={node} x={20 + index * 153} y={16} width={144} selected={active.id === node.id} onSelect={selectNode} />)}
      </svg>}
    </div>}
    <div className="flow-legend"><span><i />요청 방향</span><span><i className="return" />응답 방향</span><span>도식은 조건부 경로 · 실행 추적 아님</span></div>
    <div className="flow-detail" aria-live="polite"><strong>{active.title} <span>{active.subtitle}</span></strong><p>{active.detail}</p></div>
    <p className="caption">HTTP 상태와 시간은 수신한 응답의 관측값이며 내부 실행을 증명하지 않습니다. 위 실제 이벤트 기록은 별도 시각에 읽은 DB 메타데이터입니다.
      {record?.status === 202 && ' 202는 접수 응답이며 최종 완료가 아닙니다.'}</p>
    {record && <details className="flow-values"><summary>이 응답의 보낸 값·받은 결과</summary><div>
      <section><h3>보낸 값</h3><pre>{JSON.stringify(redact(record.request), null, 2)}</pre></section>
      <section><h3>받은 결과</h3><pre>{JSON.stringify(redact(record.response), null, 2)}</pre></section>
    </div></details>}
    <details className="event-flow" open={currentView !== 'auth'}><summary><span>Kafka · Outbox → Inbox</span><small>이벤트 계약 {eventContracts.length}개</small></summary>
      <p className="caption">아래는 선택한 이벤트의 전달 계약입니다. 위 HTTP 요청이 이 이벤트를 만들었다는 뜻은 아닙니다. 실제 발행·소비 여부와 활성화 설정은 별도로 확인하세요.</p>
      <div className="event-controls"><label>살펴볼 이벤트<Select value={event.name} onChange={e => { setEventName(e.target.value); setConsumerName(''); }}>
        {eventContracts.map(e => <option key={e.name} value={e.name}>{e.name} · {e.producer} → {e.consumers.join(', ')}</option>)}</Select></label>
        <label>소비 그룹<Select value={consumer} onChange={e => setConsumerName(e.target.value)}>{event.consumers.map(c => <option key={c} value={c}>{c}.v1</option>)}</Select></label></div>
      <div className="event-contract"><code>{topic}</code><span>key <code>{event.key}</code> · v{event.version}</span></div>
      <div className="flow-scroll" tabIndex={0} role="region" aria-label="이벤트 전달 도식 · 작은 화면에서 좌우로 스크롤">
        <svg viewBox="0 0 840 410" className="flow-diagram" style={{ minWidth: 840 }} role="img" aria-label={`${event.name}: ${event.producer} Outbox에서 ${consumer} Inbox까지의 설계 경로`}>
          <Markers id={`${id}-event`} />
          {[12,286,560].map(x => <rect key={x} x={x} y="18" width="248" height="166" rx="12" className="flow-zone" />)}
          <text x="30" y="44" className="flow-node-title">{producingApp}</text><text x="30" y="64" className="flow-zone-label">Spring Boot 앱 안 · 발행 담당</text>
          <text x="304" y="44" className="flow-node-title">Kafka 브로커</text><text x="304" y="64" className="flow-zone-label">앱 밖 · 독립 인프라</text>
          <text x="578" y="44" className="flow-node-title">{consumingApp}</text><text x="578" y="64" className="flow-zone-label">Spring Boot 앱 안 · 수신 담당</text>
          <Arrow d="M240 118 H306" marker={`${id}-event`} /><Arrow d="M514 118 H580" marker={`${id}-event`} />
          <Arrow d="M72 184 V300" marker={`${id}-event`} /><Arrow d="M190 300 V156" marker={`${id}-event`} />
          <Arrow d="M620 156 V300" marker={`${id}-event`} /><Arrow d="M748 300 V156" returning marker={`${id}-event`} />
          <Arrow d="M788 132 H820 V208 H414 V156" returning marker={`${id}-event`} />
          <text x="244" y="103" className="flow-edge-label">발행</text><text x="520" y="103" className="flow-edge-label">수신</text>
          <text x="36" y="242" className="flow-edge-label">업무 코드가 저장</text><text x="194" y="274" className="flow-edge-label">Outbox 읽기</text>
          <text x="574" y="242" className="flow-edge-label">저장·처리</text><text x="752" y="274" className="flow-edge-label">DB 커밋</text>
          <text x="430" y="227" className="flow-edge-label">DB 커밋 확인 후 ack</text>
          <text x="36" y="398" className="flow-zone-label">DB는 앱 밖의 PostgreSQL · 각 앱이 자기 DB에 접근</text>
          {[[32,300],[32,80],[306,80],[580,80],[580,300]].map(([x,y],i) => <g key={eventNodes[i].id} className="flow-node">
            <rect x={x} y={y} width="208" height="76" rx="8" /><text x={x+16} y={y+30} className="flow-node-title">{eventNodes[i].title}</text><text x={x+16} y={y+54} className="flow-node-subtitle">{eventNodes[i].subtitle}</text></g>)}
        </svg></div>
      <ol className="event-explanation"><li><strong>업무 코드가 자기 DB에 저장</strong><span>{event.producer}의 업무 코드가 상태와 Outbox를 같은 트랜잭션에 저장합니다. DB 자체가 Kafka로 전송하지는 않습니다.</span></li>
        <li><strong>{producingApp} 안에서 발행</strong><span>앱 안의 Relay 스케줄러·실행 스레드가 Outbox를 읽어 Producer 코드로 보냅니다. Kafka 응답 대기는 DB 트랜잭션 밖이며 재발행해도 eventId를 유지합니다.</span></li>
        <li><strong>{consumingApp} 안에서 수신</strong><span>Consumer가 자기 DB에 Inbox·실행 작업·처리 내용을 저장하고 커밋 확인 후 Kafka에 ack합니다. 중복을 식별하고 선행 상태가 없으면 영속 대기합니다.</span></li></ol>
      <p className="caption">Kafka는 앱 밖의 독립 브로커이며 토픽은 그 안에 저장됩니다. Producer·Consumer는 위 Spring Boot 앱 안의 코드입니다. Payment API와 Payment Worker는 서로 다른 Spring Boot 프로세스로 같은 payment DB를 사용하며, Payment의 Relay·Consumer는 Worker에서 동작합니다. 알림 소비는 Commerce 앱 안입니다. Relay·Consumer·후속 처리기는 설정에 따라 비활성일 수 있습니다.</p>
    </details>
    <div className="flow-monitoring">{monitoring.map(link => <a key={link.title} href={link.url} target="_blank" rel="noreferrer">{link.title} 열기 <ArrowIcon /></a>)}
      <span className="caption">Zipkin은 수집·샘플링된 추적, Kafka UI는 토픽·그룹을 확인합니다.</span></div>
  </section>;
}

const redis: FlowNode = { id: 'redis', title: 'Redis', subtitle: 'Gateway 세션 검증', detail: '보호 요청에서 Gateway가 세션·계정 가드를 읽습니다. 세션 발급·폐기는 Commerce가 소유합니다. 공개 API는 이 인증 분기를 거치지 않으며 실제 접근 여부는 이 화면에서 관측하지 않습니다.' };
