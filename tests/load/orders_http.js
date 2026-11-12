// order_load.py가 공개 API로 준비한 주문을 보낸다. Node.js가 아닌 k6에서 실행한다.
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

const body = open('/work/order.json');
const rate = Number(__ENV.RATE);
const seconds = Number(__ENV.DURATION_SECONDS);
const preallocated = Number(__ENV.PRE_ALLOCATED_VUS);
const maximum = Number(__ENV.MAX_VUS);
if (!__ENV.BASE_URL || !__ENV.BUYER_TOKEN || !__ENV.RUN_ID
    || !Number.isInteger(rate) || rate < 1 || rate > 200
    || !Number.isInteger(seconds) || seconds < 1 || seconds > 60
    || !Number.isInteger(preallocated) || preallocated < 1
    || !Number.isInteger(maximum) || maximum < preallocated) {
  throw new Error('order_load.py로 대상·세션·부하 조건을 준비하세요.');
}

export const options = {
  scenarios: {
    orders: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration: String(seconds) + 's',
      preAllocatedVUs: preallocated,
      maxVUs: maximum,
      gracefulStop: '6s',
    },
  },
  summaryTrendStats: ['avg', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    dropped_iterations: ['count==0'],
  },
};

export default function () {
  const iteration = exec.scenario.iterationInTest;
  // 짧은 실행의 종료 경계에서 k6가 반복을 하나 더 예약해도 준비 수량을 넘기지 않는다.
  if (iteration >= rate * seconds) return;
  const startedAt = new Date().toISOString();
  console.log('ORDER_START ' + JSON.stringify({ iteration, startedAt }));
  const response = http.post(__ENV.BASE_URL + '/api/orders', body, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: 'Bearer ' + __ENV.BUYER_TOKEN,
      'Idempotency-Key': __ENV.RUN_ID + '-load-' + iteration,
    },
    tags: { name: 'POST /api/orders' },
    timeout: '5s',
    redirects: 0,
  });
  let value = {};
  try { value = response.json() || {}; } catch (_) { /* 유실·비JSON도 원장에 남긴다. */ }
  const id = typeof value.id === 'string' && /^[0-9a-f-]{36}$/i.test(value.id) ? value.id : null;
  const code = typeof value.code === 'string' && /^[A-Z0-9_]+$/.test(value.code) ? value.code : null;
  const traceId = typeof value.traceId === 'string' && /^[0-9a-f]{16,32}$/i.test(value.traceId)
    ? value.traceId : null;
  check(response, { '주문 접수 201과 주문 ID': (r) => r.status === 201 && id !== null });
  // 토큰·요청/응답 원문·멱등키 원문은 출력하지 않는다. 키는 runId와 iteration으로 복원한다.
  console.log('ORDER ' + JSON.stringify({
    iteration, startedAt, status: response.status, orderId: id, code, traceId,
    durationMs: response.timings.duration,
  }));
}
