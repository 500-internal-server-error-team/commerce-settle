// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { operations } from '../../shared/api/contract';
import { OperationForm } from './OperationForm';
import { mergeResponseContext, responseContext } from './responseContext';

beforeEach(() => vi.stubEnv('DEV', false));
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.restoreAllMocks(); });
it.each(['buyers', 'sellers'])('%s 연습값은 유효한 폼을 채우고 요청 대기 중에는 다시 바꾸지 않는다', resource => {
  const run = vi.fn(); const context = {};
  const operation = operations.find(op => op.id === `post_api_${resource}`)!;
  const view = render(<OperationForm operation={operation} context={context} pending={false} allowed run={run} />);
  fireEvent.click(screen.getByRole('button', { name: '연습용 가입값 채우기' }));
  const email = (screen.getByLabelText(/이메일/) as HTMLInputElement).value;
  const password = (screen.getByLabelText(/비밀번호/) as HTMLInputElement).value;
  expect(email).toMatch(/@example\.test$/); expect(password.length).toBeGreaterThanOrEqual(12);
  expect((screen.getByRole('form', { name: 'API 요청' }) as HTMLFormElement).checkValidity()).toBe(true);
  expect(run).not.toHaveBeenCalled();
  view.rerender(<OperationForm operation={operation} context={context} pending allowed run={run} />);
  fireEvent.click(screen.getByRole('button', { name: '연습용 가입값 채우기' }));
  expect((screen.getByLabelText(/이메일/) as HTMLInputElement).value).toBe(email);
  expect((screen.getByLabelText(/비밀번호/) as HTMLInputElement).value).toBe(password);
});
it('주문 안내와 명세 원문이 이벤트·쿠폰 사용을 금지하지 않고 선택한 조건을 전송한다', () => {
  const run = vi.fn();
  render(<OperationForm operation={operations.find(op => op.id === 'post_api_orders')!}
    context={{ productId: 'product-1', quantity: 1, eventId: 'event-1', buyerCouponId: 'coupon-1',
      expectedProductVersion: 3, expectedEventVersion: 2, expectedTotalAmount: 8000 }}
    pending={false} allowed run={run} />);
  expect(screen.queryByText(/일반 구매만 지원|이벤트와 쿠폰은 사용 안 함/)).toBeNull();
  expect(screen.queryByText(/eventId·buyerCouponId·expectedEventVersion에 null만 허용/)).toBeNull();
  expect(screen.getByText(/일반·이벤트 구매와 쿠폰 사용을 지원합니다/)).toBeTruthy();
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  expect(run.mock.calls[0][0].body).toMatchObject({ eventId: 'event-1', buyerCouponId: 'coupon-1',
    quantity: 1, expectedEventVersion: 2, expectedTotalAmount: 8000 });
});

it.each(['post_api_buyers_me_addresses', 'put_api_buyers_me_addresses_addressId'])('%s는 상세주소가 비어도 브라우저 검증을 통과하고 빈 문자열을 전송한다', id => {
  const run = vi.fn();
  render(<OperationForm operation={operations.find(op => op.id === id)!}
    context={{ addressId: 'address-1', addressIdVersionFor: 'address-1', addressIdVersion: 1,
      recipient: '수령인', phone: '01000000000', postalCode: '12345', addressLine1: '기본 주소', addressLine2: '' }}
    pending={false} allowed run={run} />);
  const form = screen.getByRole('form', { name: 'API 요청' }) as HTMLFormElement;
  expect(form.checkValidity()).toBe(true);
  fireEvent.click(screen.getByRole('button', { name: '요청 실행' }));
  expect(run.mock.calls[0][0].body.addressLine2).toBe('');
  fireEvent.change(screen.getByLabelText(/기본 주소.*addressLine1/), { target: { value: '' } });
  expect(form.checkValidity()).toBe(false);
});
it('필수 열거형 입력은 필수 표시와 빈 선택 차단을 유지한다', () => {
  render(<OperationForm operation={operations.find(op => op.id === 'put_api_sellers_me_products_productId')!}
    context={{}} role="SELLER" pending={false} allowed run={() => {}} />);
  const select = screen.getByRole('combobox', { name: /^상태/ }) as HTMLSelectElement;
  expect(select.labels?.[0].textContent).toContain('*');
  expect(select.required).toBe(true);
  fireEvent.change(select, { target: { value: '' } });
  expect(select.validity.valueMissing).toBe(true);
});
it('상품·이벤트 결과를 차례로 연결해도 상품 편집 값을 행사 값으로 채우지 않는다', () => {
  const product = responseContext({ id: 'product-P', name: '상품', unitPrice: 10000, status: 'ON_SALE', version: 7 }, '/api/sellers/me/products');
  const event = responseContext({ id: 'event-E', productId: 'product-P', name: '행사', status: 'DRAFT', version: 3 }, '/api/sellers/me/events');
  render(<OperationForm operation={operations.find(op => op.id === 'put_api_sellers_me_products_productId')!}
    context={mergeResponseContext(product, event)} role="SELLER" pending={false} allowed run={() => {}} />);
  expect((screen.getByLabelText(/^이름/) as HTMLInputElement).value).toBe('');
  expect((screen.getByLabelText(/판매 가격/) as HTMLInputElement).value).toBe('');
  expect((screen.getByLabelText(/확인한 버전/) as HTMLInputElement).value).toBe('7');
});
it('상품 수정 목록에서 고른 같은 상품의 편집 값과 버전을 함께 채운다', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ upstreamStatus: 200,
    data: { items: [{ id: 'product-P', name: '상품', unitPrice: 10000, status: 'ON_SALE', version: 7 }], totalPages: 1 } }))));
  const run = vi.fn();
  render(<OperationForm operation={operations.find(op => op.id === 'put_api_sellers_me_products_productId')!}
    context={{}} role="SELLER" pending={false} allowed run={run} />);
  fireEvent.click(screen.getByRole('button', { name: '상품 목록 불러오기' }));
  await screen.findByRole('option', { name: /product-P/ });
  fireEvent.change(screen.getByRole('combobox', { name: '상품 선택' }), { target: { value: 'product-P' } });
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  expect(run.mock.calls[0][0]).toMatchObject({ pathParameters: { productId: 'product-P' },
    body: { name: '상품', unitPrice: 10000, status: 'ON_SALE', expectedVersion: 7 } });
});
it('결과에서 연결한 주문 ID를 결제 조회 조건에 재사용한다', () => {
  const run = vi.fn();
  render(<OperationForm operation={operations.find(op => op.id === 'get_api_payments')!}
    context={{ orderId: 'order-1', status: 'UNRELATED' }} pending={false} allowed run={run} />);
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  expect(run.mock.calls[0][0]).toMatchObject({ query: { orderId: 'order-1' }, body: null });
  expect(run.mock.calls[0][0].query.status).not.toBe('UNRELATED');
});
it.each(['product', 'event'])('직접 %s ID를 바꾸면 관련 버전과 이전 견적을 함께 지운다', field => {
  const run = vi.fn();
  render(<OperationForm operation={operations.find(op => op.id === 'post_api_orders')!}
    context={{ productId: 'product-A', expectedProductVersion: 7, eventId: 'event-A', expectedEventVersion: 3,
      expectedTotalAmount: 10000 }} pending={false} allowed run={run} />);
  fireEvent.change(screen.getByLabelText(field === 'product' ? /상품 ID.*productId/ : /이벤트 ID.*eventId/),
    { target: { value: `${field}-B` } });
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  expect(run.mock.calls[0][0].body).toMatchObject(field === 'product'
    ? { productId: 'product-B', expectedProductVersion: '', eventId: null, expectedEventVersion: null, expectedTotalAmount: '' }
    : { productId: 'product-A', expectedProductVersion: 7, eventId: 'event-B', expectedEventVersion: '', expectedTotalAmount: '' });
});
it.each([null, 'product-B'])('쿠폰 적용 상품 %s가 선택한 상품·버전을 덮어쓰지 않는다', async productId => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ upstreamStatus: 200,
    data: { items: [{ id: 'coupon-1', productId, version: 99 }], totalPages: 1 } }))));
  const run = vi.fn();
  render(<OperationForm operation={operations.find(op => op.id === 'post_api_orders')!}
    context={{ productId: 'product-A', expectedProductVersion: 7, expectedTotalAmount: 10000 }} pending={false} allowed run={run} />);
  fireEvent.click(screen.getByRole('checkbox', { name: '사용할 쿠폰 ID 사용 안 함' }));
  fireEvent.click(screen.getByRole('button', { name: '쿠폰 목록 불러오기' }));
  await screen.findByRole('option', { name: /coupon-1/ });
  fireEvent.change(screen.getByRole('combobox', { name: '쿠폰 선택' }), { target: { value: 'coupon-1' } });
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  expect(run.mock.calls[0][0].body).toMatchObject({ productId: 'product-A', buyerCouponId: 'coupon-1',
    expectedProductVersion: 7, expectedTotalAmount: '' });
});
it('결제 조회의 주문 선택을 query에 연결하고 GET 본문을 만들지 않는다', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ upstreamStatus: 200,
    data: { items: [{ id: 'order-1', version: 3 }], totalPages: 1 } }))));
  const run = vi.fn();
  render(<OperationForm operation={operations.find(op => op.id === 'get_api_payments')!}
    context={{}} pending={false} allowed run={run} />);
  fireEvent.click(screen.getByRole('button', { name: '주문 목록 불러오기' }));
  await screen.findByRole('option', { name: /order-1/ });
  fireEvent.change(screen.getByRole('combobox', { name: '주문 선택' }), { target: { value: 'order-1' } });
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  expect(run.mock.calls[0][0]).toMatchObject({ query: { orderId: 'order-1' }, body: null });
});
it('필수 값이 null이어도 입력을 숨기지 않아 다시 채울 수 있다', () => {
  const run = vi.fn();
  render(<OperationForm operation={operations.find(op => op.id === 'post_api_order_quotes')!}
    context={{ productId: null }} pending={false} allowed run={run} />);
  fireEvent.change(screen.getByLabelText(/상품 ID.*productId/), { target: { value: 'product-A' } });
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  expect(run.mock.calls[0][0].body.productId).toBe('product-A');
});
it('다른 상품의 버전을 이벤트 수정의 버전으로 사용하지 않는다', () => {
  const operation = operations.find(op => op.path === '/api/sellers/me/events/{eventId}' && op.method === 'PUT')!;
  render(<OperationForm operation={operation} context={{ productIdVersion: 7, eventId: 'event-1', eventIdVersion: 3, eventIdVersionFor: 'event-1' }}
    pending={false} allowed run={() => {}} />);
  expect((screen.getByLabelText(/확인한 버전/) as HTMLInputElement).value).toBe('3');
});
it.each([
  [{ productId: 'product-1', productIdVersion: 7, productIdVersionFor: 'product-1' }, ''],
  [{ productId: 'product-1', inventoryProductId: 'product-1', inventoryVersion: 3 }, '3'],
  [{ productId: 'product-1', inventoryProductId: 'product-2', inventoryVersion: 3 }, ''],
])('재고 조정에는 같은 상품의 재고 버전만 사용한다 (%j)', (context, expected) => {
  render(<OperationForm operation={operations.find(op => op.id === 'post_api_sellers_me_products_productId_inventory_adjustments')!}
    context={context as Record<string, string | number>} role="SELLER" pending={false} allowed run={() => {}} />);
  expect((screen.getByLabelText(/확인한 버전/) as HTMLInputElement).value).toBe(expected);
});
it('실제 상품 목록을 역할 자격으로 조회하고 선택한 ID·버전을 입력에 연결한다', async () => {
  const fetch = vi.fn(async (_url: string, _init?: RequestInit) => new Response(JSON.stringify({ upstreamStatus: 200,
    data: { items: [{ id: 'product-real', name: '조회 상품', version: 7 }], totalPages: 1 } })));
  vi.stubGlobal('fetch', fetch); const run = vi.fn();
  render(<OperationForm operation={operations.find(op => op.id === 'post_api_orders')!} context={{}}
    role="BUYER" token="buyer-session" pending={false} allowed run={run} />);
  fireEvent.change(screen.getByLabelText(/수량.*quantity/), { target: { value: '2' } });
  fireEvent.click(screen.getByRole('button', { name: '상품 목록 불러오기' }));
  const picker = await screen.findByRole('combobox', { name: '상품 선택' });
  await screen.findByRole('option', { name: /조회 상품/ });
  fireEvent.change(picker, { target: { value: 'product-real' } });
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  expect(run.mock.calls[0][0].body).toMatchObject({ productId: 'product-real', quantity: 2, expectedProductVersion: 7, expectedTotalAmount: '' });
  expect(JSON.parse(fetch.mock.calls[0][1]!.body as string).operationId).toBe('get_api_products');
  expect(fetch.mock.calls[0][1]!.headers).toMatchObject({ Authorization: 'Bearer buyer-session' });
});
it('실제 견적의 금액을 연결하고 구매 조건을 바꾸면 이전 예상 금액을 비운다', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ upstreamStatus: 200,
    data: { totalAmount: 24000, productVersion: 4, eventVersion: null } }))));
  const run = vi.fn();
  render(<OperationForm operation={operations.find(op => op.id === 'post_api_orders')!}
    context={{ productId: 'product-1', quantity: 2 }} pending={false} allowed run={run} />);
  fireEvent.click(screen.getByRole('button', { name: '현재 입력으로 견적 조회' }));
  await screen.findByText(/실제 견적의 금액/);
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  expect(run.mock.calls[0][0].body).toMatchObject({ expectedTotalAmount: 24000, expectedProductVersion: 4 });
  fireEvent.change(screen.getByLabelText(/수량.*quantity/), { target: { value: '3' } });
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  expect(run.mock.calls[1][0].body.expectedTotalAmount).toBe('');
});
it('대상 ID가 바뀐 뒤 도착한 이전 대상의 버전을 버린다', async () => {
  let finish!: (value: Response) => void;
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => { finish = resolve; })));
  render(<OperationForm operation={operations.find(op => op.id === 'put_api_sellers_me_events_eventId')!}
    context={{ eventId: 'event-A' }} role="SELLER" pending={false} allowed run={() => {}} />);
  fireEvent.click(screen.getByRole('button', { name: '대상 최신 값 불러오기' }));
  fireEvent.change(screen.getByLabelText(/이벤트 ID.*eventId/), { target: { value: 'event-B' } });
  await act(async () => finish(new Response(JSON.stringify({ upstreamStatus: 200, data: { id: 'event-A', version: 9 } }))));
  await screen.findByText(/조회 중 입력값이 바뀌었습니다/);
  expect((screen.getByLabelText(/확인한 버전/) as HTMLInputElement).value).toBe('');
});
it('목록 재조회가 실패하면 이전 페이지의 항목을 현재 결과로 선택하지 않는다', async () => {
  let count = 0;
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(++count === 1
    ? { upstreamStatus: 200, data: { items: [{ id: 'product-1', name: '이전 상품', version: 1 }], totalPages: 2 } }
    : { upstreamStatus: 401, data: {} }))));
  render(<OperationForm operation={operations.find(op => op.id === 'post_api_orders')!} context={{}} pending={false} allowed run={() => {}} />);
  fireEvent.click(screen.getByRole('button', { name: '상품 목록 불러오기' }));
  await screen.findByRole('option', { name: /이전 상품/ });
  fireEvent.click(screen.getByRole('button', { name: '다음' }));
  await screen.findByText('이 역할로 먼저 로그인하세요.');
  expect(screen.queryByRole('combobox', { name: '상품 선택' })).toBeNull();
  await waitFor(() => expect((screen.getByLabelText(/상품 ID.*productId/) as HTMLInputElement).value).toBe(''));
});
it.each([
  ['post_api_sellers_me_orders_orderId_shipments', 'SELLER', '주문', { order: { id: 'chosen', version: 8, productName: '내 상품' }, deliveryAddress: {} }, 'body'],
  ['post_internal_payment_jobs_jobId_replays', 'OPERATOR', '처리 작업', { id: 'chosen', version: 8, status: 'RETRY' }, 'body'],
  ['post_internal_notification_deliveries_deliveryId_replays', 'OPERATOR', '알림 전달', { notification: { id: 'chosen', status: 'RETRY' }, version: 8 }, 'body'],
  ['delete_api_buyers_me_addresses_addressId', 'BUYER', '배송지', { id: 'chosen', version: 8, recipient: '수령인' }, 'query'],
] as const)('목록 자원 %s의 실제 ID·버전을 해당 요청에 연결한다', async (operationId, role, title, row, location) => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ upstreamStatus: 200, data: { items: [row], totalPages: 1 } }))));
  const run = vi.fn();
  render(<OperationForm operation={operations.find(op => op.id === operationId)!} context={{}} role={role} pending={false} allowed run={run} />);
  fireEvent.click(screen.getByRole('button', { name: `${title} 목록 불러오기` }));
  await screen.findByRole('option', { name: /chosen/ });
  fireEvent.change(screen.getByRole('combobox', { name: `${title} 선택` }), { target: { value: 'chosen' } });
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  expect(Object.values(run.mock.calls[0][0].pathParameters)).toContain('chosen');
  expect(String(run.mock.calls[0][0][location].expectedVersion)).toBe('8');
});
it('동일 작업을 다시 제출할 때 멱등키와 본문을 보존한다', () => {
  const run = vi.fn();
  const operation = operations.find(op => op.path === '/api/orders' && op.method === 'POST')!;
  render(<OperationForm operation={operation} context={{ productId: 'product-1', expectedProductVersion: 3 }}
    pending={false} allowed run={run} />);
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  expect(run.mock.calls[0][0]).toEqual(run.mock.calls[1][0]);
  expect(run.mock.calls[0][0].idempotencyKey.length).toBeGreaterThanOrEqual(8);
});

it('요청 대기 중 입력·목록·견적·키 조작을 막고 응답 뒤 다시 사용할 수 있다', () => {
  const operation = operations.find(op => op.id === 'post_api_orders')!;
  const context = { productId: 'product-1', quantity: 2, expectedTotalAmount: 10000 };
  const run = vi.fn();
  const { rerender } = render(<OperationForm operation={operation} context={context} pending allowed run={run} />);
  const controls = [...screen.getByRole('form', { name: 'API 요청' }).querySelectorAll('input,select,button,textarea')];
  expect(controls.length).toBeGreaterThan(8);
  expect(controls.every(control => control.matches(':disabled'))).toBe(true);
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  expect(run).not.toHaveBeenCalled();

  rerender(<OperationForm operation={operation} context={context} pending={false} allowed run={run} />);
  expect(controls.every(control => !control.matches(':disabled'))).toBe(true);
  expect((screen.getByLabelText(/수량.*quantity/) as HTMLInputElement).value).toBe('2');
});

it('주문 제출 전에 시작한 견적이 늦게 도착해도 보낸 금액·버전·키를 덮어쓰지 않는다', async () => {
  let finish!: (value: Response) => void;
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => { finish = resolve; })));
  const run = vi.fn();
  render(<OperationForm operation={operations.find(op => op.id === 'post_api_orders')!}
    context={{ productId: 'product-1', quantity: 2, expectedProductVersion: 3, expectedTotalAmount: 10000 }}
    pending={false} allowed run={run} />);
  fireEvent.click(screen.getByRole('button', { name: '현재 입력으로 견적 조회' }));
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));

  await act(async () => finish(new Response(JSON.stringify({ upstreamStatus: 200,
    data: { totalAmount: 24000, productVersion: 9, eventVersion: null } }))));
  expect((screen.getByLabelText(/확인한 결제 금액/) as HTMLInputElement).value).toBe('10000');
  expect((screen.getByLabelText(/확인한 상품 버전/) as HTMLInputElement).value).toBe('3');
  fireEvent.submit(screen.getByRole('form', { name: 'API 요청' }));
  expect(run.mock.calls[1][0]).toEqual(run.mock.calls[0][0]);
});
