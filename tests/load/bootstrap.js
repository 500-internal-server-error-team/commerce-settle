import http from 'k6/http';
import { check } from 'k6';

function positiveInteger(name, fallback) {
  const value = __ENV[name] || fallback;
  if (!/^[1-9]\d*$/.test(value) || !Number.isSafeInteger(Number(value))) {
    throw new Error(`${name} must be a positive integer`);
  }
  return Number(value);
}

const rate = positiveInteger('RATE', '10');
const seconds = positiveInteger('DURATION_SECONDS', '10');
const preAllocatedVUs = positiveInteger('PRE_ALLOCATED_VUS', '20');
const maxVUs = positiveInteger('MAX_VUS', '20');
if (maxVUs < preAllocatedVUs) {
  throw new Error('MAX_VUS must be >= PRE_ALLOCATED_VUS');
}

export const options = {
  scenarios: {
    bootstrap: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration: `${seconds}s`,
      preAllocatedVUs,
      maxVUs,
    },
  },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    dropped_iterations: ['count==0'],
  },
};

// One document request per iteration. This verifies the tool, not payment TPS.
export default function () {
  const response = http.get(`${__ENV.BASE_URL || 'http://gateway:8080'}/v3/api-docs/seller`, {
    timeout: '5s',
    tags: { name: 'bootstrap-seller-docs' },
  });
  check(response, {
    'Seller OpenAPI is reachable through Gateway': (r) => {
      if (r.status !== 200) return false;
      try {
        const document = r.json();
        return document.openapi.startsWith('3.') && document.info.title === 'Seller';
      } catch (_) {
        return false;
      }
    },
  });
}
