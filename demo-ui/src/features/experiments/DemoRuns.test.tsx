// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, expect, it, vi } from 'vitest';
import { DemoRuns } from './DemoRuns';

let client: QueryClient;
afterEach(() => { cleanup(); client.clear(); vi.unstubAllGlobals(); });
const id='abcdef00-0000-4000-8000-000000000001';
const run={id,sellerId:id,productId:id,eventId:null,deliverySeconds:5,refundSeconds:10,frozenAt:'2026-11-14T10:00:00+09:00',orderIds:[id],payableOrderIds:[id],refundedOrderIds:[],pendingCount:0,excludedCount:0};
function setup(enabled=true) {
  const fetch=vi.fn(async (_url: unknown,init?: RequestInit) => {
    const body=JSON.parse(String(init?.body));
    const data=body.operationId.includes('capabilities')?{enabled}:body.operationId==='get_internal_demo_settlement_run'?null:run;
    return new Response(JSON.stringify({upstreamStatus:data===null?404:200,data}));
  });
  vi.stubGlobal('fetch',fetch);
  client=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});
  render(<QueryClientProvider client={client}><DemoRuns services={['commerce','settlement']}/></QueryClientProvider>);
  return fetch;
}
it('비활성 서버에서는 시연 생성과 조회를 막는다', async () => {
  setup(false);
  await screen.findByText('전용 환경에서 Commerce·Settlement의 시연 모드를 켜야 합니다.');
  expect((screen.getByRole('button',{name:'시연 회차 만들기'}) as HTMLButtonElement).disabled).toBe(true);
});
it('생성 응답을 잃어도 같은 옵션·멱등키로 재조회하고 생성 뒤 옵션을 잠근다',async()=>{
  const fetch=setup();
  await waitFor(()=>expect((screen.getByRole('button',{name:'시연 회차 만들기'}) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.change(screen.getByLabelText('판매자 ID'),{target:{value:id.toUpperCase()}});
  fireEvent.change(screen.getByLabelText('상품 ID'),{target:{value:id}});
  expect((screen.getByRole('form',{name:'시연 회차 생성'}) as HTMLFormElement).checkValidity()).toBe(true);
  const original=fetch.getMockImplementation()!;let first=true;
  fetch.mockImplementation(async(url,init)=>{if(JSON.parse(String(init?.body)).operationId==='post_internal_demo_runs' && first){first=false;throw new Error('연결 끊김');}return original(url,init);});
  fireEvent.submit(screen.getByRole('form',{name:'시연 회차 생성'}));
  await screen.findByRole('alert');
  expect((screen.getByLabelText('배송 대기 (초)') as HTMLInputElement).disabled).toBe(false);
  expect((screen.getByLabelText('배송 대기 (초)').closest('fieldset') as HTMLFieldSetElement).disabled).toBe(true);
  fireEvent.click(screen.getByRole('button',{name:'같은 회차 생성 요청 다시 확인'}));
  await screen.findByText('배송 5초 · 환불 10초');
  const calls=fetch.mock.calls.map(([,init])=>JSON.parse(String(init?.body))).filter(c=>c.operationId==='post_internal_demo_runs');
  expect(calls).toHaveLength(2);expect(calls[0]).toEqual(calls[1]);
});
it('50건 페이지의 cursor를 이어서 보내고 같은 처리 요청 키를 유지한다',async()=>{
  const fetch=setup();const original=fetch.getMockImplementation()!;let page=0;
  fetch.mockImplementation(async(url,init)=>{
    if(JSON.parse(String(init?.body)).operationId==='post_internal_demo_run_processing')return new Response(JSON.stringify({upstreamStatus:200,data:{nextCursor:page++===0?id:null,processedCount:50,waitingCount:0}}));
    return original(url,init);
  });
  await waitFor(()=>expect((screen.getByRole('button',{name:'회차 불러오기'}) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.change(screen.getByLabelText('기존 회차 ID'),{target:{value:id}});
  fireEvent.submit(screen.getByRole('form',{name:'기존 시연 회차 조회'}));
  fireEvent.click(await screen.findByRole('button',{name:'도래한 기한 처리'}));
  await waitFor(()=>expect(page).toBe(2));
  const calls=fetch.mock.calls.map(([,init])=>JSON.parse(String(init?.body))).filter(c=>c.operationId==='post_internal_demo_run_processing');
  expect(calls[0].query).toEqual({});expect(calls[1].query).toEqual({after:id});expect(calls[0].idempotencyKey).toBe(calls[1].idempotencyKey);
});
