import document from '../../generated/contract.json';
import type { paths } from '../../generated/api';

export type ApiPath = keyof paths;
export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export interface Schema {
  $ref?: string; type?: string; enum?: Json[]; const?: Json; default?: Json;
  properties?: Record<string, Schema>; required?: string[]; items?: Schema;
  anyOf?: Schema[]; allOf?: Schema[]; format?: string; description?: string;
  minimum?: number; maximum?: number; minLength?: number; maxLength?: number;
}
export interface Parameter { name: string; in: string; required?: boolean; schema: Schema }
export interface Operation {
  id: string; path: ApiPath; method: string; title: string; actor: string; owner: string;
  parameters: Parameter[]; schema?: Schema; idempotent: boolean;
}
const spec = document as unknown as {
  components: { schemas: Record<string, Schema>; parameters: Record<string, Parameter> };
  paths: Record<string, Record<string, { operationId: string; summary?: string;
    'x-actor': string; 'x-owner-service': string; parameters?: (Parameter & { $ref?: string })[];
    requestBody?: { content?: { 'application/json'?: { schema: Schema } } } }>>;
};
export function resolve(schema: Schema): Schema {
  if (schema.$ref) return resolve(spec.components.schemas[schema.$ref.split('/').at(-1)!]);
  return schema;
}
export const operations: Operation[] = Object.entries(spec.paths).flatMap(([path, methods]) =>
  Object.entries(methods).filter(([method, op]) => ['get', 'post', 'put', 'delete'].includes(method)
    && op.operationId && (path.startsWith('/api/') || op['x-actor'] === 'OPERATOR')).map(([method, op]) => {
      const parameters = (op.parameters ?? []).map(p => p.$ref ? spec.components.parameters[p.$ref.split('/').at(-1)!] : p);
      return { id: op.operationId, path: path as ApiPath, method: method.toUpperCase(), title: op.summary ?? path,
        actor: op['x-actor'], owner: op['x-owner-service'], parameters,
        schema: op.requestBody?.content?.['application/json']?.schema,
        idempotent: parameters.some(p => p.in === 'header' && p.name === 'Idempotency-Key') };
    }));

export function defaults(input: Schema): Json {
  const schema = resolve(input);
  if (schema.default !== undefined) return schema.default;
  if (schema.const !== undefined) return schema.const;
  if (schema.anyOf?.some(s => s.type === 'null')) return null;
  if (schema.enum) return schema.enum[0];
  if (schema.type === 'object') return Object.fromEntries(Object.entries(schema.properties ?? {}).map(([k, s]) => [k, defaults(s)]));
  if (schema.type === 'array') return [];
  if (schema.type === 'boolean') return false;
  return '';
}

const secrets = /password|token|authorization|credential|secret|confirmationRef/i;
export function redact(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(redact);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value)
    .map(([key, v]) => [key, secrets.test(key) ? '••••••' : redact(v)]));
  return value;
}
