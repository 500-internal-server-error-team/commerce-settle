import { useEffect, useRef, useState } from 'react';
import { defaults, operations, resolve, type Json, type Operation } from '../../shared/api/contract';
import { execute, type RequestValues } from '../../shared/api/client';
import { Field, Fields } from '../../shared/ui/SchemaFields';
import { ReferencePicker, referenceSource } from './ReferencePicker';
import { responseContext } from './responseContext';

export function OperationForm({ operation, context, pending, allowed, run, role = 'BUYER', token, loginValues, recoveryValues }:
  { operation: Operation; context: Record<string, Json>; pending: boolean; allowed: boolean; run: (v: RequestValues) => void; role?: string; token?: string;
    loginValues?: { email: string; password: string }; recoveryValues?: RequestValues }) {
  const [body, setBody] = useState<Json>(operation.schema ? defaults(operation.schema) : null);
  const [pathParameters, setPath] = useState<Record<string, string>>({});
  const [query, setQuery] = useState<Record<string, string>>({});
  const [key, setKey] = useState<string>(crypto.randomUUID());
  const [lookup, setLookup] = useState(''); const [lookupBusy, setLookupBusy] = useState(false);
  const lookupAbort = useRef<AbortController | null>(null);
  const latestValues = useRef({ body, pathParameters }); latestValues.current = { body, pathParameters };
  useEffect(() => () => lookupAbort.current?.abort(), []);
  useEffect(() => {
    if (pending) { lookupAbort.current?.abort(); setLookupBusy(false); }
  }, [pending]);
  useEffect(() => {
    const fields = operation.schema ? resolve(operation.schema).properties ?? {} : {};
    const values = operation.schema ? defaults(operation.schema) as Record<string, Json> : null;
    if (values && 'quantity' in fields) values.quantity = 1;
    if (values && 'mileageAmount' in fields) values.mileageAmount = 0;
    if (values) for (const k of Object.keys(fields)) if (context[k] !== undefined) values[k] = context[k];
    if (values && loginValues && operation.path.endsWith('-sessions')) {
      values.email = loginValues.email; values.password = loginValues.password;
    }
    const resourceId = operation.parameters.find(p => p.in === 'path')?.name;
    if (values && 'expectedVersion' in fields) values.expectedVersion = operation.path.endsWith('/inventory-adjustments')
      ? context.inventoryProductId === context.productId ? context.inventoryVersion ?? '' : ''
      : resourceId && context[`${resourceId}VersionFor`] === context[resourceId] ? context[`${resourceId}Version`] ?? '' : '';
    setBody(values);
    setPath(Object.fromEntries(operation.parameters.filter(p => p.in === 'path').map(p => [p.name, String(context[p.name] ?? '')])));
    setQuery(Object.fromEntries(operation.parameters.filter(p => p.in === 'query').map(p => [p.name,
      String(p.name === 'expectedVersion'
        ? resourceId && context[`${resourceId}VersionFor`] === context[resourceId] ? context[`${resourceId}Version`] ?? '' : ''
        : p.name.endsWith('Id') ? context[p.name] ?? p.schema.default ?? '' : p.schema.default ?? '')])));
    setKey(crypto.randomUUID());
  }, [operation.id, context, loginValues]);
  useEffect(() => {
    if (!recoveryValues) return;
    lookupAbort.current?.abort(); setLookupBusy(false); setLookup('');
    setBody(recoveryValues.body); setPath(recoveryValues.pathParameters); setQuery(recoveryValues.query);
    setKey(recoveryValues.idempotencyKey);
  }, [recoveryValues]);
  const login = operation.method === 'POST' && ['/api/buyer-sessions', '/api/seller-sessions'].includes(operation.path);
  const signup = operation.method === 'POST' && (operation.path === '/api/buyers' || operation.path === '/api/sellers');
  function fillPracticeSignup() {
    if (pending) return;
    const seller = operation.path === '/api/sellers';
    const id = crypto.randomUUID().replaceAll('-', '');
    setBody({ email: `practice-${seller ? 'seller' : 'buyer'}-${id}@example.test`,
      password: `Practice-${crypto.randomUUID()}`, displayName: seller ? '연습 판매자' : '연습 구매자',
      ...(seller ? { payoutAccountRef: `practice-seller-${id}` } : {}) });
    setKey(crypto.randomUUID());
  }
  const object = body && typeof body === 'object' && !Array.isArray(body) ? body : {};
  const quote = operations.find(op => op.id === 'post_api_order_quotes')!;
  const quoteFields = Object.keys(resolve(quote.schema!).properties ?? {});
  function changeBody(next: Json) {
    const values = next && typeof next === 'object' && !Array.isArray(next) ? next : null;
    if (values && 'expectedTotalAmount' in object
      && quoteFields.some(key => JSON.stringify(object[key]) !== JSON.stringify(values[key]))) {
      values.expectedTotalAmount = ''; setLookup('구매 조건이 바뀌었습니다. 견적을 다시 조회하세요.');
    }
    if (values && values.productId !== object.productId) {
      if ('expectedProductVersion' in values) values.expectedProductVersion = '';
      if ('eventId' in values) values.eventId = null;
      if ('expectedEventVersion' in values) values.expectedEventVersion = null;
    } else if (values && values.eventId !== object.eventId && 'expectedEventVersion' in values) {
      values.expectedEventVersion = values.eventId === null ? null : '';
    }
    setBody(next);
  }
  function assistance(name: string) {
    const source = referenceSource(name, operation, role);
    return source && <ReferencePicker source={source} token={token} choose={(row, sourceOperation) => {
      const values = responseContext(row, sourceOperation.path);
      const id = row.id; if (typeof id !== 'string') return;
      const pathField = operation.parameters.some(p => p.in === 'path' && p.name === name);
      const queryField = operation.parameters.some(p => p.in === 'query' && p.name === name);
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
      else if (queryField) setQuery(old => ({ ...old, [name]: id }));
      else {
        const next = { ...object, [name]: id };
        if (name === 'productId' || name === 'eventId') {
          for (const key of ['productId', 'expectedProductVersion', 'expectedEventVersion']) {
            if (key in object && values[key] !== undefined) next[key] = values[key];
          }
          if (next.productId !== object.productId) {
            if ('expectedProductVersion' in next && values.expectedProductVersion === undefined) next.expectedProductVersion = '';
            if (name === 'productId') {
              if ('eventId' in next) next.eventId = null;
              if ('expectedEventVersion' in next) next.expectedEventVersion = null;
            }
          }
        }
        if ('expectedTotalAmount' in next) next.expectedTotalAmount = '';
        setBody(next);
      }
      setLookup('선택한 항목을 입력에 연결했습니다. 변경 작업은 최신 버전을, 주문은 견적을 확인하세요.');
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
  return <form aria-label="API 요청" onSubmit={event => {
    event.preventDefault(); if (pending || !allowed) return;
    lookupAbort.current?.abort(); setLookupBusy(false);
    run({ body, pathParameters, query, idempotencyKey: key });
  }}>
    <div className="endpoint"><span className={`method ${operation.method.toLowerCase()}`}>{operation.method}</span><code>{operation.path}</code></div>
    {!login && <div className="input-guide"><strong>입력값 안내</strong><p>{signup
        ? '직접 값을 정하지 않아도 아래 버튼으로 연습용 가입값을 채울 수 있습니다. 확인 후 회원가입을 눌러 가입하세요.'
      : operation.method === 'GET'
        ? '조회할 ID와 조회 조건을 입력하세요. 목록이 제공되는 항목은 아래에서 선택할 수 있습니다.'
      : '목록이 있는 ID는 아래에서 선택하고, 나머지는 생성·조회 응답에서 확인하세요. 이름·가격·일정은 직접 정합니다.'}</p>
      {operation.path === '/api/orders' && operation.method === 'POST' && <p>상품 선택 → 수량·할인·결제수단 입력 → 배송지 선택 → 견적 조회 → 주문 실행</p>}
      {operation.path === '/api/orders' && operation.method === 'POST' && <p>일반·이벤트 구매와 쿠폰 사용을 지원합니다. 선택한 조건으로 견적을 조회한 뒤 주문하세요.</p>}
      {['/api/orders', '/api/order-quotes'].includes(operation.path) && operation.method === 'POST' && <p>견적에서 쿠폰을 적용할 때는 마일리지를 0으로 두고 허용된 카드·간편결제를 선택하세요. 주문 접수 시 서버가 금액과 조건을 다시 확인합니다.</p>}
      {operation.schema && resolve(operation.schema).description && <details><summary>API 입력 제약 원문</summary><p>{resolve(operation.schema).description}</p></details>}
    </div>}
    <fieldset className="request-inputs" aria-label="요청 입력" disabled={pending}>
    {signup && <div className="input-guide"><button type="button" className="secondary" onClick={fillPracticeSignup}>연습용 가입값 채우기</button>
      <p className="caption">테스트 이메일·비밀번호·이름{operation.path === '/api/sellers' ? '·판매자 지급 대상' : ''}을 만듭니다. 가입에 성공하면 같은 정보로 로그인할 수 있습니다.</p></div>}
    {operation.parameters.some(p => p.in === 'path' || p.in === 'query') && <div className="fields">{operation.parameters.filter(p => p.in === 'path' || p.in === 'query').map(p =>
      <Field key={p.name} name={p.name} schema={p.schema} required={p.required}
        value={(p.in === 'path' ? pathParameters : query)[p.name] ?? ''}
        change={value => { (p.in === 'path' ? setPath : setQuery)(old => ({ ...old, [p.name]: String(value) }));
          if (p.in === 'path') {
            if ('expectedVersion' in object) setBody({ ...object, expectedVersion: '' });
            if (operation.parameters.some(p => p.in === 'query' && p.name === 'expectedVersion')) setQuery(old => ({ ...old, expectedVersion: '' }));
          } }} assistance={assistance(p.name)} />)}</div>}
    {operation.schema && <Fields schema={operation.schema} value={body} change={changeBody} assistance={assistance} />}
    {'expectedVersion' in object && detail && <button type="button" className="secondary" disabled={lookupBusy || !allowed || operation.parameters.some(p => p.in === 'path' && !pathParameters[p.name])}
      onClick={() => lookupValues('detail')}>대상 최신 값 불러오기</button>}
    {'expectedTotalAmount' in object && <button type="button" className="secondary" disabled={lookupBusy || !allowed || !object.productId || !object.quantity}
      onClick={() => lookupValues('quote')}>{lookupBusy ? '견적 확인 중…' : '현재 입력으로 견적 조회'}</button>}
    {lookup && <p role="status" className="caption">{lookup}</p>}
    {operation.idempotent && <details className="idempotency"><summary>같은 작업의 재요청 키 · 멱등키</summary>
      <input aria-label="멱등키" value={key} onChange={e => setKey(e.target.value)} minLength={8} maxLength={128} required />
      <p className="caption">응답이 불명확하면 먼저 원래 거래를 조회하세요. 같은 작업을 재요청할 때는 입력과 키를 유지합니다. 입력을 바꾸려면 기존 결과부터 확인하세요.</p>
      <button type="button" className="text-button" onClick={() => setKey(crypto.randomUUID())}>새 작업 키 만들기</button></details>}
    <div className="form-footer"><span className="caption">* 필수 입력 · 서버가 가격·권한·상태를 최종 판단합니다.</span>
      <button className="primary" disabled={!allowed || pending}>{pending ? '응답 확인 중…' : login ? '로그인' : signup ? '회원가입' : '요청 실행'}</button></div>
    </fieldset>
    {!allowed && <p className="caption">{operation.actor === 'OPERATOR' ? '제어 서버에 해당 서비스의 운영자 자격을 설정해야 실행할 수 있습니다.' : '이 작업에 필요한 역할로 전환하고 로그인하세요.'}</p>}
  </form>;
}
