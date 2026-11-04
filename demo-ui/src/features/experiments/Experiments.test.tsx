// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { focusManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, expect, it, vi } from 'vitest';
import { Experiments, type DemoInfo } from './Experiments';

let activeClient: QueryClient | undefined;
afterEach(() => { cleanup(); activeClient?.clear(); activeClient = undefined; focusManager.setFocused(undefined); vi.useRealTimers(); vi.unstubAllGlobals(); });
const info: DemoInfo = { gateway: '', operatorServices: [], timeTravel: false, loadEnabled: true, loadProject: 'commerce-settle-ui-load-test', links: [] };
function setup(demoInfo?: DemoInfo, items: unknown[] = []) {
  const fetch = vi.fn(async (_: unknown, init?: RequestInit) => init?.method === 'POST'
    ? new Response(JSON.stringify({ detail: '입력값을 확인해 주세요.', errors: [{ field: 'rate', reason: '100 이하여야 합니다.' }] }), { status: 400 })
    : new Response(JSON.stringify(items)));
  vi.stubGlobal('fetch', fetch);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  activeClient = client;
  const view = (next?: DemoInfo) => <QueryClientProvider client={client}><Experiments info={next} /></QueryClientProvider>;
  const rendered = render(view(demoInfo));
  return { fetch, rerenderInfo: (next?: DemoInfo) => rendered.rerender(view(next)) };
}
it.each([['초당 주입 건수', '101'], ['초당 주입 건수', '0'], ['초당 주입 건수', '1.5'], ['주입 시간 (초)', '31'], ['주입 시간 (초)', '0']])('%s에 %s를 입력하면 제출을 막는다', (label, value) => {
  const { fetch } = setup(info);
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
  expect((screen.getByRole('form', { name: '결제 부하 실행' }) as HTMLFormElement).checkValidity()).toBe(false);
  fireEvent.click(screen.getByRole('button', { name: '부하 테스트 시작' }));
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0);
});
it('서버 입력 오류를 필드 이름과 수정 사유로 보여준다', async () => {
  setup(info);
  fireEvent.click(screen.getByRole('button', { name: '부하 테스트 시작' }));
  await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('초당 주입 건수: 100 이하여야 합니다.'));
});

const advance = async (ms: number) => act(async () => { await vi.advanceTimersByTimeAsync(ms); });
it.each([false, undefined])('부하 활성 여부가 %s이면 목록 요청을 보내지 않는다', async enabled => {
  vi.useFakeTimers(); focusManager.setFocused(true);
  const { fetch } = setup(enabled === undefined ? undefined : { ...info, loadEnabled: enabled });
  await advance(4100);
  expect(fetch).not.toHaveBeenCalled();
  expect((screen.getByRole('button', { name: '부하 테스트 시작' }) as HTMLButtonElement).disabled).toBe(true);
});
it('활성 부하 환경은 빈 목록도 주기적으로 조회한다', async () => {
  vi.useFakeTimers(); focusManager.setFocused(true);
  const { fetch } = setup(info);
  await advance(4100);
  expect(fetch.mock.calls.length).toBeGreaterThanOrEqual(3);
});
it('활성 여부가 바뀌면 목록 조회를 중단하고 다시 활성화할 때 재개한다', async () => {
  vi.useFakeTimers(); focusManager.setFocused(true);
  const { fetch, rerenderInfo } = setup({ ...info, loadEnabled: false });
  await advance(4100); expect(fetch).not.toHaveBeenCalled();
  rerenderInfo(info); await advance(4100);
  const enabledCalls = fetch.mock.calls.length;
  expect(enabledCalls).toBeGreaterThanOrEqual(3);
  rerenderInfo({ ...info, loadEnabled: false }); await advance(4100);
  expect(fetch).toHaveBeenCalledTimes(enabledCalls);
  rerenderInfo(info); await advance(4100);
  expect(fetch.mock.calls.length - enabledCalls).toBeGreaterThanOrEqual(3);
});
it('비활성 서버로 전환하면 캐시의 실행과 중지 버튼을 숨기고 조회도 멈춘다', async () => {
  vi.useFakeTimers(); focusManager.setFocused(true);
  const run = { id: 'previous-run', state: 'RUNNING', startedAt: '2026-11-04T00:00:00Z', output: '' };
  const { fetch, rerenderInfo } = setup(info, [run]);
  await advance(5); expect(screen.getByText(run.id)).toBeTruthy();
  expect(screen.getByRole('button', { name: '추가 주입 중지' })).toBeTruthy();
  const beforeDisable = fetch.mock.calls.length;
  fetch.mockImplementation(async () => new Response('[]'));
  rerenderInfo({ ...info, loadEnabled: false }); await advance(4100);
  expect(fetch).toHaveBeenCalledTimes(beforeDisable);
  expect(screen.queryByText(run.id)).toBeNull();
  expect(screen.queryByRole('button', { name: '추가 주입 중지' })).toBeNull();
});
