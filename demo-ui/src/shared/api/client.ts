import type { Json, Operation } from './contract';
import { redact } from './contract';

export interface Execution {
  id: string; at: string; operation: string; method: string; path: string;
  status: number; elapsedMs: number; request: unknown; response: unknown; actor: string;
}
export interface RequestValues {
  pathParameters: Record<string, string>; query: Record<string, string>; body: Json; idempotencyKey: string;
}
export class RequestInputError extends Error {}
/** Jackson int64 values outside JS's exact range remain decimal strings, including in the journal. */
export function parseResponse(text: string): Json {
  return JSON.parse(text.replace(/"(?:[^"\\]|\\.)*"|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/g, token => {
    if (token.startsWith('"')) return token;
    const number = Number(token);
    if (!Number.isFinite(number)) throw new SyntaxError('응답 숫자의 표현 범위를 확인하세요.');
    return Number.isInteger(number) && !Number.isSafeInteger(number) ? JSON.stringify(token) : token;
  })) as Json;
}
export async function execute(operation: Operation, values: RequestValues, token: string | undefined,
  signal?: AbortSignal): Promise<{ status: number; data: Json; elapsedMs: number }> {
  const start = performance.now();
  const direct = import.meta.env.DEV && operation.actor !== 'OPERATOR' && operation.path.startsWith('/api/');
  let path = operation.path as string;
  if (direct) {
    const names = [...path.matchAll(/\{([^}]+)}/g)].map(match => match[1]);
    if (Object.keys(values.pathParameters).length !== names.length
      || names.some(name => !/^[A-Za-z0-9_-]{1,128}$/.test(values.pathParameters[name] ?? '')))
      throw new RequestInputError('경로 식별자를 확인하세요.');
    path = path.replace(/\{([^}]+)}/g, (_, name: string) => encodeURIComponent(values.pathParameters[name]));
    const allowed = new Set(operation.parameters.filter(p => p.in === 'query').map(p => p.name));
    if (Object.keys(values.query).some(name => !allowed.has(name))) throw new RequestInputError('허용되지 않은 조회 조건입니다.');
    if (values.idempotencyKey && !/^[\x21-\x7e]{8,128}$/.test(values.idempotencyKey)) throw new RequestInputError('멱등키 형식을 확인하세요.');
    const query = new URLSearchParams(Object.entries(values.query).filter(([, value]) => value.trim() !== '')).toString();
    if (query) path += `?${query}`;
  }
  const response = await fetch(direct ? path : '/demo/requests', direct ? {
    method: operation.method, signal,
    headers: { ...(values.body !== null ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(values.idempotencyKey ? { 'Idempotency-Key': values.idempotencyKey } : {}) },
    ...(values.body !== null ? { body: JSON.stringify(values.body) } : {}),
  } : {
    method: 'POST', signal, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify({ operationId: operation.id, ...values }),
  });
  const text = await response.text();
  const result = response.status === 204 && text.trim() === '' ? null : parseResponse(text);
  const wrapped = !direct && result !== null && typeof result === 'object' && !Array.isArray(result)
    && typeof result.upstreamStatus === 'number' && Object.hasOwn(result, 'data');
  return { status: wrapped ? result.upstreamStatus as number : response.status,
    data: wrapped ? result.data : result,
    elapsedMs: Math.round(performance.now() - start) };
}
export function journal(operation: Operation, values: RequestValues, actor: string,
  result: { status: number; data: Json; elapsedMs: number }): Execution {
  return { id: crypto.randomUUID(), at: new Date().toISOString(), operation: operation.title,
    method: operation.method, path: operation.path, actor, status: result.status, elapsedMs: result.elapsedMs,
    request: redact(values), response: redact(result.data) };
}
