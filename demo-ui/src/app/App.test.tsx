// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { operations } from '../shared/api/contract';
import { scenarios, workflow } from '../scenarios/catalog';

const response = (body: unknown) => new Response(JSON.stringify(body));
let client: QueryClient;
beforeEach(() => {
  vi.stubEnv('DEV', false);
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => { callback(0); return 0; });
});
afterEach(() => { cleanup(); client?.clear(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.restoreAllMocks(); });
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

it('명시한 조회의 응답을 흐름도보다 먼저 보여주고 결과 바로가기를 제공한다', async () => {
  const fetch = setup();
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  await screen.findByText('격리된 조회 상품');
  const result = document.querySelector('.result-section')!;
  const diagram = screen.getByRole('region', { name: '데이터 흐름' });
  expect(result.compareDocumentPosition(diagram) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(screen.getByRole('link', { name: '받은 결과 보기' }).getAttribute('href')).toBe(`#${result.id}`);
  expect(fetch.mock.calls.filter(([url]) => url !== '/demo/info')).toHaveLength(1);
});

it.each([
  ['BUYER', '구매자', '/api/payments/{paymentId}', '/api/buyer-sessions'],
  ['SELLER', '판매자', '/api/sellers/me/products', '/api/seller-sessions'],
])('%s 보호 작업은 필요한 계정의 로그인 진입을 제공하고 자동 호출하지 않는다', (_role, label, path, loginPath) => {
  const fetch = setup();
  fireEvent.click(screen.getByRole('button', { name: label }));
  const op = operations.find(o => o.path === path && o.method === 'GET')!;
  fireEvent.change(screen.getByLabelText('할 작업'), { target: { value: op.id } });
  expect((screen.getByLabelText('할 작업') as HTMLSelectElement).value).toBe(op.id);
  expect((screen.getByRole('button', { name: '요청 실행' }) as HTMLButtonElement).disabled).toBe(true);
  fireEvent.click(screen.getByRole('button', { name: `${label}로 로그인·가입` }));
  expect(screen.getByRole('form', { name: 'API 요청' }).textContent).toContain(loginPath);
  expect(fetch.mock.calls.filter(([url]) => url !== '/demo/info')).toHaveLength(0);
});

it('구매·판매 계정과 운영 도구를 구분하고 미설정 운영 자격을 우회하지 않는다', () => {
  const fetch = setup();
  const accounts = screen.getByLabelText('API 실행 계정');
  expect(within(accounts).getAllByRole('button').map(b => b.textContent)).toEqual(['구매자', '판매자']);
  expect(within(document.querySelector('header')!).queryByRole('button', { name: '운영 도구' })).toBeNull();
  const tools = screen.getByRole('button', { name: /월 랭킹·자동 발급/ });
  expect(accounts.contains(tools)).toBe(false);
  expect(screen.queryByRole('button', { name: '발표자' })).toBeNull();
  fireEvent.click(tools);
  expect((screen.getByRole('button', { name: '요청 실행' }) as HTMLButtonElement).disabled).toBe(true);
  expect(screen.getByText(/현재 이 서비스의 운영 자격을 확인하지 못했습니다/)).toBeTruthy();
  expect(fetch.mock.calls.filter(([url]) => url !== '/demo/info')).toHaveLength(0);
});

it.each([['BUYER', '구매자'], ['SELLER', '판매자']])('%s 상단 로그인·가입은 명확한 실행 폼을 열고 자동 호출하지 않는다', (_actor, label) => {
  const fetch = setup();
  fireEvent.click(screen.getByRole('button', { name: label }));
  fireEvent.click(screen.getByRole('button', { name: `${label} 로그인 화면 열기` }));
  expect(within(screen.getByRole('form', { name: 'API 요청' })).getByRole('button', { name: '로그인' })).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: `${label} 회원가입 화면 열기` }));
  expect(within(screen.getByRole('form', { name: 'API 요청' })).getByRole('button', { name: '회원가입' })).toBeTruthy();
  expect(fetch.mock.calls.filter(([url]) => url !== '/demo/info')).toHaveLength(0);
});

it.each([['구매자', 'seller', '판매자'], ['판매자', 'buyer', '구매자']])('%s 상태에서 반대 계정 로그인 작업을 선택하면 실제 응답의 역할로 로그인한다', async (from, target, label) => {
  setup();
  fireEvent.click(screen.getByRole('button', { name: from }));
  fireEvent.click(screen.getByRole('button', { name: `${from} 로그인 화면 열기` }));
  fireEvent.click(screen.getByText('다른 계정 작업 선택'));
  fireEvent.change(screen.getByLabelText('할 작업'), { target: { value: `post_api_${target}_sessions` } });
  expect(screen.getByRole('button', { name: label }).getAttribute('aria-pressed')).toBe('true');
  fireEvent.change(screen.getByLabelText(/이메일/), { target: { value: `${target}@example.test` } });
  fireEvent.change(screen.getByLabelText(/비밀번호/), { target: { value: 'isolated-test-password' } });
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  await screen.findByLabelText('로그인 완료');
  expect(within(document.querySelector('header')!).getByText(`테스트 ${label}`)).toBeTruthy();
  expect(within(screen.getByLabelText('로그인 완료')).getByRole('button', { name: target === 'seller' ? /상품·재고 준비하기/ : /판매 상품 보기/ })).toBeTruthy();
});

it('가입 결과에서 로그인으로 이동하면 화면 시작 위치로 돌아가고 대기 중 전환은 이동하지 않는다', async () => {
  const fetch = setup();
  fireEvent.click(screen.getByRole('button', { name: '구매자 회원가입 화면 열기' }));
  fireEvent.click(screen.getByRole('button', { name: '연습용 가입값 채우기' }));
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  const next = await screen.findByRole('button', { name: '가입한 구매자로 로그인' });
  vi.mocked(window.scrollTo).mockClear();
  fireEvent.click(next);
  expect(window.scrollTo).toHaveBeenCalledExactlyOnceWith({ top: 0, behavior: 'auto' });
  let finish!: (value: Response) => void;
  fetch.mockImplementationOnce(() => new Promise<Response>(resolve => { finish = resolve; }));
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  vi.mocked(window.scrollTo).mockClear();
  fireEvent.change(screen.getByLabelText('시연 흐름 선택'), { target: { value: 'buy.normal' } });
  expect(window.scrollTo).not.toHaveBeenCalled();
  expect(screen.getByRole('form', { name: 'API 요청' }).textContent).toContain('/api/buyer-sessions');
  await act(async () => finish(response({ upstreamStatus: 401, data: { status: 401 } })));
});

it('두 계정을 각각 로그인한 뒤 전환해도 각 세션으로 요청하고 서로의 기록을 섞지 않는다', async () => {
  const fetch = setup(); await login();
  fireEvent.click(screen.getByRole('button', { name: '판매자' }));
  fireEvent.click(screen.getByRole('button', { name: '판매자 로그인 화면 열기' }));
  fireEvent.change(screen.getByLabelText(/이메일/), { target: { value: 'seller@example.test' } });
  fireEvent.change(screen.getByLabelText(/비밀번호/), { target: { value: 'isolated-test-password' } });
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  await screen.findByText('테스트 판매자');
  fireEvent.click(screen.getByRole('button', { name: /상품·재고 준비하기/ }));
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  await screen.findByText('격리된 조회 상품');
  expect(new Headers(fetch.mock.calls.at(-1)![1]?.headers).get('Authorization')).toBe('Bearer private-seller');
  fireEvent.click(screen.getByRole('button', { name: '구매자' }));
  expect(screen.queryByText('격리된 조회 상품')).toBeNull();
  expect(within(document.querySelector('header')!).getByText('테스트 구매자')).toBeTruthy();
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  await screen.findByText('격리된 조회 상품');
  expect(new Headers(fetch.mock.calls.at(-1)![1]?.headers).get('Authorization')).toBe('Bearer private-buyer');
});

it('운영 화면에서 공개 복구 작업을 거쳐 선택한 가입·로그인은 작업의 고유 계정에 결속한다', () => {
  const fetch = setup();
  for (const [id, label] of [['post_api_buyers', '구매자'], ['post_api_sellers', '판매자'], ['post_api_buyer_sessions', '구매자'], ['post_api_seller_sessions', '판매자']]) {
    fireEvent.change(screen.getByLabelText('시연 흐름 선택'), { target: { value: 'monthly' } });
    fireEvent.change(screen.getByLabelText('시연 흐름 선택'), { target: { value: 'account.password' } });
    expect(document.querySelector('.breadcrumb')?.textContent).toContain('운영자');
    fireEvent.change(screen.getByLabelText('할 작업'), { target: { value: id } });
    expect((screen.getByLabelText('할 작업') as HTMLSelectElement).value).toBe(id);
    expect(screen.getByRole('button', { name: label }).getAttribute('aria-pressed')).toBe('true');
  }
  expect(fetch.mock.calls.filter(([url]) => url !== '/demo/info')).toHaveLength(0);
});

it('좁은 화면의 공통 선택 경로에서도 49개 흐름과 월간 화면을 열며 자동 실행하지 않는다', () => {
  const fetch = setup(); const menu = screen.getByLabelText('시연 흐름 선택') as HTMLSelectElement;
  expect(Array.from(menu.options).map(o => o.value).sort()).toEqual([...scenarios.map(s => s.id), 'monthly'].sort());
  for (const scenario of scenarios) {
    fireEvent.change(menu, { target: { value: scenario.id } });
    expect(menu.value).toBe(scenario.id);
    expect(screen.getByRole('form', { name: 'API 요청' })).toBeTruthy();
  }
  fireEvent.change(menu, { target: { value: 'monthly' } });
  expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('월 랭킹과 쿠폰 발급');
  expect(fetch.mock.calls.filter(([url]) => url !== '/demo/info')).toHaveLength(0);
});

it('오류 결과는 ID 연결 동작을 제공하지 않고 가입한 정보의 로그인 연결은 유지한다', async () => {
  const fetch = setup();
  fireEvent.click(screen.getByRole('button', { name: '구매자 회원가입 화면 열기' }));
  fireEvent.click(screen.getByRole('button', { name: '연습용 가입값 채우기' }));
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  fireEvent.click(await screen.findByRole('button', { name: '가입한 구매자로 로그인' }));
  fetch.mockResolvedValueOnce(response({ upstreamStatus: 503, data: { status: 503, detail: '실제 인증 준비를 확인하세요.' } }));
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  await screen.findByText('실제 인증 준비를 확인하세요.');
  const result = document.querySelector('.result-section') as HTMLElement;
  expect(within(result).queryByRole('button', { name: 'ID·버전을 입력에 연결' })).toBeNull();
  expect(within(result).getByRole('button', { name: '가입한 구매자로 로그인' })).toBeTruthy();
});

it('본문 없는 서버 로그아웃 후 세션·기록·해당 계정의 조회 캐시를 지운다', async () => {
  setup(); await login();
  fireEvent.click(screen.getByRole('button', { name: /구매·결제/ }));
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  await screen.findByText('격리된 조회 상품');
  expect(client.getQueryCache().findAll({ queryKey: ['actor', 'BUYER'] }).length).toBeGreaterThan(0);
  fireEvent.click(screen.getByRole('button', { name: '로그인·가입 화면 열기' }));
  fireEvent.click(screen.getByText('다른 계정 작업 선택'));
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
    fireEvent.click(screen.getByRole('button', { name: label }));
    fireEvent.click(screen.getByRole('button', { name: `${label} 회원가입 화면 열기` }));
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
  fireEvent.click(screen.getByRole('button', { name: '실행 기록 보기' }));
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
  fireEvent.click(screen.getByRole('button', { name: '실행 기록 보기' }));
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

  fireEvent.click(screen.getByRole('button', { name: '실행 기록 보기' }));
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

const selectOperation = (id: string) => fireEvent.change(screen.getByLabelText('할 작업'), { target: { value: id } });
async function recoverCase(kind: string, quantity: number, navigate = true) {
  const fetch = setup(); await login();
  fireEvent.click(screen.getByRole('button', { name: /01.*구매·결제/ })); selectOperation('post_api_orders');
  fireEvent.change(screen.getByLabelText(/상품 ID.*productId/), { target: { value: `product-${quantity}` } });
  fireEvent.change(screen.getByLabelText(/수량.*quantity/), { target: { value: String(quantity) } });
  if (kind === 'transport') fetch.mockRejectedValueOnce(new TypeError('synthetic response loss'));
  else if (kind === 'parse') fetch.mockResolvedValueOnce(new Response('{broken'));
  else fetch.mockResolvedValueOnce(response({ upstreamStatus: kind === 'http' ? 504 : 202, data: { id: 'order-test', status: 'PAYMENT_PENDING' } }));
  await act(async () => fireEvent.submit(screen.getByRole('form', { name: 'API 요청' })));
  const original = JSON.parse(String(fetch.mock.calls.at(-1)![1]?.body));
  if (navigate) {
    selectOperation('get_api_orders');
    await act(async () => fireEvent.submit(screen.getByRole('form', { name: 'API 요청' })));
    selectOperation('post_api_orders');
  }
  expect((screen.getByLabelText('멱등키') as HTMLInputElement).value).toBe(original.idempotencyKey);
  expect((screen.getByLabelText(/상품 ID.*productId/) as HTMLInputElement).value).toBe(`product-${quantity}`);
  await act(async () => fireEvent.submit(screen.getByRole('form', { name: 'API 요청' })));
  expect(JSON.parse(String(fetch.mock.calls.at(-1)![1]?.body))).toEqual(original);
}
it('transport-recovery', () => recoverCase('transport',2));
it('parse-recovery', () => recoverCase('parse',3));
it('http-recovery', () => recoverCase('http',4));
it('accepted-retry', () => recoverCase('accepted',5,false));

async function loseOrder() {
  const fetch=setup();await login();
  fireEvent.click(screen.getByRole('button',{name:/01.*구매·결제/}));selectOperation('post_api_orders');
  fireEvent.change(screen.getByLabelText(/상품 ID.*productId/),{target:{value:'original-product'}});
  fetch.mockRejectedValueOnce(new TypeError('synthetic lost response'));
  await act(async()=>fireEvent.submit(screen.getByRole('form',{name:'API 요청'})));
  return {fetch,original:JSON.parse(String(fetch.mock.calls.at(-1)![1]?.body))};
}
it('미확정 원요청의 입력·키를 바꿔 덮어쓰지 않고 복구·성공 뒤에도 같은 키를 유지한다',async()=>{
  const {fetch,original}=await loseOrder();const count=fetch.mock.calls.length;
  fireEvent.change(screen.getByLabelText(/수량.*quantity/),{target:{value:'9'}});
  fireEvent.submit(screen.getByRole('form',{name:'API 요청'}));
  expect(fetch).toHaveBeenCalledTimes(count);
  expect(screen.getByText(/미확정 원요청과 입력·키가 다릅니다/)).toBeTruthy();
  fireEvent.click(screen.getByRole('button',{name:'원요청 다시 채우기'}));
  fetch.mockResolvedValueOnce(response({upstreamStatus:202,data:{id:'resolved-order'}}));
  await act(async()=>fireEvent.submit(screen.getByRole('form',{name:'API 요청'})));
  expect(JSON.parse(String(fetch.mock.calls.at(-1)![1]?.body))).toEqual(original);
  expect(screen.queryByLabelText('미확정 요청 보존')).toBeNull();
  await act(async()=>fireEvent.submit(screen.getByRole('form',{name:'API 요청'})));
  expect(JSON.parse(String(fetch.mock.calls.at(-1)![1]?.body))).toEqual(original);
});
it('역할을 전환하면 미확정 입력을 숨기고 로컬 세션을 지우면 함께 삭제한다',async()=>{
  await loseOrder();expect(screen.getByLabelText('미확정 요청 보존')).toBeTruthy();
  fireEvent.click(screen.getByRole('button',{name:'판매자'}));expect(screen.queryByLabelText('미확정 요청 보존')).toBeNull();
  fireEvent.click(screen.getByRole('button',{name:'구매자'}));expect(screen.getByLabelText('미확정 요청 보존')).toBeTruthy();
  fireEvent.click(screen.getByRole('button',{name:'로컬 세션 지우기'}));expect(screen.queryByLabelText('미확정 요청 보존')).toBeNull();
  expect(localStorage.length).toBe(0);expect(sessionStorage.length).toBe(0);
});
it.each([true,false])('동일 계정 재인증에만 미확정 원요청을 유지한다: %s',async(same)=>{
  const {fetch,original}=await loseOrder();
  fireEvent.click(screen.getByRole('button',{name:'로그인·가입 화면 열기'}));
  fireEvent.change(screen.getByLabelText(/이메일/),{target:{value:'buyer@example.test'}});
  fireEvent.change(screen.getByLabelText(/비밀번호/),{target:{value:'demo-test-password'}});
  fetch.mockResolvedValueOnce(response({upstreamStatus:200,data:{token:'new-token',account:{id:same?'buyer-1':'buyer-2',actorKind:'BUYER',displayName:'재인증 구매자'}}}));
  await act(async()=>fireEvent.submit(screen.getByRole('form',{name:'API 요청'})));
  expect(!!screen.queryByLabelText('미확정 요청 보존')).toBe(same);
  fireEvent.click(screen.getByRole('button',{name:/01.*구매·결제/}));selectOperation('post_api_orders');
  expect((screen.getByLabelText('멱등키') as HTMLInputElement).value===original.idempotencyKey).toBe(same);
});
it.each(['재로그인', '다른 흐름', '같은 작업 목록'])('%s 화면에서 원요청을 복구하면 작업 표시와 보낸 입력이 일치한다', async destination => {
  const { fetch, original } = await loseOrder();
  if (destination === '재로그인') {
    fireEvent.click(screen.getByRole('button', { name: '로그인·가입 화면 열기' }));
    fireEvent.change(screen.getByLabelText(/이메일/), { target: { value: 'buyer@example.test' } });
    fireEvent.change(screen.getByLabelText(/비밀번호/), { target: { value: 'demo-test-password' } });
    await act(async () => fireEvent.submit(screen.getByRole('form', { name: 'API 요청' })));
  } else {
    fireEvent.click(screen.getByRole('button', { name: /05.*쿠폰·마일리지/ }));
    if (destination === '다른 흐름') {
      fireEvent.click(screen.getByRole('button', { name: '판매자 쿠폰 수량·예산 소진' }));
      fireEvent.click(within(screen.getByLabelText('흐름별 작업 선택')).getAllByRole('button')[2]);
    }
  }
  const beforeRestore = fetch.mock.calls.length;
  fireEvent.click(screen.getByRole('button', { name: '원요청 다시 채우기' }));
  expect(fetch).toHaveBeenCalledTimes(beforeRestore);
  const selector = screen.getByLabelText('할 작업') as HTMLSelectElement;
  expect(selector.value).toBe(original.operationId);
  expect(Array.from(selector.options).filter(option => option.value === original.operationId)).toHaveLength(1);
  expect((screen.getByLabelText('멱등키') as HTMLInputElement).value).toBe(original.idempotencyKey);
  expect((screen.getByLabelText(/상품 ID.*productId/) as HTMLInputElement).value).toBe(original.body.productId);
  await act(async () => fireEvent.submit(screen.getByRole('form', { name: 'API 요청' })));
  expect(JSON.parse(String(fetch.mock.calls.at(-1)![1]?.body))).toEqual(original);
});
it('원거래 결과를 확인한 사용자가 보존을 해제한 뒤 새 작업을 시작한다',async()=>{
  const {fetch,original}=await loseOrder();
  fireEvent.click(screen.getByRole('button',{name:'결과 확인 완료 · 보존 해제'}));
  expect(screen.queryByLabelText('미확정 요청 보존')).toBeNull();
  fireEvent.change(screen.getByLabelText(/수량.*quantity/),{target:{value:'7'}});
  fireEvent.click(screen.getByRole('button',{name:'새 작업 키 만들기'}));
  await act(async()=>fireEvent.submit(screen.getByRole('form',{name:'API 요청'})));
  const sent=JSON.parse(String(fetch.mock.calls.at(-1)![1]?.body));
  expect(sent.body.quantity).toBe(7);expect(sent.idempotencyKey).not.toBe(original.idempotencyKey);
});

it.each(['headers','body','headers-error','body-error'])('응답 %s가 멈춰도 원요청을 보존한 뒤 대기를 중단하고 늦은 결과를 무시한다',async(stage)=>{
  const fetch=setup();await login();
  fireEvent.click(screen.getByRole('button',{name:/01.*구매·결제/}));selectOperation('post_api_orders');
  fireEvent.change(screen.getByLabelText(/상품 ID.*productId/),{target:{value:'stalled-product'}});
  let finish!:()=>void;let signal:AbortSignal|undefined;
  fetch.mockImplementationOnce(async(_url:string,init?:RequestInit)=>{
    signal=init?.signal??undefined;
    if(stage.startsWith('headers'))return new Promise<Response>((resolve,reject)=>finish=()=>stage.endsWith('error')?reject(new TypeError('late transport failure')):resolve(response({upstreamStatus:202,data:{id:'late-order'}})));
    return {status:200,text:()=>new Promise<string>((resolve,reject)=>finish=()=>stage.endsWith('error')?reject(new SyntaxError('late body failure')):resolve(JSON.stringify({upstreamStatus:202,data:{id:'late-order'}})))} as Response;
  });
  await act(async()=>fireEvent.submit(screen.getByRole('form',{name:'API 요청'})));
  const original=JSON.parse(String(fetch.mock.calls.at(-1)![1]?.body));
  fireEvent.click(screen.getByRole('button',{name:'응답 대기 중단 · 원요청 보존'}));
  expect(signal?.aborted).toBe(true);expect(screen.getByLabelText('미확정 요청 보존')).toBeTruthy();
  selectOperation('get_api_orders');await act(async()=>fireEvent.submit(screen.getByRole('form',{name:'API 요청'})));
  await act(async()=>finish());
  expect(screen.queryByText('late-order')).toBeNull();expect(screen.getByLabelText('미확정 요청 보존')).toBeTruthy();
  selectOperation('post_api_orders');
  expect((screen.getByLabelText('멱등키') as HTMLInputElement).value).toBe(original.idempotencyKey);
  await act(async()=>fireEvent.submit(screen.getByRole('form',{name:'API 요청'})));
  expect(JSON.parse(String(fetch.mock.calls.at(-1)![1]?.body))).toEqual(original);
});

it('중단한 이전 호출의 늦은 오류는 해제한 보존값이나 새 안내를 되살리지 않는다',async()=>{
  const fetch=setup();await login();fireEvent.click(screen.getByRole('button',{name:/01.*구매·결제/}));selectOperation('post_api_orders');
  let fail!:(error:Error)=>void;fetch.mockImplementationOnce(()=>new Promise<Response>((_resolve,reject)=>fail=reject));
  fireEvent.submit(screen.getByRole('form',{name:'API 요청'}));
  fireEvent.click(screen.getByRole('button',{name:'응답 대기 중단 · 원요청 보존'}));
  fireEvent.click(screen.getByRole('button',{name:'결과 확인 완료 · 보존 해제'}));
  selectOperation('get_api_orders');await act(async()=>fireEvent.submit(screen.getByRole('form',{name:'API 요청'})));
  await act(async()=>fail(new TypeError('late cancelled error')));
  expect(screen.queryByLabelText('미확정 요청 보존')).toBeNull();
  expect(screen.queryByText(/응답을 확인하지 못했습니다/)).toBeNull();
});
