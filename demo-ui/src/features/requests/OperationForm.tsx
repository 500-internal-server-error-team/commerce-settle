import { useEffect, useRef, useState } from 'react';
import { defaults, operations, resolve, type Json, type Operation } from '../../shared/api/contract';
import { execute, type RequestValues } from '../../shared/api/client';
import { Field, Fields } from '../../shared/ui/SchemaFields';
import { ReferencePicker, referenceSource } from './ReferencePicker';
import { responseContext } from './responseContext';

export function OperationForm({ operation, context, pending, allowed, run, role = 'BUYER', token }:
  { operation: Operation; context: Record<string, Json>; pending: boolean; allowed: boolean; run: (v: RequestValues) => void; role?: string; token?: string }) {
  const [body, setBody] = useState<Json>(operation.schema ? defaults(operation.schema) : null);
  const [pathParameters, setPath] = useState<Record<string, string>>({});
  const [query, setQuery] = useState<Record<string, string>>({});
  const [key, setKey] = useState<string>(crypto.randomUUID());
  const [lookup, setLookup] = useState(''); const [lookupBusy, setLookupBusy] = useState(false);
  const lookupAbort = useRef<AbortController | null>(null);
  const latestValues = useRef({ body, pathParameters }); latestValues.current = { body, pathParameters };
  useEffect(() => () => lookupAbort.current?.abort(), []);
  useEffect(() => {
    const fields = operation.schema ? resolve(operation.schema).properties ?? {} : {};
    const values = operation.schema ? defaults(operation.schema) as Record<string, Json> : null;
    if (values && 'quantity' in fields) values.quantity = 1;
    if (values && 'mileageAmount' in fields) values.mileageAmount = 0;
    if (values) for (const k of Object.keys(fields)) if (context[k] !== undefined) values[k] = context[k];
    const resourceId = operation.parameters.find(p => p.in === 'path')?.name;
    if (values && 'expectedVersion' in fields) values.expectedVersion = resourceId ? context[`${resourceId}Version`] ?? '' : '';
    setBody(values);
    setPath(Object.fromEntries(operation.parameters.filter(p => p.in === 'path').map(p => [p.name, String(context[p.name] ?? '')])));
    setQuery(Object.fromEntries(operation.parameters.filter(p => p.in === 'query').map(p => [p.name,
      String(p.name === 'expectedVersion' && resourceId ? context[`${resourceId}Version`] ?? '' : p.schema.default ?? '')])));
    setKey(crypto.randomUUID());
  }, [operation.id, context]);
  const object = body && typeof body === 'object' && !Array.isArray(body) ? body : {};
  const quote = operations.find(op => op.id === 'post_api_order_quotes')!;
  const quoteFields = Object.keys(resolve(quote.schema!).properties ?? {});
  function changeBody(next: Json) {
    const values = next && typeof next === 'object' && !Array.isArray(next) ? next : null;
    if (values && 'expectedTotalAmount' in object
      && quoteFields.some(key => JSON.stringify(object[key]) !== JSON.stringify(values[key]))) {
      next = { ...values, expectedTotalAmount: '' }; setLookup('구매 조건이 바뀌었습니다. 견적을 다시 조회하세요.');
    }
    setBody(next);
  }
  function assistance(name: string) {
    const source = referenceSource(name, operation, role);
    return source && <ReferencePicker source={source} token={token} choose={(row, sourceOperation) => {
      const values = responseContext(row, sourceOperation.path);
      const id = row.id; if (typeof id !== 'string') return;
      const pathField = operation.parameters.some(p => p.in === 'path' && p.name === name);
      const sameVersion = pathField && !operation.path.endsWith('/inventory-adjustments') ? row.version : undefined;
      if (pathField) {
        setPath(old => ({ ...old, [name]: id }));
        const next = { ...object };
        // Editing the chosen resource starts from that resource's real fields, not another object's name/price.
        if (operation.method === 'PUT' && operation.path === `${sourceOperation.path}/{${name}}`) {
          for (const key of Object.keys(next)) if (key !== 'expectedVersion' && row[key] !== undefined) next[key] = row[key];
        }
        if ('expectedVersion' in next) next.expectedVersion = sameVersion ?? '';
        if (operation.schema) setBody(next);
        if (operation.parameters.some(p => p.in === 'query' && p.name === 'expectedVersion')) setQuery(old => ({ ...old, expectedVersion: String(sameVersion ?? '') }));
      }
      else {
        const next = { ...object, [name]: id };
        for (const key of ['productId', 'expectedProductVersion', 'expectedEventVersion']) {
          if (key in object && values[key] !== undefined) next[key] = values[key];
        }
        if ('expectedTotalAmount' in next) next.expectedTotalAmount = '';
        setBody(next);
      }
      setLookup('실제 응답의 ID를 연결했습니다. 변경 작업은 대상 최신 값을 확인하고, 주문은 견적을 조회하세요.');
    }} />;
  }
  const detailPath = operation.path.endsWith('/inventory-adjustments') ? operation.path.replace(/inventory-adjustments$/, 'inventory')
    : operation.path.replace(/\/(publication|closure|issues|shipments|cancellation|payment-retries|refund-requests|decision|receipt|withdrawal|closure-confirmations|replacements|replays)$/, '');
  const detail = operations.find(op => op.method === 'GET' && op.path === detailPath
    && (op.actor === 'PUBLIC' || op.actor.includes(role)));
  async function lookupValues(kind: 'quote' | 'detail') {
    const op = kind === 'quote' ? quote : detail; if (!op) return;
    lookupAbort.current?.abort(); const controller = new AbortController(); lookupAbort.current = controller;
    const before = JSON.stringify({ body, pathParameters }); setLookupBusy(true); setLookup('');
    try {
      const result = await execute(op, { body: kind === 'quote' ? Object.fromEntries(quoteFields.map(key => [key, object[key] ?? null])) : null,
        pathParameters, query: {}, idempotencyKey: key }, token, controller.signal);
      if (controller.signal.aborted) return;
      if (result.status >= 300) throw new Error(`조회 실패 (HTTP ${result.status}) · ${result.data && typeof result.data === 'object' && !Array.isArray(result.data) ? String(result.data.detail ?? result.data.code ?? '') : ''}`);
      if (JSON.stringify(latestValues.current) !== before) throw new Error('조회 중 입력값이 바뀌었습니다. 현재 값으로 다시 조회하세요.');
      if (!result.data || typeof result.data !== 'object' || Array.isArray(result.data)) throw new Error('조회 응답을 확인할 수 없습니다.');
      const row = result.data.order && typeof result.data.order === 'object' && !Array.isArray(result.data.order) ? result.data.order : result.data;
      if (kind === 'detail' && row.version === undefined) throw new Error('응답에서 대상 버전을 찾지 못했습니다. 상세 응답의 버전 필드를 확인하세요.');
      const values = kind === 'quote' ? responseContext(row, op.path) : { expectedVersion: row.version };
      const next = { ...object };
      for (const key of kind === 'quote' ? ['expectedProductVersion', 'expectedEventVersion', 'expectedTotalAmount'] : ['expectedVersion']) {
        if (key in next && values[key] !== undefined) next[key] = values[key];
      }
      setBody(next); setLookup(kind === 'quote' ? '실제 견적의 금액·버전을 입력에 연결했습니다. 조건을 바꾸면 다시 조회하세요.' : '대상의 현재 버전을 조회했습니다. 실행 전 나머지 값을 확인하세요.');
    } catch (e) { if (!controller.signal.aborted) setLookup(e instanceof Error ? e.message : '조회하지 못했습니다.'); }
    finally { if (lookupAbort.current === controller) setLookupBusy(false); }
  }
  return <form aria-label="API 요청" onSubmit={event => { event.preventDefault(); run({ body, pathParameters, query, idempotencyKey: key }); }}>
    <div className="endpoint"><span className={`method ${operation.method.toLowerCase()}`}>{operation.method}</span><code>{operation.path}</code></div>
    <div className="input-guide"><strong>입력값은 어디서 찾나요?</strong><p>이름·가격·일정은 직접 정하고, ID는 각 입력 아래의 실제 목록에서 선택하세요. 조회된 내역이 없으면 해당 역할에서 먼저 등록해야 합니다.</p>
      {operation.path === '/api/orders' && operation.method === 'POST' && <p>상품 선택 → 수량·할인·결제수단 입력 → 배송지 선택 → 견적 조회 → 주문 실행</p>}
      {operation.schema && resolve(operation.schema).description && <p>{resolve(operation.schema).description}</p>}
    </div>
    <div className="fields">{operation.parameters.filter(p => p.in === 'path' || p.in === 'query').map(p =>
      <Field key={p.name} name={p.name} schema={p.schema} required={p.required}
        value={(p.in === 'path' ? pathParameters : query)[p.name] ?? ''}
        change={value => { (p.in === 'path' ? setPath : setQuery)(old => ({ ...old, [p.name]: String(value) }));
          if (p.in === 'path') {
            if ('expectedVersion' in object) setBody({ ...object, expectedVersion: '' });
            if (operation.parameters.some(p => p.in === 'query' && p.name === 'expectedVersion')) setQuery(old => ({ ...old, expectedVersion: '' }));
          } }} assistance={assistance(p.name)} />)}</div>
    {operation.schema && <Fields schema={operation.schema} value={body} change={changeBody} assistance={assistance} />}
    {'expectedVersion' in object && detail && <button type="button" className="secondary" disabled={lookupBusy || !allowed || operation.parameters.some(p => p.in === 'path' && !pathParameters[p.name])}
      onClick={() => lookupValues('detail')}>대상 최신 값 불러오기</button>}
    {'expectedTotalAmount' in object && <button type="button" className="secondary" disabled={lookupBusy || !allowed || !object.productId || !object.quantity}
      onClick={() => lookupValues('quote')}>{lookupBusy ? '견적 확인 중…' : '현재 입력으로 견적 조회'}</button>}
    {lookup && <p role="status" className="caption">{lookup}</p>}
    {operation.idempotent && <details className="idempotency"><summary>같은 작업의 재요청 키 · 멱등키</summary>
      <input aria-label="멱등키" value={key} onChange={e => setKey(e.target.value)} minLength={8} maxLength={128} required />
      <p className="caption">응답이 불명확하면 같은 입력·키로 재조회 또는 재요청합니다. 본문을 바꾸려면 기존 결과를 먼저 확인하세요.</p>
      <button type="button" className="text-button" onClick={() => setKey(crypto.randomUUID())}>새 작업 키 만들기</button></details>}
    <div className="form-footer"><span className="caption">* 필수 입력 · 서버가 가격·권한·상태를 최종 판단합니다.</span>
      <button className="primary" disabled={!allowed || pending}>{pending ? '응답 확인 중…' : '요청 실행'}</button></div>
    {!allowed && <p className="caption">이 작업의 역할로 로그인하거나 운영자 서버 자격을 설정해야 합니다.</p>}
  </form>;
}
