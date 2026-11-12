// @vitest-environment jsdom
import { act, cleanup, renderHook } from '@testing-library/react';
import { focusManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { PropsWithChildren } from 'react';
import { operations } from '../../shared/api/contract';
import type { Execution } from '../../shared/api/client';
import { useEventGauge } from './useEventGauge';

let activeClient: QueryClient | undefined;
beforeEach(() => vi.stubEnv('DEV', false));
afterEach(() => { cleanup(); activeClient?.clear(); activeClient = undefined; focusManager.setFocused(undefined); vi.useRealTimers(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.restoreAllMocks(); });
it('선택한 이벤트만 1초마다 갱신하고 화면 이탈·백그라운드에서는 주입을 멈춘다', async () => {
  vi.useFakeTimers(); focusManager.setFocused(true);
  const fetch = vi.fn(async () => new Response('{"upstreamStatus":200,"data":{"status":"OPEN","asOf":"2026-11-03T10:00:00Z"}}'));
  vi.stubGlobal('fetch', fetch);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  activeClient = client;
  const wrapper = ({ children }: PropsWithChildren) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  const gauge = operations.find(op => op.path.endsWith('/gauge'))!;
  const record: Execution = { id: 'gauge-1', at: new Date().toISOString(), actor: 'BUYER', method: 'GET',
    path: gauge.path, operation: gauge.title, status: 200, elapsedMs: 1,
    request: { pathParameters: { eventId: 'event-1' }, query: {}, body: null, idempotencyKey: 'test-key' }, response: {} };
  const { rerender, unmount } = renderHook(({ active }) => useEventGauge(active ? gauge : operations[0], record, ['BUYER', 'public', 0]),
    { wrapper, initialProps: { active: true } });
  await act(async () => { await vi.advanceTimersByTimeAsync(2100); });
  expect(fetch.mock.calls.length).toBeGreaterThanOrEqual(3);
  focusManager.setFocused(false);
  const beforeHidden = fetch.mock.calls.length;
  await act(async () => { await vi.advanceTimersByTimeAsync(2100); });
  expect(fetch).toHaveBeenCalledTimes(beforeHidden);
  rerender({ active: false }); focusManager.setFocused(true);
  const beforeExit = fetch.mock.calls.length;
  await act(async () => { await vi.advanceTimersByTimeAsync(2100); });
  expect(fetch).toHaveBeenCalledTimes(beforeExit);
  unmount(); client.clear();
});

function setupGauge(status: number, failure?: 'http' | 'network') {
  vi.useFakeTimers(); focusManager.setFocused(true);
  const fetch = vi.fn(async () => new Response('{"upstreamStatus":200,"data":{"status":"OPEN"}}'));
  if (failure === 'http') fetch.mockResolvedValueOnce(new Response('{"upstreamStatus":503,"data":{"code":"TEMPORARY"}}'));
  if (failure === 'network') fetch.mockRejectedValueOnce(new TypeError('synthetic transient response loss'));
  vi.stubGlobal('fetch', fetch);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } }); activeClient = client;
  const wrapper = ({ children }: PropsWithChildren) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  const operation = operations.find(op => op.path.endsWith('/gauge'))!;
  const record: Execution = { id: 'initial-gauge', at: '2026-11-04T00:00:00Z', actor: 'BUYER', method: 'GET',
    path: operation.path, operation: operation.title, status, elapsedMs: 1,
    request: { pathParameters: { eventId: status === 400 ? 'bad-id' : '11111111-1111-4111-8111-111111111111' }, query: {}, body: null, idempotencyKey: '' }, response: {} };
  const hook = renderHook(({ entry }) => useEventGauge(operation, entry, ['BUYER', 'public', 0]),
    { wrapper, initialProps: { entry: record } });
  return { ...hook, fetch, record };
}
const advance = async (ms: number) => act(async () => { await vi.advanceTimersByTimeAsync(ms); });
it.each([400, 404, 503])('최초 조회가 HTTP %s이면 실시간 재조회를 시작하지 않는다', async status => {
  const { fetch, result } = setupGauge(status);
  await advance(2100);
  expect(fetch).not.toHaveBeenCalled();
  expect(result.current.current?.status).toBe(status);
});
it.each(['http', 'network'] as const)('정상 조회 이후의 일시 %s 장애는 계속 관측해 회복한다', async failure => {
  const { fetch, result } = setupGauge(200, failure);
  await advance(5);
  if (failure === 'http') expect(result.current.current?.status).toBe(503);
  else expect(result.current.error).toBe(true);
  await advance(2100);
  expect(fetch.mock.calls.length).toBeGreaterThanOrEqual(3);
  expect(result.current.current?.status).toBe(200);
  expect(result.current.error).toBe(false);
});
it('입력을 고쳐 새로 조회한 성공 기록이 오면 실시간 관측을 시작한다', async () => {
  const { fetch, rerender, result, record } = setupGauge(400);
  await advance(5); expect(fetch).not.toHaveBeenCalled();
  rerender({ entry: { ...record, id: 'confirmed-gauge', status: 200,
    request: { ...record.request as object, pathParameters: { eventId: '11111111-1111-4111-8111-111111111111' } } } });
  await advance(2100);
  expect(fetch.mock.calls.length).toBeGreaterThanOrEqual(3);
  expect(result.current.current?.status).toBe(200);
});
