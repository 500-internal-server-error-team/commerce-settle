import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { execute, journal, parseResponse, RequestInputError } from './client';
import { format } from '../../features/results/ResultView';
import { operations } from './contract';

const operation = operations.find(op => op.id === 'get_api_products')!;
const values = { pathParameters: {}, query: {}, body: { password: 'do-not-retain' }, idempotencyKey: 'example-key' };
beforeEach(() => vi.stubEnv('DEV', false));
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.restoreAllMocks(); });

describe('요청 결과 보존', () => {
  it('Long 범위의 예산을 반올림하지 않고 표시·기록한다', () => {
    const response = parseResponse(String.raw`{"totalBudget":9007199254740993,"note":"9007199254740993 \"quoted\"","amount":10000}`) as Record<string, never>;
    expect(response.totalBudget).toBe('9007199254740993');
    expect(response.amount).toBe(10000);
    expect(response.note).toBe('9007199254740993 "quoted"');
    expect(format('totalBudget', response.totalBudget)).toBe('9,007,199,254,740,993원');
  });
  it('기록 객체 어디에도 원본 토큰과 비밀번호를 남기지 않는다', () => {
    const record = journal(operation, values, 'BUYER', {
      status: 200, elapsedMs: 12, data: { token: 'private-session', account: { id: 'buyer' } },
    });
    expect(JSON.stringify(record)).not.toContain('private-session');
    expect(JSON.stringify(record)).not.toContain('do-not-retain');
    expect(record.response).toEqual({ token: '••••••', account: { id: 'buyer' } });
    expect(record).not.toHaveProperty('data');
  });
  it('본문 없는 성공 응답을 제어 서버 래퍼로 바꾸지 않는다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ upstreamStatus: 204, data: null }))));
    const result = await execute(operation, values, undefined);
    expect(result.status).toBe(204);
    expect(result.data).toBeNull();
  });
});

it('direct-wire', async () => {
  vi.stubEnv('DEV',true);
  const fetch = vi.fn(async () => new Response('{"id":"product-1"}')); vi.stubGlobal('fetch',fetch);
  const baseOperation = operations.find(op=>op.id==='put_api_sellers_me_products_productId')!;
  const op={...baseOperation,parameters:[...baseOperation.parameters,{name:'search',in:'query',schema:{type:'string'}}]};
  const signal=new AbortController().signal;
  const values={body:{name:'한글 상품',unitPrice:1200},pathParameters:{productId:'product-1'},query:{search:'한글 & = /'},idempotencyKey:'fixed-safe-key'};
  await execute(op,values,'synthetic-token',signal);
  expect(fetch.mock.calls[0]).toEqual(['/api/sellers/me/products/product-1?search=%ED%95%9C%EA%B8%80+%26+%3D+%2F',{
    method:'PUT',signal,headers:{'Content-Type':'application/json',Authorization:'Bearer synthetic-token','Idempotency-Key':'fixed-safe-key'},body:JSON.stringify(values.body)}]);
});
it('direct-empty', async () => {
  vi.stubEnv('DEV',true);vi.stubGlobal('fetch',vi.fn(async()=>new Response(null,{status:204})));
  const result=await execute(operations.find(op=>op.id==='delete_api_sessions_current')!,{body:null,pathParameters:{},query:{},idempotencyKey:''},'synthetic-token');
  expect(result.status).toBe(204);expect(result.data).toBeNull();
});
it('whole-body-time', async () => {
  vi.stubEnv('DEV',true);let finish!:(body:string)=>void;let time=10;let done=false;
  vi.spyOn(performance,'now').mockImplementation(()=>time);
  vi.stubGlobal('fetch',vi.fn(async()=>({status:200,text:()=>new Promise<string>(resolve=>finish=resolve)})));
  const pending=execute(operations.find(op=>op.id==='get_api_products')!,{body:null,pathParameters:{},query:{},idempotencyKey:''},undefined).then(value=>{done=true;return value;});
  await Promise.resolve();await Promise.resolve();expect(done).toBe(false);time=260;finish('{"items":[]}');
  expect((await pending).elapsedMs).toBe(250);
});

it('개발 모드에서도 운영자 요청은 서버 자격을 쓰는 기존 중계로만 보낸다',async()=>{
  vi.stubEnv('DEV',true);const fetch=vi.fn(async(_url:string,_init?:RequestInit)=>new Response('{"upstreamStatus":200,"data":{"items":[]}}'));vi.stubGlobal('fetch',fetch);
  const op=operations.find(op=>op.id==='get_internal_coupon_issue_runs')!;
  const args={body:null,pathParameters:{},query:{},idempotencyKey:''};
  const result=await execute(op,args,undefined);
  expect(fetch.mock.calls[0][0]).toBe('/demo/requests');expect(JSON.parse(String(fetch.mock.calls[0][1]?.body))).toEqual({operationId:op.id,...args});
  expect(result.data).toEqual({items:[]});
});
it('직접 응답의 문제 본문·큰 정수·래퍼처럼 보이는 필드를 그대로 보존한다',async()=>{
  vi.stubEnv('DEV',true);const fetch=vi.fn().mockResolvedValueOnce(new Response('{"code":"CONFLICT","detail":"최신 버전을 확인하세요."}',{status:409}))
    .mockResolvedValueOnce(new Response('{"upstreamStatus":202,"data":{"totalBudget":9007199254740993}}'));
  vi.stubGlobal('fetch',fetch);const args={body:null,pathParameters:{},query:{},idempotencyKey:''};
  expect(await execute(operation,args,undefined)).toMatchObject({status:409,data:{code:'CONFLICT',detail:'최신 버전을 확인하세요.'}});
  expect((await execute(operation,args,undefined)).data).toEqual({upstreamStatus:202,data:{totalBudget:'9007199254740993'}});
});
it.each([{productId:'../internal'},{wrong:'product-1'},{}] as Record<string,string>[])('잘못된 직접 경로 입력은 전송 전에 거절한다: %j',async(pathParameters)=>{
  vi.stubEnv('DEV',true);const fetch=vi.fn();vi.stubGlobal('fetch',fetch);
  await expect(execute(operations.find(op=>op.id==='get_api_products_productId')!,{body:null,pathParameters,query:{},idempotencyKey:''},undefined)).rejects.toBeInstanceOf(RequestInputError);
  expect(fetch).not.toHaveBeenCalled();
});
