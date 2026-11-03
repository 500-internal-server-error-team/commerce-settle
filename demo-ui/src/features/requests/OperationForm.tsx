import { useEffect, useState } from 'react';
import { defaults, resolve, type Json, type Operation } from '../../shared/api/contract';
import type { RequestValues } from '../../shared/api/client';
import { Field, Fields } from '../../shared/ui/SchemaFields';

export function OperationForm({ operation, context, pending, allowed, run }:
  { operation: Operation; context: Record<string, Json>; pending: boolean; allowed: boolean; run: (v: RequestValues) => void }) {
  const [body, setBody] = useState<Json>(operation.schema ? defaults(operation.schema) : null);
  const [pathParameters, setPath] = useState<Record<string, string>>({});
  const [query, setQuery] = useState<Record<string, string>>({});
  const [key, setKey] = useState<string>(crypto.randomUUID());
  useEffect(() => {
    const fields = operation.schema ? resolve(operation.schema).properties ?? {} : {};
    const values = operation.schema ? defaults(operation.schema) as Record<string, Json> : null;
    if (values) for (const k of Object.keys(fields)) if (context[k] !== undefined) values[k] = context[k];
    const resourceId = operation.parameters.find(p => p.in === 'path')?.name;
    if (values && 'expectedVersion' in fields) values.expectedVersion = resourceId ? context[`${resourceId}Version`] ?? '' : '';
    setBody(values);
    setPath(Object.fromEntries(operation.parameters.filter(p => p.in === 'path').map(p => [p.name, String(context[p.name] ?? '')])));
    setQuery(Object.fromEntries(operation.parameters.filter(p => p.in === 'query').map(p => [p.name, String(p.schema.default ?? '')])));
    setKey(crypto.randomUUID());
  }, [operation.id, context]);
  return <form aria-label="API 요청" onSubmit={event => { event.preventDefault(); run({ body, pathParameters, query, idempotencyKey: key }); }}>
    <div className="endpoint"><span className={`method ${operation.method.toLowerCase()}`}>{operation.method}</span><code>{operation.path}</code></div>
    <div className="fields">{operation.parameters.filter(p => p.in === 'path' || p.in === 'query').map(p =>
      <Field key={p.name} name={p.name} schema={p.schema} required={p.required}
        value={(p.in === 'path' ? pathParameters : query)[p.name] ?? ''}
        change={value => (p.in === 'path' ? setPath : setQuery)(old => ({ ...old, [p.name]: String(value) }))} />)}</div>
    {operation.schema && <Fields schema={operation.schema} value={body} change={setBody} />}
    {operation.idempotent && <details className="idempotency"><summary>같은 작업의 재요청 키 · 멱등키</summary>
      <input aria-label="멱등키" value={key} onChange={e => setKey(e.target.value)} minLength={8} maxLength={128} required />
      <p className="caption">응답이 불명확하면 같은 입력·키로 재조회 또는 재요청합니다. 본문을 바꾸려면 기존 결과를 먼저 확인하세요.</p>
      <button type="button" className="text-button" onClick={() => setKey(crypto.randomUUID())}>새 작업 키 만들기</button></details>}
    <div className="form-footer"><span className="caption">* 필수 입력 · 서버가 가격·권한·상태를 최종 판단합니다.</span>
      <button className="primary" disabled={!allowed || pending}>{pending ? '응답 확인 중…' : '요청 실행'}</button></div>
    {!allowed && <p className="caption">이 작업의 역할로 로그인하거나 운영자 서버 자격을 설정해야 합니다.</p>}
  </form>;
}
