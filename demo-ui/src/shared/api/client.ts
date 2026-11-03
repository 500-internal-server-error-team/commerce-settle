import type { Json, Operation } from './contract';
import { redact } from './contract';

export interface Execution {
  id: string; at: string; operation: string; method: string; path: string;
  status: number; elapsedMs: number; request: unknown; response: unknown; actor: string;
}
export interface RequestValues {
  pathParameters: Record<string, string>; query: Record<string, string>; body: Json; idempotencyKey: string;
}
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
  const response = await fetch('/demo/requests', {
    method: 'POST', signal, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify({ operationId: operation.id, ...values }),
  });
  const result = parseResponse(await response.text());
  const wrapped = result !== null && typeof result === 'object' && !Array.isArray(result)
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
