// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { operations } from '../shared/api/contract';
import { scenarios, workflow } from '../scenarios/catalog';

const response = (body: unknown) => new Response(JSON.stringify(body));
let client: QueryClient;
afterEach(() => { cleanup(); client?.clear(); vi.unstubAllGlobals(); });
function setup(items: unknown[] = [{ id: 'product-1', name: '격리된 조회 상품', version: 3 }]) {
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === '/demo/info') return response({ operatorServices: [], links: [], loadEnabled: false });
    const command = JSON.parse(String(init?.body));
    if (command.operationId === 'post_api_buyer_sessions') return response({ upstreamStatus: 200,
      data: { token: 'private-buyer', account: { id: 'buyer-1', actorKind: 'BUYER', displayName: '테스트 구매자' } } });
    if (command.operationId === 'post_api_seller_sessions') return response({ upstreamStatus: 200,
      data: { token: 'private-seller', account: { id: 'seller-1', actorKind: 'SELLER', displayName: '테스트 판매자' } } });
    if (command.operationId === 'post_api_buyers' || command.operationId === 'post_api_sellers') return response({ upstreamStatus: 201,
      data: { id: 'new-account', actorKind: command.operationId === 'post_api_sellers' ? 'SELLER' : 'BUYER' } });
    if (command.operationId === 'delete_api_sessions_current') return response({ upstreamStatus: 204, data: null });
    return response({ upstreamStatus: 200, data: { items } });
  });
  vi.stubGlobal('fetch', fetch);
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><App /></QueryClientProvider>);
  return fetch;
}
async function login() {
  fireEvent.click(screen.getByRole('button', { name: '로그인·가입 화면 열기' }));
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
  fireEvent.click(screen.getByRole('button', { name: '로그인·가입 화면 열기' }));
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

it.each([['BUYER', '구매자', 'buyers', 'buyer'], ['SELLER', '판매자', 'sellers', 'seller']])(
  '%s 연습 가입 후 같은 정보로 로그인하고 비밀번호를 메모리에서 비운다', async (_role, label, resource, session) => {
    const fetch = setup();
    fireEvent.click(screen.getByRole('button', { name: `${label} 가입부터 시작` }));
    expect((screen.getByLabelText('할 작업') as HTMLSelectElement).value).toBe(`post_api_${resource}`);
    fireEvent.click(screen.getByRole('button', { name: '연습용 가입값 채우기' }));
    const email = (screen.getByLabelText(/이메일/) as HTMLInputElement).value;
    const password = (screen.getByLabelText(/비밀번호/) as HTMLInputElement).value;
    expect(fetch).toHaveBeenCalledTimes(1);
    fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
    const loginButton = await screen.findByRole('button', { name: `가입한 ${label}로 로그인` });
    expect(document.body.textContent).not.toContain(password);
    fireEvent.click(loginButton);
    expect(fetch).toHaveBeenCalledTimes(2);
    expect((screen.getByLabelText('할 작업') as HTMLSelectElement).value).toBe(`post_api_${session}_sessions`);
    expect((screen.getByLabelText(/이메일/) as HTMLInputElement).value).toBe(email);
    expect((screen.getByLabelText(/비밀번호/) as HTMLInputElement).value).toBe(password);
    fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
    await screen.findByText(`테스트 ${label}`);
    expect(screen.queryByRole('button', { name: `가입한 ${label}로 로그인` })).toBeNull();
    expect(JSON.stringify(client.getQueryCache().getAll().map(query => query.state.data))).not.toContain(password);
    expect(localStorage.length).toBe(0); expect(sessionStorage.length).toBe(0);
    fireEvent.click(screen.getByRole('button', { name: '로컬 세션 지우기' }));
    expect((screen.getByLabelText(/비밀번호/) as HTMLInputElement).value).toBe('');
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
  for (const scenario of scenarios) {
    for (const role of ['BUYER', 'SELLER']) {
      expect(workflow(scenario, role)).toHaveLength(scenario.screens.length);
      expect(workflow(scenario, role).every(step => step.operation)).toBe(true);
      for (const step of workflow(scenario, role)) {
        const actor = ({ '구매자': 'BUYER', '판매자': 'SELLER', '운영자': 'OPERATOR' } as Record<string, string>)[step.screen[0]];
        if (actor && step.operation?.actor !== 'PUBLIC') expect(step.operation?.actor.split('|')).toContain(actor);
      }
    }
  }
  expect(operations.some(op => op.path.endsWith('/targets'))).toBe(true);
});

it('미확정 결제와 가격 변경은 원결제 조회와 견적부터 시작한다', () => {
  const fetch = setup();
  fireEvent.click(screen.getByRole('button', { name: '결제 확인 중·응답 유실' }));
  expect((screen.getByLabelText('할 작업') as HTMLSelectElement).value).toBe('get_api_payments_paymentId');
  fireEvent.click(screen.getByRole('button', { name: '가격·버전 변경' }));
  expect((screen.getByLabelText('할 작업') as HTMLSelectElement).value).toBe('post_api_order_quotes');
  expect(fetch).toHaveBeenCalledTimes(1); // Only capability discovery, never a financial request.
});

it('구매 단계를 선택하면 주문 입력을 열고 거래를 자동 실행하지 않는다', () => {
  const fetch = setup();
  const steps = screen.getByLabelText('흐름별 작업 선택');
  const orderStep = within(steps).getByRole('button', { name: /02.*주문서/ });
  fireEvent.click(orderStep);
  expect(orderStep.getAttribute('aria-current')).toBe('step');
  expect((screen.getByLabelText('할 작업') as HTMLSelectElement).value).toBe('post_api_orders');
  expect(screen.getByRole('button', { name: '요청 실행' }).hasAttribute('disabled')).toBe(true);
  expect(fetch).toHaveBeenCalledTimes(1);
});

it('흐름을 바꾸면 이전 응답을 비우고 실행 기록에서는 다시 선택할 수 있다', async () => {
  setup();
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  await screen.findByText('격리된 조회 상품');
  fireEvent.click(screen.getByRole('button', { name: '결제 확인 중·응답 유실' }));
  expect(screen.queryByText('격리된 조회 상품')).toBeNull();
  const journal = screen.getByRole('heading', { name: '실행 기록' }).parentElement!.parentElement!;
  fireEvent.click(within(journal).getByRole('button', { name: /200.*판매 상품 목록/ }));
  expect(screen.getByText('격리된 조회 상품')).toBeTruthy();
});

it('빈 상품 목록에서는 판매자 상품 등록으로 안내하고 자동 등록하지 않는다', async () => {
  const fetch = setup([]);
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  fireEvent.click(await screen.findByRole('button', { name: '판매자로 상품 등록하기' }));
  expect(screen.getByRole('button', { name: '판매자' }).getAttribute('aria-pressed')).toBe('true');
  expect((screen.getByLabelText('할 작업') as HTMLSelectElement).value).toBe('post_api_sellers_me_products');
  expect(fetch).toHaveBeenCalledTimes(2);
});

it('판매자의 반품 거절 흐름은 판매자 조회부터 시작한다', () => {
  setup();
  fireEvent.click(screen.getByRole('button', { name: /03.*취소·반품/ }));
  fireEvent.click(screen.getByRole('button', { name: '판매자 반품 거절' }));
  expect(screen.getByRole('button', { name: '판매자' }).getAttribute('aria-pressed')).toBe('true');
  expect((screen.getByLabelText('할 작업') as HTMLSelectElement).value).toBe('get_api_sellers_me_refund_requests');
});

it('주문 요청의 응답을 기다리는 동안 입력과 멱등키를 보존한다', async () => {
  const fetch = setup(); await login();
  fireEvent.click(screen.getByRole('button', { name: /01.*구매·결제/ }));
  fireEvent.click(within(screen.getByLabelText('흐름별 작업 선택')).getByRole('button', { name: /02.*주문서/ }));
  let finish!: (value: Response) => void;
  fetch.mockImplementationOnce(() => new Promise<Response>(resolve => { finish = resolve; }));
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  const originalKey = JSON.parse(String(fetch.mock.calls.at(-1)![1]?.body)).idempotencyKey;
  fireEvent.click(screen.getByRole('button', { name: '결제 확인 중·응답 유실' }));
  expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('기본 구매·배송·자동 확정');
  expect((screen.getByLabelText('할 작업') as HTMLSelectElement).value).toBe('post_api_orders');
  finish(response({ upstreamStatus: 202, data: { id: 'order-1', status: 'PAYMENT_PENDING' } }));
  await screen.findByText('요청이 접수됐습니다. 최종 완료는 해당 거래 조회로 확인하세요.');
  expect(screen.getByRole('button', { name: /202.*주문·결제 접수/ })).toBeTruthy();
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  expect(JSON.parse(String(fetch.mock.calls.at(-1)![1]?.body)).idempotencyKey).toBe(originalKey);
});

it('주문 대기 중 과거 결과를 연결해도 응답 유실 뒤 같은 입력과 키로 재요청한다', async () => {
  const fetch = setup(); await login();
  fireEvent.click(screen.getByRole('button', { name: /01.*구매·결제/ }));
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  await screen.findByText('격리된 조회 상품');
  fireEvent.click(within(screen.getByLabelText('흐름별 작업 선택')).getByRole('button', { name: /02.*주문서/ }));
  fireEvent.change(screen.getByLabelText(/상품 ID.*productId/), { target: { value: 'product-original' } });
  fireEvent.change(screen.getByLabelText(/수량.*quantity/), { target: { value: '2' } });
  let fail!: (error: Error) => void;
  fetch.mockImplementationOnce(() => new Promise<Response>((_resolve, reject) => { fail = reject; }));
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  const sent = JSON.parse(String(fetch.mock.calls.at(-1)![1]?.body));

  fireEvent.click(screen.getByRole('button', { name: /200.*판매 상품 목록/ }));
  fireEvent.click(screen.getByRole('button', { name: '입력에 연결' }));
  expect((screen.getByLabelText('멱등키') as HTMLInputElement).value).toBe(sent.idempotencyKey);
  expect((screen.getByLabelText(/상품 ID.*productId/) as HTMLInputElement).value).toBe('product-original');
  expect((screen.getByLabelText(/수량.*quantity/) as HTMLInputElement).value).toBe('2');
  await act(async () => fail(new TypeError('응답 유실 대역')));
  await screen.findByText(/응답을 확인하지 못했습니다/);
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));

  expect(JSON.parse(String(fetch.mock.calls.at(-1)![1]?.body))).toEqual(sent);
});

it('대기하지 않을 때는 실제 결과의 ID·버전을 다음 주문 입력에 연결한다', async () => {
  setup();
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  await screen.findByText('격리된 조회 상품');
  fireEvent.click(screen.getByRole('button', { name: '입력에 연결' }));
  fireEvent.click(within(screen.getByLabelText('흐름별 작업 선택')).getByRole('button', { name: /02.*주문서/ }));
  expect((screen.getByLabelText(/상품 ID.*productId/) as HTMLInputElement).value).toBe('product-1');
  expect((screen.getByLabelText(/확인한 상품 버전/) as HTMLInputElement).value).toBe('3');
});
