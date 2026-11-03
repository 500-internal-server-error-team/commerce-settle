// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { operations } from '../shared/api/contract';

const response = (body: unknown) => new Response(JSON.stringify(body));
let client: QueryClient;
afterEach(() => { cleanup(); client?.clear(); vi.unstubAllGlobals(); });
function setup() {
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === '/demo/info') return response({ operatorServices: [], links: [], loadEnabled: false });
    const command = JSON.parse(String(init?.body));
    if (command.operationId === 'post_api_buyer_sessions') return response({ upstreamStatus: 200,
      data: { token: 'private-buyer', account: { id: 'buyer-1', actorKind: 'BUYER', displayName: '테스트 구매자' } } });
    if (command.operationId === 'delete_api_sessions_current') return response({ upstreamStatus: 204, data: null });
    return response({ upstreamStatus: 200, data: { items: [{ id: 'product-1', name: '격리된 조회 상품', version: 3 }] } });
  });
  vi.stubGlobal('fetch', fetch);
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><App /></QueryClientProvider>);
  return fetch;
}
async function login() {
  fireEvent.click(screen.getByRole('button', { name: '역할별 로그인·가입 화면 열기' }));
  fireEvent.change(screen.getByLabelText(/이메일/), { target: { value: 'buyer@example.test' } });
  fireEvent.change(screen.getByLabelText(/비밀번호/), { target: { value: 'demo-test-password' } });
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  await screen.findByText('테스트 구매자');
}

it('본문 없는 서버 로그아웃 후 세션·기록·해당 계정의 조회 캐시를 지운다', async () => {
  setup(); await login();
  fireEvent.click(screen.getByRole('button', { name: /구매·결제/ }));
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  await screen.findByText('격리된 조회 상품');
  expect(client.getQueryCache().findAll({ queryKey: ['actor', 'BUYER'] }).length).toBeGreaterThan(0);
  fireEvent.click(screen.getByRole('button', { name: '역할별 로그인·가입 화면 열기' }));
  fireEvent.change(screen.getByLabelText('할 작업'), { target: { value: 'delete_api_sessions_current' } });
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  await screen.findByText('로그인 전');
  expect(screen.queryByText('테스트 구매자')).toBeNull();
  expect(screen.queryByText('격리된 조회 상품')).toBeNull();
  expect(client.getQueryCache().findAll({ queryKey: ['actor', 'BUYER', 'buyer-1'] })).toHaveLength(0);
});

it('역할을 전환하면 다른 역할의 계정·응답을 보여주지 않는다', async () => {
  setup(); await login();
  fireEvent.click(screen.getByRole('button', { name: /구매·결제/ }));
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  await screen.findByText('격리된 조회 상품');
  fireEvent.click(screen.getByRole('button', { name: '판매자' }));
  expect(screen.queryByText('테스트 구매자')).toBeNull();
  expect(screen.queryByText('격리된 조회 상품')).toBeNull();
  expect(screen.getByText('로그인 전')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '구매자' }));
  expect(screen.getByText('테스트 구매자')).toBeTruthy();
});

it('49개 상세 분기와 월간 화면을 모두 탐색하고 실제 작업 입력을 제공한다', () => {
  setup();
  const navigation = screen.getByRole('navigation');
  const groupButtons = within(navigation).getAllByRole('button').filter(button => button.classList.contains('nav-item'));
  let count = 0;
  for (const button of groupButtons) {
    fireEvent.click(button);
    const cases = within(navigation).getAllByRole('button').filter(item => item.classList.contains('case-link'));
    for (const item of cases) { fireEvent.click(item); expect(screen.getByRole('form', { name: 'API 요청' })).toBeTruthy(); count++; }
  }
  expect(count).toBe(49);
  expect(operations.some(op => op.path.endsWith('/targets'))).toBe(true);
});
