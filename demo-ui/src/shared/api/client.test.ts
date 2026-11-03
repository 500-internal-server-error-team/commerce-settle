import { afterEach, describe, expect, it, vi } from 'vitest';
import { execute, journal, parseResponse } from './client';
import { format } from '../../features/results/ResultView';
import { operations } from './contract';

const operation = operations.find(op => op.id === 'get_api_products')!;
const values = { pathParameters: {}, query: {}, body: { password: 'do-not-retain' }, idempotencyKey: 'example-key' };
afterEach(() => vi.unstubAllGlobals());

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
