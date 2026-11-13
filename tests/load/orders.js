import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

// order_load.py --prepare-only가 만든 order.json을 ORDER_FILE로 지정할 수 있다.
const body = open(__ENV.ORDER_FILE || './order.json');
let order;
try {
  order = JSON.parse(body);
} catch (_) {
  throw new Error('ORDER_FILE에 유효한 주문 JSON 파일을 지정하세요.');
}
const required = [
  'productId', 'quantity', 'eventId', 'buyerCouponId', 'mileageAmount', 'addressId',
  'expectedProductVersion', 'expectedEventVersion', 'expectedTotalAmount',
  'externalMethod', 'methodAccountRef',
];
if (!order || Array.isArray(order) || typeof order !== 'object'
    || required.some((field) => !Object.prototype.hasOwnProperty.call(order, field))
    || !order.productId || !order.addressId || !Number.isInteger(order.quantity)
    || order.quantity < 1 || !Number.isInteger(order.expectedTotalAmount)
    || order.expectedTotalAmount < 1) {
  throw new Error('ORDER_FILE에는 로그인 본문이 아닌 견적·주소를 반영한 주문 본문이 필요합니다.');
}

const baseUrl = (__ENV.BASE_URL || '').replace(/\/+$/, '');
const rate = Number(__ENV.RATE || 200);
const seconds = Number(__ENV.DURATION_SECONDS || 60);
const phase = __ENV.LOAD_PHASE || 'measurement';
const preallocated = Number(__ENV.PRE_ALLOCATED_VUS || Math.max(400, rate * 2));
const maximum = Number(__ENV.MAX_VUS || Math.max(1200, rate * 6));
const diagnosticEvery = Number(__ENV.DIAGNOSTIC_EVERY || 0);
if (!/^https?:\/\//.test(baseUrl) || !__ENV.BUYER_TOKEN || !__ENV.RUN_ID
    || !Number.isInteger(rate) || rate < 1 || rate > 1000
    || !Number.isInteger(seconds) || seconds < 1 || seconds > 900
    || !['warmup', 'measurement'].includes(phase)
    || !Number.isInteger(preallocated) || preallocated < 1
    || !Number.isInteger(maximum) || maximum < preallocated || maximum > 10000
    || !Number.isInteger(diagnosticEvery) || diagnosticEvery < 0 || diagnosticEvery > 100000
    || (diagnosticEvery > 0 && !/^[a-f0-9]{32}$/.test(__ENV.RUN_ID))) {
  throw new Error('BASE_URL·BUYER_TOKEN·RUN_ID·LOAD_PHASE와 rate=1..1000, seconds=1..900, VU=1..10000을 확인하세요.');
}

export const options = {
  scenarios: {
    orders: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration: `${seconds}s`,
      preAllocatedVUs: preallocated,
      maxVUs: maximum,
      gracefulStop: '6s',
    },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    dropped_iterations: ['count==0'],
  },
};

export default function () {
  const iteration = exec.scenario.iterationInTest;
  if (iteration >= rate * seconds) return;
  if (iteration === 0) {
    console.log('ORDER_PHASE ' + JSON.stringify({
      phase,
      measurementStart: new Date(exec.scenario.startTime).toISOString(),
      measurementEnd: new Date(exec.scenario.startTime + seconds * 1000).toISOString(),
    }));
  }
  const startedAt = new Date().toISOString();
  const traceSampled = diagnosticEvery > 0 && iteration % diagnosticEvery === 0;
  const injectedTraceId = traceSampled
    ? __ENV.RUN_ID.slice(0, 16) + (phase === 'measurement' ? '00000002' : '00000001')
      + iteration.toString(16).padStart(8, '0') : null;
  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${__ENV.BUYER_TOKEN}`,
    'Idempotency-Key': `${__ENV.RUN_ID}-${phase === 'measurement' ? 'load' : 'warmup'}-${iteration}`,
  };
  if (traceSampled) {
    headers.traceparent = `00-${injectedTraceId}-${(iteration + 1).toString(16).padStart(16, '0')}-01`;
  }
  console.log('ORDER_START ' + JSON.stringify({ phase, iteration, startedAt,
    traceSampled, traceId: injectedTraceId }));
  const response = http.post(`${baseUrl}/api/orders`, body, {
    headers,
    tags: { name: 'POST /api/orders' },
    timeout: '5s',
    redirects: 0,
  });

  let value = {};
  try { value = response.json() || {}; } catch (_) { /* 유실·비JSON도 결과에 남긴다. */ }
  const id = typeof value.id === 'string' && /^[0-9a-f-]{36}$/i.test(value.id) ? value.id : null;
  const code = typeof value.code === 'string' && /^[A-Z0-9_]+$/.test(value.code) ? value.code : null;
  const traceId = injectedTraceId || (typeof value.traceId === 'string' && /^[0-9a-f]{16,32}$/i.test(value.traceId)
    ? value.traceId : null);
  check(response, {
    '주문 접수 201과 주문 ID': (r) => r.status === 201 && id !== null,
  });
  console.log('ORDER ' + JSON.stringify({
    phase, iteration, startedAt, completedAt: new Date().toISOString(),
    status: response.status, orderId: id, code, traceId, traceSampled,
    clientErrorCode: response.error_code || null,
    clientError: response.error
      ? String(response.error).split(__ENV.BUYER_TOKEN).join('<redacted>')
        .replace(/https?:\/\/\S+/g, '<url>').replace(/[\r\n]/g, ' ').slice(0, 200)
      : null,
    durationMs: response.timings.duration,
    blockedMs: response.timings.blocked,
    connectingMs: response.timings.connecting,
    tlsMs: response.timings.tls_handshaking,
    waitingMs: response.timings.waiting,
    receivingMs: response.timings.receiving,
  }));
}
