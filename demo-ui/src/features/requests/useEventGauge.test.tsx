// @vitest-environment jsdom
import { act, cleanup, renderHook } from '@testing-library/react';
import { focusManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, expect, it, vi } from 'vitest';
import type { PropsWithChildren } from 'react';
import { operations } from '../../shared/api/contract';
import type { Execution } from '../../shared/api/client';
import { useEventGauge } from './useEventGauge';

afterEach(() => { cleanup(); focusManager.setFocused(undefined); vi.useRealTimers(); vi.unstubAllGlobals(); });
it('선택한 이벤트만 1초마다 갱신하고 화면 이탈·백그라운드에서는 주입을 멈춘다', async () => {
  vi.useFakeTimers(); focusManager.setFocused(true);
  const fetch = vi.fn(async () => new Response('{"upstreamStatus":200,"data":{"status":"OPEN","asOf":"2026-11-03T10:00:00Z"}}'));
  vi.stubGlobal('fetch', fetch);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
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
