// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { operations } from '../../shared/api/contract';
import { OperationForm } from './OperationForm';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
it('다른 상품의 버전을 이벤트 수정의 버전으로 사용하지 않는다', () => {
  const operation = operations.find(op => op.path === '/api/sellers/me/events/{eventId}' && op.method === 'PUT')!;
  render(<OperationForm operation={operation} context={{ productIdVersion: 7, eventId: 'event-1', eventIdVersion: 3 }}
    pending={false} allowed run={() => {}} />);
  expect((screen.getByLabelText(/확인한 버전/) as HTMLInputElement).value).toBe('3');
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
