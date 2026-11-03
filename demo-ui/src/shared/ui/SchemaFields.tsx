import { useEffect, useState } from 'react';
import type { Json, Schema } from '../api/contract';
import { defaults, resolve } from '../api/contract';

const labels: Record<string, string> = {
  productId: '상품 ID', quantity: '수량', eventId: '이벤트 ID', buyerCouponId: '사용할 쿠폰 ID',
  mileageAmount: '사용 마일리지', addressId: '배송지 ID', expectedProductVersion: '확인한 상품 버전',
  expectedEventVersion: '확인한 이벤트 버전', expectedTotalAmount: '확인한 결제 금액',
  externalMethod: '결제수단', methodAccountRef: '결제 시험계정', expectedVersion: '확인한 버전',
  name: '이름', unitPrice: '판매 가격', status: '상태', reason: '사유', decision: '승인·거절',
  quota: '한정 수량', eventPrice: '행사 가격', startsAt: '시작 시각', endsAt: '종료 시각',
  validFrom: '사용 시작', validUntil: '사용 종료', totalBudget: '총 할인 예산',
  discountAmount: '할인액', minOrderAmount: '최소 주문금액', issuanceLimit: '발급 수량',
  minSellerNetAmount: '최소 판매자 정산액', allowedMethods: '허용 결제수단', buyerId: '발급 대상 구매자 ID',
  sellerId: '판매자 ID', batchDate: '정산 마감일', policyVersion: '정책 버전',
  email: '이메일', password: '비밀번호', displayName: '표시 이름', payoutAccountRef: '지급 시험계정',
  shipmentReference: '발송 참조', kind: '종류', recipientName: '수령인', phoneNumber: '연락처',
  postalCode: '우편번호', addressLine1: '기본 주소', addressLine2: '상세 주소', isDefault: '기본 배송지',
  resalable: '재판매 가능', delta: '조정 수량', reasonCode: '사유 코드', targetMonth: '혜택 월',
};
export function Field({ name, schema: input, value, change, required = false }:
  { name: string; schema: Schema; value: Json; change: (v: Json) => void; required?: boolean }) {
  const base = resolve(input);
  const nullable = base.anyOf?.some(s => s.type === 'null');
  const schema = resolve(base.anyOf?.find(s => s.type !== 'null') ?? base);
  if (schema.type === 'object') return <fieldset className="object-fields"><legend>{labels[name] ?? name}</legend>
    <Fields schema={schema} value={value ?? {}} change={change} /></fieldset>;
  return <div className="field"><label htmlFor={`field-${name}`}>
    {labels[name.split('.').at(-1)!] ?? name}{required && !nullable ? ' *' : ''}
    <span className="field-name">{name}</span></label>
    {nullable && <label className="null-choice"><input type="checkbox" checked={value === null}
      onChange={e => change(e.target.checked ? null : defaults(schema))} />사용 안 함</label>}
    {value !== null && (schema.enum ? <select id={`field-${name}`} value={String(value)} onChange={e => change(e.target.value)}>
      {schema.enum.map(v => <option key={String(v)} value={String(v)}>{String(v)}</option>)}</select>
      : schema.type === 'boolean' ? <select id={`field-${name}`} value={String(value)} onChange={e => change(e.target.value === 'true')}>
        <option value="false">아니요</option><option value="true">예</option></select>
      : schema.type === 'array' ? <ArrayInput name={name} value={value} change={change} />
      : <input id={`field-${name}`} type={/password|token/i.test(name) ? 'password' : schema.type === 'integer' || schema.type === 'number' ? 'number' : schema.format === 'email' ? 'email' : 'text'}
        value={String(value)} required={required} min={schema.minimum} max={schema.maximum} minLength={schema.minLength} maxLength={schema.maxLength}
        placeholder={schema.format === 'date-time' ? '2026-11-03T10:00:00+09:00' : schema.format === 'date' ? '2026-11-03' : ''}
        step={schema.type === 'integer' ? 1 : undefined}
        autoComplete={/password|token/i.test(name) ? 'off' : undefined}
        onChange={e => {
          const number = Number(e.target.value);
          e.target.setCustomValidity(schema.type === 'integer' && e.target.value !== '' && !Number.isSafeInteger(number)
            ? '정확하게 표현할 수 있는 정수 범위로 입력하세요.' : '');
          change((schema.type === 'integer' || schema.type === 'number') && e.target.value !== '' ? number : e.target.value);
        }} />)}
    {schema.format === 'date-time' && <small>시간대 포함 ISO 시각 · 예: +09:00</small>}
  </div>;
}
function ArrayInput({ name, value, change }: { name: string; value: Json; change: (v: Json) => void }) {
  const [draft, setDraft] = useState(JSON.stringify(value));
  useEffect(() => setDraft(JSON.stringify(value)), [value]);
  return <input id={`field-${name}`} value={draft} placeholder='["CREDIT_CARD"]' onChange={event => {
    setDraft(event.target.value);
    try {
      const parsed: Json = JSON.parse(event.target.value);
      if (!Array.isArray(parsed)) throw new Error('array required');
      event.target.setCustomValidity(''); change(parsed);
    } catch { event.target.setCustomValidity('JSON 배열로 입력하세요. 예: ["CREDIT_CARD"]'); }
  }} />;
}
export function Fields({ schema: input, value, change }: { schema: Schema; value: Json; change: (v: Json) => void }) {
  const schema = resolve(input); const object = (value && typeof value === 'object' && !Array.isArray(value) ? value : {}) as Record<string, Json>;
  return <div className="fields">{Object.entries(schema.properties ?? {}).map(([key, child]) =>
    <Field key={key} name={key} schema={child} value={object[key] ?? null} required={schema.required?.includes(key)}
      change={v => change({ ...object, [key]: v })} />)}</div>;
}
