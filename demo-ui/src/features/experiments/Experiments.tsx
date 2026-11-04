import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { parseResponse } from '../../shared/api/client';
import { ArrowIcon } from '../../shared/ui/Select';

export interface DemoInfo {
  gateway: string; operatorServices: string[]; timeTravel: boolean;
  loadEnabled: boolean; loadProject: string; links: { title: string; url: string }[];
}
const loadStates: Record<string, string> = { RUNNING: '실행 중', STOP_REQUESTED: '추가 주입 중지 요청됨', STOPPED: '중지 후 실행 종료', COMPLETED: '실행 종료', FAILED: '실행 도구 오류', INTERRUPTED: '실행 중단' };
interface LoadRun { id: string; state: string; startedAt: string; exitCode?: number; output: string; summary?: unknown }
async function control<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await fetch(`/demo/${path}`, { method, headers: { 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  const result = parseResponse(await response.text());
  if (!response.ok) {
    const error = result && typeof result === 'object' && !Array.isArray(result) ? result : {};
    const fields: Record<string, string> = { rate: '초당 주입 건수', seconds: '주입 시간', body: '요청 본문', id: '실행 ID' };
    const reasons = Array.isArray(error.errors) ? error.errors.flatMap(e => e && typeof e === 'object' && !Array.isArray(e)
      ? [`${fields[String(e.field)] ?? String(e.field)}: ${String(e.reason)}`] : []).join(' · ') : '';
    throw new Error([String(error.detail ?? error.code ?? '제어 요청을 처리하지 못했습니다.'), reasons].filter(Boolean).join(' '));
  }
  return result as T;
}
export function Experiments({ info }: { info?: DemoInfo }) {
  const [rate, setRate] = useState(10); const [seconds, setSeconds] = useState(10);
  const client = useQueryClient();
  const runs = useQuery({ queryKey: ['load-runs'], queryFn: () => control<LoadRun[]>('load-runs'), refetchInterval: 2000, retry: false });
  const start = useMutation({ mutationFn: () => control<LoadRun>('load-runs', 'POST', { rate, seconds }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['load-runs'] }) });
  const stop = useMutation({ mutationFn: (id: string) => control<LoadRun>(`load-runs/${id}/stop`, 'POST', {}),
    onSuccess: () => client.invalidateQueries({ queryKey: ['load-runs'] }) });
  return <div className="experiment-content">
    <div className="section-heading"><span>실험 제어</span><span className="badge neutral">로컬 전용</span></div>
    <section><h3>모니터링</h3><div className="monitor-links">{info?.links.map(link =>
      <a key={link.title} href={link.url} target="_blank" rel="noreferrer">{link.title}<ArrowIcon /></a>)}</div>
      <p className="caption">서버에 설정된 주소로 연결합니다. 모니터링 도구를 먼저 켜고 실제 요청 시각의 지표를 확인하세요.</p></section>
    <section><h3>업무 시간</h3><p>배송 예정·환불 마감·정산 준비 시각은 실제 응답에서 확인합니다.</p>
      <button disabled title="여러 서비스와 DB 실행 시각을 분리하는 백엔드 기능이 필요합니다.">다음 업무 시각으로 이동</button>
      <p className="caption">아직 지원하지 않습니다. 브라우저 시각 변경이나 DB 수정으로 대신하지 않습니다.</p></section>
    <section><h3>결제 부하 실행</h3><p className="caption">Kafka 메시지 수신 → 결제 서비스 → 모의 금융 서버의 처리량을 확인합니다. 주문 접수·선착순 이벤트·전체 정산의 처리량은 포함하지 않습니다.</p>
      <p className="project-label">대상 <code>{info?.loadProject || '미설정'}</code></p>
      <form aria-label="결제 부하 실행" onSubmit={e => { e.preventDefault(); start.mutate(); }}>
        <div className="fields"><label>초당 주입 건수<input type="number" required min="1" max="100" step="1" value={rate} onChange={e => setRate(Number(e.target.value))} /></label>
          <label>주입 시간 (초)<input type="number" required min="1" max="30" step="1" value={seconds} onChange={e => setSeconds(Number(e.target.value))} /></label></div>
        <p className="caption">초당 1~100건, 1~30초 동안 실행할 수 있습니다.</p>
        <button type="submit" className="primary" disabled={!info?.loadEnabled || start.isPending}>{start.isPending ? '실행 접수 중…' : '부하 테스트 시작'}</button>
      </form>
      {!info?.loadEnabled && <p className="caption">전용 부하 환경을 준비하고 서버 설정에서 활성화해야 합니다. 기존 사용자 데모를 대상으로 실행하지 않습니다.</p>}
      {(start.error || stop.error || runs.error) && <p role="alert" className="error">{(start.error ?? stop.error ?? runs.error)?.message}</p>}
      {runs.data?.map(run => <article className="run" key={run.id}><div><strong>{loadStates[run.state] ?? run.state}</strong><small>{new Date(run.startedAt).toLocaleString('ko-KR')}</small></div>
        <code>{run.id}</code>{run.state === 'RUNNING' && <button onClick={() => stop.mutate(run.id)} disabled={stop.isPending}>추가 주입 중지</button>}
        <details><summary>실행 출력·결과</summary><p className="caption">상태 코드: {run.state} · 종료 코드: {run.exitCode ?? '미확정'}</p><pre>{run.summary ? JSON.stringify(run.summary, null, 2) : run.output || '결과 대기 중'}</pre></details></article>)}
      <p className="caption">중지는 추가 주입을 멈춥니다. 이미 접수한 금융 작업은 취소하지 않습니다. 실행 종료 뒤에도 거래별 최종 상태와 금액을 대조해야 합니다.</p>
    </section>
  </div>;
}
