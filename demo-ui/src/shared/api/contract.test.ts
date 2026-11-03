import { describe, expect, it } from 'vitest';
import { defaults, operations, redact } from './contract';
import { actions, scenarios } from '../../scenarios/catalog';

describe('API와 시연 연결', () => {
  it('와이어프레임의 49개 분기를 고유하게 찾고 각 영역에 실제 작업을 제공한다', () => {
    expect(scenarios).toHaveLength(49);
    expect(new Set(scenarios.map(s => s.id)).size).toBe(49);
    for (const group of new Set(scenarios.map(s => s.group))) expect(actions(group).length).toBeGreaterThan(0);
    expect(actions('monthly').some(o => o.path.endsWith('/targets'))).toBe(true);
    expect(actions('event').some(o => o.path.endsWith('/publication'))).toBe(true);
  });
  it('서비스 전용 금융 API를 브라우저 작업 목록에 넣지 않는다', () => {
    expect(operations.every(o => o.path.startsWith('/api/') || o.actor === 'OPERATOR')).toBe(true);
    expect(operations.some(o => o.path.includes('/payment-context'))).toBe(false);
  });
  it('중첩된 요청·응답의 비밀번호와 토큰을 기록에서 제거한다', () => {
    expect(redact({ password: 'secret', account: { id: 'buyer' }, result: [{ token: 'session', amount: 100 }] }))
      .toEqual({ password: '••••••', account: { id: 'buyer' }, result: [{ token: '••••••', amount: 100 }] });
  });
  it('서버가 판단할 금액이나 버전을 성공값으로 만들지 않는다', () => {
    expect(defaults({ type: 'object', properties: { expectedVersion: { type: 'integer' }, coupon: { anyOf: [{ type: 'string' }, { type: 'null' }] } } }))
      .toEqual({ expectedVersion: '', coupon: null });
  });
});
