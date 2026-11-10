// 선착순 이벤트 집중 부하. event_load.py가 시험 스택·구매자·세션·이벤트를 준비하고 이 스크립트를 실행한다.
//   MODE=seed  구매자마다 주소를 하나씩 API로 등록한다(주소는 주소 ID로 암호화되므로 SQL로 복제할 수 없다).
//   MODE=warmup 이벤트 공개 전 WARMUP_SECONDS 동안 WARMUP_RATE로 예열 구매자(buyers.json 끝 WARMUP_BUYERS명)가 일반 상품을 주문한다.
//   MODE=flash 첫 PEAK_SECONDS 동안 PEAK_RATE, 이후 TAIL_RATE로 서로 다른 구매자의 주문을 한 번씩 보낸다.
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const base = __ENV.BASE_URL || 'http://gateway:8080';
const mode = __ENV.MODE || 'flash';
const buyers = new SharedArray('buyers', () => JSON.parse(open('/work/buyers.json')));
const plan = JSON.parse(open('/work/plan.json'));

function integer(name, fallback) {
  const value = Number(__ENV[name] || fallback);
  if (!Number.isSafeInteger(value) || value < 0) throw new Error(`${name} must be a non-negative integer`);
  return value;
}

const peakSeconds = integer('PEAK_SECONDS', '5');
const peakRate = integer('PEAK_RATE', '0');
const tailSeconds = integer('TAIL_SECONDS', '0');
const tailRate = integer('TAIL_RATE', '0');
const gaugeRate = integer('GAUGE_RATE', '0');
const maxVUs = integer('MAX_VUS', '1000');
const warmupSeconds = integer('WARMUP_SECONDS', '0');
const warmupRate = integer('WARMUP_RATE', '0');
const warmupBuyers = integer('WARMUP_BUYERS', '0');
const flashBuyers = buyers.length - warmupBuyers;
const peakCount = peakSeconds * peakRate;
if (peakCount + tailSeconds * tailRate > flashBuyers) throw new Error('more orders than prepared buyers');

const outcomes = {};
for (const name of ['created', 'sold_out', 'admission_closed', 'capacity', 'conflict', 'client_error', 'server_error', 'timeout']) {
  outcomes[name] = new Counter(`order_${name}`);
}
const eventLatency = new Trend('order_event_duration', true);
const normalLatency = new Trend('order_normal_duration', true);

function arrival(rate, seconds, startTime, fn) {
  return {
    executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: `${seconds}s`, startTime,
    preAllocatedVUs: Math.min(maxVUs, Math.max(10, rate)), maxVUs, exec: fn,
  };
}

const scenarios = {};
if (mode === 'seed') {
  scenarios.seed = {
    executor: 'shared-iterations', vus: integer('SEED_VUS', '50'), iterations: buyers.length, maxDuration: '30m', exec: 'seed',
  };
} else if (mode === 'warmup') {
  if (!warmupSeconds || !warmupRate || !warmupBuyers) throw new Error('warm-up needs WARMUP_SECONDS, WARMUP_RATE and WARMUP_BUYERS');
  scenarios.warmup = arrival(warmupRate, warmupSeconds, '0s', 'warmup');
} else {
  if (peakRate) scenarios.peak = arrival(peakRate, peakSeconds, '0s', 'peak');
  if (tailRate && tailSeconds) scenarios.tail = arrival(tailRate, tailSeconds, `${peakSeconds}s`, 'tail');
  if (gaugeRate) scenarios.gauge = arrival(gaugeRate, peakSeconds + tailSeconds, '0s', 'gauge');
}

// 항상 참인 기준을 걸어 결과별 응답 시간을 요약에 따로 남긴다. 생성은 매진 전, 이벤트 마감 거절은 대부분 매진 뒤 구간이다.
const thresholds = {};
for (const metric of ['order_event_duration', 'order_normal_duration']) {
  for (const outcome of Object.keys(outcomes)) thresholds[`${metric}{outcome:${outcome}}`] = ['max>=0'];
}

export const options = { scenarios, thresholds, summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'] };

function headers(token) {
  return {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${token}`,
    'Idempotency-Key': `${exec.scenario.name}-${exec.vu.idInTest}-${exec.scenario.iterationInTest}-${Date.now()}`,
  };
}

export function seed() {
  const [token] = buyers[exec.scenario.iterationInTest];
  const body = { recipient: 'load', phone: '010-0000-0000', postalCode: '00000', addressLine1: 'load', addressLine2: '', isDefault: true };
  const response = http.post(`${base}/api/buyers/me/addresses`, JSON.stringify(body), { headers: headers(token), timeout: '30s', tags: { name: 'seed-address' } });
  check(response, { 'address created': (r) => r.status === 201 });
}

function code(response) {
  try {
    return response.json('code');
  } catch (_) {
    return null;
  }
}

function classify(response) {
  if (response.status === 201) return 'created';
  if (response.status === 0) return 'timeout';
  const reason = code(response);
  if (reason === 'INVENTORY_INSUFFICIENT') return 'sold_out';
  if (reason === 'EVENT_ADMISSION_CLOSED') return 'admission_closed';
  if (reason === 'COMMON_CAPACITY_EXCEEDED') return 'capacity';
  if (response.status === 409) return 'conflict';
  if (response.status >= 500) return 'server_error';
  return 'client_error';
}

// 집중 구간에서는 10명 중 1명이 일반 상품, 나머지는 이벤트 상품을 주문한다.
function order(index, normal = index % 10 === 9) {
  const [token, addressId] = buyers[index];
  const target = normal ? plan.normal : plan.event;
  const body = {
    productId: target.productId, quantity: 1, eventId: normal ? null : plan.event.eventId, buyerCouponId: null,
    mileageAmount: 0, addressId, expectedProductVersion: target.productVersion,
    expectedEventVersion: normal ? null : plan.event.eventVersion, expectedTotalAmount: target.price,
    externalMethod: 'CREDIT_CARD', methodAccountRef: 'load-card',
  };
  const kind = normal ? 'normal' : 'event';
  const response = http.post(`${base}/api/orders`, JSON.stringify(body), { headers: headers(token), timeout: '30s', tags: { name: `order-${kind}` } });
  const outcome = classify(response);
  outcomes[outcome].add(1, { kind });
  (normal ? normalLatency : eventLatency).add(response.timings.duration, { outcome });
  if (outcome === 'client_error' || outcome === 'conflict' || outcome === 'server_error') {
    console.warn(`order ${kind} ${response.status} ${code(response)}`);
  }
}

export function warmup() {
  order(flashBuyers + (exec.scenario.iterationInTest % warmupBuyers), true);
}

export function peak() {
  order(exec.scenario.iterationInTest);
}

export function tail() {
  order(peakCount + exec.scenario.iterationInTest);
}

export function gauge() {
  const response = http.get(`${base}/api/events/${plan.event.eventId}/gauge`, { timeout: '10s', tags: { name: 'gauge' } });
  check(response, { 'gauge read': (r) => r.status === 200 });
}
