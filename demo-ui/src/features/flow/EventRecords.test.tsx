// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { Execution } from '../../shared/api/client';
import type { DemoInfo } from '../experiments/Experiments';
import { EventRecords } from './EventRecords.tsx';
import { owners, requestReferences, resultTarget, type EventRecord } from './eventRecords';
import { Field } from '../../shared/ui/SchemaFields';

const ORDER = '10000000-0000-4000-8000-000000000001';
const OTHER = '20000000-0000-4000-8000-000000000002';
const EVENT = 'aaaaaaaa-0000-4000-8000-000000000001';
const AT = '2026-11-05T12:00:00+09:00';
const info: DemoInfo = { gateway: '', operatorServices: [...owners], timeTravel: false, loadEnabled: false, loadProject: '', links: [] };
const record = (id = 'first', order = ORDER): Execution => ({ id, at: AT, operation: '주문 조회', method: 'GET', path: '/api/orders/{orderId}',
  actor: 'BUYER', status: 200, elapsedMs: 10, request: { pathParameters: { orderId: order } }, response: { id: order, buyerId: OTHER } });
const row = (owner: string, kind: 'OUTBOX' | 'INBOX', status: string): Omit<EventRecord, 'owner'> => ({
  kind, id: kind === 'OUTBOX' ? EVENT : owner === 'commerce' ? ORDER : OTHER, eventId: EVENT,
  eventType: owner === 'commerce' && status === 'PENDING' ? 'OrderPaymentRequested' : 'PaymentConfirmed',
  aggregateId: ORDER, status, topic: 'commerce-settle.payment.v1', consumer: kind === 'INBOX' ? `${owner}.v1` : null,
  partition: status === 'PENDING' ? null : 1, offset: status === 'PENDING' ? null : '9007199254740993', attempts: 1,
  lastErrorCode: null, resultRef: OTHER, createdAt: AT, updatedAt: AT, referenceIds: [ORDER],
});
const reply = (records: Omit<EventRecord, 'owner'>[] = [], status = 200, truncated = false) => new Response(JSON.stringify({
  upstreamStatus: status, data: { records, truncated, limit: 50, observedAt: AT },
}));
function mock(fetcher: (owner: string, ids: string[]) => Response | Promise<Response>) {
  const fetch = vi.fn((_url: string, options: RequestInit) => {
    const command = JSON.parse(options.body as string); const owner = command.operationId.match(/get_internal_(\w+)_event_snapshot/)[1];
    return Promise.resolve(fetcher(owner, command.query.ids.split(',')));
  }); vi.stubGlobal('fetch', fetch); return fetch;
}
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.useRealTimers(); });

it('거래 단건만 연결하고 계정·상품·목록의ID로전체거래를조회하지않는다', () => {
  expect(requestReferences(record())).toEqual([ORDER]);
  expect(requestReferences({ ...record(), path: '/api/products', request: {}, response: { id: OTHER, buyerId: ORDER } })).toEqual([]);
  expect(requestReferences({ ...record(), request: {}, response: { items: [{ id: ORDER }] } })).toEqual([]);
});

it('수동조회전과관측후시간경과에추가호출없이서버자격중계로실제기록을연결한다', async () => {
  const fetch = mock(owner => reply(owner === 'commerce' ? [row('commerce', 'OUTBOX', 'PENDING')] : []));
  const onSelect = vi.fn(); render(<EventRecords record={record()} info={info} onSelectEvent={onSelect} />);
  expect(fetch).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '실제 이벤트 기록 조회' }));
  await screen.findByText('PENDING');
  expect(document.body.textContent).toContain(EVENT); expect(onSelect).toHaveBeenCalledWith('OrderPaymentRequested');
  expect(fetch).toHaveBeenCalledTimes(6); // seed → event/resultRef의 두 차례 병렬 관측.
  for (const [, options] of fetch.mock.calls) {
    expect(options.headers).not.toHaveProperty('Authorization');
    expect(JSON.parse(options.body as string).query.ids.split(',').length).toBeLessThanOrEqual(20);
  }
  vi.useFakeTimers(); act(() => vi.advanceTimersByTime(60_000)); expect(fetch).toHaveBeenCalledTimes(6);
});

it('같은eventId의여러소비자를상태·위치별로보여주고APPLIED를금융성공으로바꾸지않는다', async () => {
  mock(owner => reply([owner === 'payment' ? row(owner, 'OUTBOX', 'PUBLISHED') : { ...row(owner, 'INBOX', owner === 'commerce' ? 'APPLIED' : 'WAITING'), offset: owner === 'commerce' ? 19 : 21 }]));
  render(<EventRecords record={record()} info={info} onSelectEvent={() => {}} />);
  fireEvent.click(screen.getByRole('button', { name: '실제 이벤트 기록 조회' }));
  await screen.findByText('APPLIED'); expect(screen.getByText('WAITING')).toBeTruthy();
  expect(document.body.textContent).toContain('commerce.v1'); expect(document.body.textContent).toContain('settlement.v1');
  expect(document.body.textContent).toContain('9007199254740993'); expect(document.body.textContent).toContain('offset 19');
  expect(document.body.textContent).toContain('최종 성공은 해당 거래 조회');
});

it('실패한서비스를자동재시도하거나미수신으로바꾸지않고다른서비스관측을유지한다', async () => {
  const fetch = mock(owner => reply(owner === 'commerce' ? [row(owner, 'OUTBOX', 'PENDING')] : [], owner === 'payment' ? 503 : 200));
  render(<EventRecords record={record()} info={info} onSelectEvent={() => {}} />);
  fireEvent.click(screen.getByRole('button', { name: '실제 이벤트 기록 조회' }));
  await screen.findByText('PENDING'); expect(screen.getByText('payment 조회 실패')).toBeTruthy();
  expect(fetch.mock.calls.filter(([, options]) => (options.body as string).includes('get_internal_payment_'))).toHaveLength(1);
});

it('설정미확인과설정없음을구분하고설정도착시사용자ID입력을보존한다', async () => {
  const fetch = mock(() => reply());
  const { rerender } = render(<EventRecords record={record()} onSelectEvent={() => {}} />);
  expect(screen.getByRole('button', { name: '실제 이벤트 기록 조회' }).hasAttribute('disabled')).toBe(true);
  fireEvent.change(screen.getByLabelText('연결할 거래·이벤트 ID'), { target: { value: OTHER } });
  rerender(<EventRecords record={record()} info={{ ...info, operatorServices: ['commerce'] }} onSelectEvent={() => {}} />);
  expect((screen.getByLabelText('연결할 거래·이벤트 ID') as HTMLInputElement).value).toBe(OTHER);
  fireEvent.click(screen.getByRole('button', { name: '실제 이벤트 기록 조회' }));
  await screen.findByText('payment 설정 없음'); expect(fetch).toHaveBeenCalledTimes(1);
  expect(screen.getByText('settlement 설정 없음')).toBeTruthy();
});

it.each(['', 'not-a-uuid', Array.from({ length: 21 }, (_, n) => `10000000-0000-4000-8000-${String(n).padStart(12, '0')}`).join(',')])('잘못된ID목록은호출전에거절한다: %s', async input => {
  const fetch = mock(() => reply()); render(<EventRecords record={record()} info={info} onSelectEvent={() => {}} />);
  fireEvent.change(screen.getByLabelText('연결할 거래·이벤트 ID'), { target: { value: input } });
  fireEvent.click(screen.getByRole('button', { name: '실제 이벤트 기록 조회' }));
  expect(await screen.findByRole('alert')).toBeTruthy(); expect(fetch).not.toHaveBeenCalled();
});

it('선택기록이바뀐뒤도착한이전응답을버리고네트워크대기를취소한다', async () => {
  const finishes: ((r: Response) => void)[] = [];
  const fetch = vi.fn((_url, _options) => new Promise<Response>(resolve => finishes.push(resolve))); vi.stubGlobal('fetch', fetch);
  const { rerender } = render(<EventRecords record={record()} info={info} onSelectEvent={() => {}} />);
  fireEvent.click(screen.getByRole('button', { name: '실제 이벤트 기록 조회' }));
  expect(fetch).toHaveBeenCalledTimes(3);
  rerender(<EventRecords record={record('second', OTHER)} info={info} onSelectEvent={() => {}} />);
  expect(fetch.mock.calls[0][1].signal.aborted).toBe(true);
  await act(async () => finishes.forEach(finish => finish(reply([row('commerce', 'OUTBOX', 'PENDING')]))));
  expect(document.body.textContent).not.toContain(EVENT);
  expect((screen.getByLabelText('연결할 거래·이벤트 ID') as HTMLInputElement).value).toBe(OTHER);
});

it('참조확장을3라운드9호출로제한하고미조회참조를명시한다', async () => {
  let calls = 0;
  const fetch = mock(owner => {
    const round = Math.floor(calls++ / 3);
    const reference = `50000000-0000-4000-8000-${String(round + 1).padStart(12, '0')}`;
    return reply([{ ...row(owner, owner === 'payment' ? 'OUTBOX' : 'INBOX', 'WAITING'), referenceIds: [reference] }]);
  });
  render(<EventRecords record={record()} info={info} onSelectEvent={() => {}} />);
  fireEvent.click(screen.getByRole('button', { name: '실제 이벤트 기록 조회' }));
  await screen.findByText(/연결 조회 상한에 도달/); expect(fetch).toHaveBeenCalledTimes(9);
  expect(screen.queryByText('이 관측에서 수신 기록 없음')).toBeNull();
});

it('서버행잘림을전달완료나전체목록으로표현하지않는다', async () => {
  mock(() => reply([], 200, true)); render(<EventRecords record={record()} info={info} onSelectEvent={() => {}} />);
  fireEvent.click(screen.getByRole('button', { name: '실제 이벤트 기록 조회' }));
  await screen.findByText(/연결 조회 상한에 도달/);
  expect(screen.getAllByText('50개 상한 · 일부 기록 생략')).toHaveLength(3);
});

it('Inbox결과ID를기존거래조회로연결하고알림ID를주문ID로오인하지않는다', async () => {
  mock(owner => reply(owner === 'payment' ? [{ ...row(owner, 'INBOX', 'APPLIED'), eventType: 'OrderPaymentRequested' }] : []));
  const inspect = vi.fn(); render(<EventRecords record={record()} info={info} onSelectEvent={() => {}} onInspectResult={inspect} />);
  fireEvent.click(screen.getByRole('button', { name: '실제 이벤트 기록 조회' }));
  fireEvent.click(await screen.findByRole('button', { name: '구매자 결제 조회로 이동' }));
  expect(inspect).toHaveBeenCalledWith('payment', OTHER);
});

it.each(['RefundApproved', 'RefundOrderRestored', 'RefundReservationResult', 'RefundLedgerReversed', 'RefundReservationReleased'])(
  '환불 Inbox %s의 결과를 결제 ID로 연결하지 않는다', async eventType => {
    mock(owner => reply(owner === 'payment' ? [{ ...row(owner, 'INBOX', 'APPLIED'), eventType }] : []));
    const inspect = vi.fn(); render(<EventRecords record={record()} info={info} onSelectEvent={() => {}} onInspectResult={inspect} />);
    fireEvent.click(screen.getByRole('button', { name: '실제 이벤트 기록 조회' }));
    fireEvent.click(await screen.findByRole('button', { name: '구매자 환불 조회로 이동' }));
    expect(inspect).toHaveBeenCalledWith('refund', OTHER);
    expect(screen.queryByRole('button', { name: '구매자 결제 조회로 이동' })).toBeNull();
  });

it('이벤트와 소비 그룹으로 결과 종류를 확인하고 예약·알림·알 수 없는 참조는 단정하지 않는다', () => {
  const result = (owner: EventRecord['owner'], eventType: string, consumer = `${owner}.v1`) =>
    resultTarget({ ...row(owner, 'INBOX', 'APPLIED'), owner, eventType, consumer });
  for (const eventType of ['PaymentAccepted', 'PaymentCancellationRejected', 'PaymentConfirmed', 'PaymentDeclined', 'PaymentCancelled',
    'RefundAccepted', 'RefundCompleted', 'RefundFailed']) expect(result('commerce', eventType)).toBe('order');
  for (const eventType of ['OrderPaymentRequested', 'PaymentCancellationRequested']) expect(result('payment', eventType)).toBe('payment');
  for (const eventType of ['PaymentConfirmed', 'PurchaseConfirmed']) expect(result('settlement', eventType)).toBe('settlement-item');
  for (const eventType of ['RefundReservationRequested', 'RefundFinancialCompleted', 'RefundReleaseRequested', 'Unknown'])
    expect(result('settlement', eventType)).toBeUndefined();
  expect(result('commerce', 'PaymentNoticeRequested', 'commerce-notifications.v1')).toBeUndefined();
  expect(result('payment', 'Unknown')).toBeUndefined();
  expect(resultTarget({ ...row('payment', 'OUTBOX', 'PUBLISHED'), owner: 'payment', eventType: 'OrderPaymentRequested' })).toBeUndefined();
});

it('카드는합성참조를정하고간편결제는준비된지갑을쓰도록안내한다', () => {
  render(<Field name="methodAccountRef" schema={{ type: 'string' }} value="" change={() => {}} />);
  expect(screen.getByText(/신용·체크카드는 시연용 참조값/)).toBeTruthy();
  expect(screen.getByText(/간편결제는 모의 금융 서버에 준비한 지갑/)).toBeTruthy();
});
