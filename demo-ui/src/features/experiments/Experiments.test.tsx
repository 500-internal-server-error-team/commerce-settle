// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, expect, it, vi } from 'vitest';
import { Experiments, type DemoInfo } from './Experiments';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
const info: DemoInfo = { gateway: '', operatorServices: [], timeTravel: false, loadEnabled: true, loadProject: 'commerce-settle-ui-load-test', links: [] };
function setup() {
  const fetch = vi.fn(async (_: unknown, init?: RequestInit) => init?.method === 'POST'
    ? new Response(JSON.stringify({ detail: '입력값을 확인해 주세요.', errors: [{ field: 'rate', reason: '100 이하여야 합니다.' }] }), { status: 400 })
    : new Response('[]'));
  vi.stubGlobal('fetch', fetch);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><Experiments info={info} /></QueryClientProvider>);
  return fetch;
}
it.each([['초당 주입 건수', '101'], ['초당 주입 건수', '0'], ['초당 주입 건수', '1.5'], ['주입 시간 (초)', '31'], ['주입 시간 (초)', '0']])('%s에 %s를 입력하면 제출을 막는다', (label, value) => {
  const fetch = setup();
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
  expect((screen.getByRole('form', { name: '결제 부하 실행' }) as HTMLFormElement).checkValidity()).toBe(false);
  fireEvent.click(screen.getByRole('button', { name: '부하 테스트 시작' }));
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0);
});
it('서버 입력 오류를 필드 이름과 수정 사유로 보여준다', async () => {
  setup();
  fireEvent.click(screen.getByRole('button', { name: '부하 테스트 시작' }));
  await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('초당 주입 건수: 100 이하여야 합니다.'));
});
