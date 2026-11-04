import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { parseResponse } from '../../shared/api/client';
import { ArrowIcon } from '../../shared/ui/Select';

export interface DemoInfo {
  gateway: string; operatorServices: string[]; timeTravel: boolean;
  loadEnabled: boolean; loadProject: string; links: { title: string; url: string }[];
}
interface LoadRun { id: string; state: string; startedAt: string; exitCode?: number; output: string; summary?: unknown }
async function control<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await fetch(`/demo/${path}`, { method, headers: { 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  const result = parseResponse(await response.text());
  if (!response.ok) {
    const error = result && typeof result === 'object' && !Array.isArray(result) ? result : {};
    throw new Error(String(error.detail ?? error.code ?? '제어 요청 실패'));
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
      <p className="caption">접속 주소는 실행 설정 기준입니다. 별도 프로필 도구는 기동이 필요합니다. 지표는 실제 시각 기준으로 조회하세요.</p></section>
    <section><h3>업무 시간</h3><p>배송 예정·환불 마감·정산 준비 시각은 실제 응답에서 확인합니다.</p>
      <button disabled title="여러 서비스와 DB 실행 시각을 분리하는 백엔드 기능이 필요합니다.">다음 업무 시각으로 이동</button>
      <p className="caption">아직 지원하지 않습니다. 브라우저 시각 변경이나 DB 수정으로 대신하지 않습니다.</p></section>
    <section><h3>결제 부하 실행</h3><p className="caption">Kafka 수신 → Payment → 금융 Mock 범위입니다. 주문 API·선착순·전체 정산 TPS를 측정하지 않습니다.</p>
      <p className="project-label">대상 <code>{info?.loadProject || '미설정'}</code></p>
      <div className="fields"><label>초당 요청<input type="number" min="1" max="100" value={rate} onChange={e => setRate(Number(e.target.value))} /></label>
        <label>주입 시간(초)<input type="number" min="1" max="30" value={seconds} onChange={e => setSeconds(Number(e.target.value))} /></label></div>
      <button className="primary" disabled={!info?.loadEnabled || start.isPending} onClick={() => start.mutate()}>{start.isPending ? '실행 접수 중…' : '부하테스트 시작'}</button>
      {!info?.loadEnabled && <p className="caption">전용 부하 환경을 준비하고 서버 설정에서 활성화해야 합니다. 기존 사용자 데모를 대상으로 실행하지 않습니다.</p>}
      {(start.error || stop.error || runs.error) && <p role="alert" className="error">{(start.error ?? stop.error ?? runs.error)?.message}</p>}
      {runs.data?.map(run => <article className="run" key={run.id}><div><strong>{run.state}</strong><small>{run.startedAt}</small></div>
        <code>{run.id}</code>{run.state === 'RUNNING' && <button onClick={() => stop.mutate(run.id)} disabled={stop.isPending}>추가 주입 중지</button>}
        <details><summary>실행 출력·결과</summary><pre>{run.summary ? JSON.stringify(run.summary, null, 2) : run.output || '결과 대기 중'}</pre></details></article>)}
      <p className="caption">중지는 추가 주입을 멈춥니다. 이미 접수한 금융 작업은 취소하지 않습니다. 최종 상태·금액 대조는 별도로 확인합니다.</p>
    </section>
  </div>;
}
