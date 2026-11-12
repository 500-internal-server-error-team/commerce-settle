import { useEffect, useState, type ReactNode } from 'react';
import type { Json, Schema } from '../api/contract';
import { defaults, resolve } from '../api/contract';
import { Select } from './Select';
import { codeLabel } from './labels';

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
  email: '이메일', password: '비밀번호', displayName: '표시 이름', payoutAccountRef: '판매자 지급 대상',
  shipmentReference: '발송 참조', kind: '종류', recipientName: '수령인', phoneNumber: '연락처',
  postalCode: '우편번호', addressLine1: '기본 주소', addressLine2: '상세 주소', isDefault: '기본 배송지',
  resalable: '재판매 가능', delta: '조정 수량', reasonCode: '사유 코드', targetMonth: '혜택 월',
  page: '페이지 번호', size: '페이지당 건수', orderId: '주문 ID', paymentId: '결제 ID',
  requestId: '반품 요청 ID', refundId: '환불 ID', templateId: '쿠폰 캠페인 ID', runId: '월간 회차 ID',
  payoutId: '지급 ID', jobId: '처리 작업 ID', notificationId: '알림 ID', recipient: '수령인', phone: '연락처',
  actorKind: '계정 종류', token: '비밀번호 복구 토큰', newPassword: '새 비밀번호',
  deliveryId: '전달 ID', quarantineId: '격리 ID', deliveryStatus: '전달 상태', accountId: '시험계정 ID',
  accountRef: '시험계정 참조', providerId: '금융 제공자', purpose: '시험계정 용도', balance: '시험 잔액',
  customerConfirmationRef: '고객 확인 참조', scenarioVersion: '장애 시나리오 버전', seed: '난수 시드',
};
const hints: Record<string, string> = {
  productId: '아래 상품 목록에서 선택하세요. 판매 상품이 없으면 판매자 역할에서 상품과 재고를 먼저 등록하세요.',
  eventId: '행사 구매에만 사용합니다. 아래 실제 이벤트 목록에서 선택하세요.',
  buyerCouponId: '현재 구매자가 보유한 쿠폰을 선택하세요. 쿠폰을 사용하지 않으면 사용 안 함을 유지하세요.',
  addressId: '현재 구매자의 배송지를 선택하세요. 계정·설정에서 먼저 등록할 수 있습니다.',
  buyerId: '쿠폰을 발급할 구매자가 계정·설정의 내 계정 조회로 확인한 ID를 입력하세요. 판매자에게 전체 구매자 목록은 제공하지 않습니다.',
  sellerId: '대상 판매자가 내 계정 조회에서 확인한 계정 ID를 입력하세요.',
  expectedVersion: '대상 항목의 최신 상세 조회 응답에 있는 version입니다. 상품·주문·재고의 버전을 서로 대신 쓰지 마세요.',
  expectedProductVersion: '상품 선택 또는 견적 응답에서 확인한 상품 버전입니다.',
  expectedEventVersion: '이벤트 선택 또는 견적 응답에서 확인한 이벤트 버전입니다. 일반 구매는 사용 안 함입니다.',
  expectedTotalAmount: '상품·수량·쿠폰·마일리지·결제수단을 정한 뒤 아래 견적 조회로 채우세요. 단위: 원.',
  quantity: '주문할 개수입니다. 먼저 상품을 선택하세요.', mileageAmount: '사용할 마일리지입니다. 사용하지 않으면 0을 입력하세요.',
  externalMethod: '외부 결제액이 있으면 결제수단을 선택하세요. 전액 마일리지 결제는 사용 안 함입니다.',
  email: '가입할 때는 테스트용 이메일을 정하세요. 로그인·비밀번호 복구에는 해당 역할로 가입한 주소를 입력합니다.',
  password: '새 계정은 테스트용 비밀번호를 정하세요. 로그인할 때는 가입 시 정한 값을 사용합니다.', newPassword: '복구할 계정의 새 비밀번호를 직접 정하세요.',
  token: '비밀번호 복구 요청 뒤 기존 서버의 전달 경로에서 받은 토큰입니다. 임의 문자열로 대체할 수 없습니다.',
  displayName: '화면에 표시할 계정 이름을 직접 정하세요.', name: '등록할 항목의 이름을 직접 정하세요.',
  methodAccountRef: '신용·체크카드는 시연용 참조값(예: demo-card)을 정하면 됩니다. 간편결제는 모의 금융 서버에 준비한 지갑 accountRef를 입력하세요.',
  payoutAccountRef: '모의 지급에서 판매자를 구분할 참조값입니다. 연습용 가입값을 채우면 함께 만들어집니다.',
  unitPrice: '판매자가 정할 상품 가격입니다. 단위: 원.', eventPrice: '행사에 적용할 판매 가격입니다. 단위: 원.',
  quota: '행사에 배정할 한정 수량입니다. 판매 가능한 재고를 먼저 확인하세요.', delta: '재고에 더하거나 뺄 수량입니다. 차감은 음수로 입력하세요.',
  startsAt: '행사 시작 시각을 직접 정하세요. 종료 시각보다 빨라야 합니다.', endsAt: '행사 종료 시각을 직접 정하세요. 시작 시각보다 늦어야 합니다.',
  validFrom: '쿠폰을 사용할 수 있는 시작 시각입니다.', validUntil: '쿠폰 사용을 마치는 시각입니다.',
  discountAmount: '쿠폰 한 장의 할인액입니다. 단위: 원.', minOrderAmount: '쿠폰을 적용할 최소 주문금액입니다. 단위: 원.',
  totalBudget: '판매자가 배정할 총 할인 예산입니다. 단위: 원.', issuanceLimit: '최대 발급할 쿠폰 장수입니다.',
  minSellerNetAmount: '할인 후 보장할 최소 판매자 정산액입니다. 단위: 원.', allowedMethods: '쿠폰을 허용할 결제수단을 선택하세요.',
  page: '첫 페이지는 0입니다. 다음 페이지는 1, 2 순서로 조회하세요.', size: '한 번에 조회할 건수입니다. 최대 100건입니다.',
  recipient: '배송받을 사람의 이름을 직접 입력하세요.', phone: '배송지 연락처를 입력하세요.', postalCode: '배송지 우편번호를 입력하세요.',
  addressLine1: '배송지 기본 주소를 입력하세요.', addressLine2: '동·호수 등 상세 주소입니다. 없으면 비워 두세요.', isDefault: '이 배송지를 기본값으로 사용할지 선택하세요.',
  reason: '이번 변경이나 처리의 사유를 직접 적으세요.', shipmentReference: '실제로 발송한 건을 식별할 배송 참조를 입력하세요.',
  requestId: '반품 요청 생성 응답의 ID입니다. 판매자는 반품 요청 목록에서도 선택할 수 있습니다.',
  refundId: '반품·취소 처리 뒤 실제 응답에서 확인한 환불 ID입니다. 먼저 해당 주문의 상태를 조회하세요.',
  customerConfirmationRef: '고객의 실제 확인을 식별할 참조입니다. 임의 확인이나 동의를 만들지 마세요.',
  batchDate: '정산을 마감할 날짜를 선택하세요.', targetMonth: '조회할 혜택 월의 1일을 선택하세요. 예: 2026-11-01.',
  policyVersion: '백엔드에 설정된 정산 정책 버전입니다. 실행 환경의 정책과 일치해야 합니다.',
};
export function Field({ name, schema: input, value, change, required = false, assistance }:
  { name: string; schema: Schema; value: Json; change: (v: Json) => void; required?: boolean; assistance?: ReactNode }) {
  const [selectionError, setSelectionError] = useState('');
  useEffect(() => setSelectionError(''), [value]);
  const base = resolve(input);
  const nullable = base.anyOf?.some(s => s.type === 'null');
  const schema = resolve(base.anyOf?.find(s => s.type !== 'null') ?? base);
  const reference = schema.format === 'uuid' && !!assistance;
  const nonEmpty = required && (schema.type !== 'string' || (schema.minLength ?? 0) > 0 || !!schema.format || !!schema.enum && !schema.enum.includes(''));
  if (schema.type === 'object') return <fieldset className="object-fields"><legend>{labels[name] ?? name}</legend>
    <Fields schema={schema} value={value ?? {}} change={change} /></fieldset>;
  const label = <label htmlFor={`field-${name}`}>{labels[name.split('.').at(-1)!] ?? name}{nonEmpty && !nullable ? ' *' : ''}
    <span className="field-name">{name}</span></label>;
  const control = (value !== null || !nullable) && (schema.enum ? <Select id={`field-${name}`} aria-describedby={`hint-${name}`} value={String(value ?? '')} required={required} onChange={e => change(e.target.value)}>
    <option value="">{required ? '값을 선택하세요' : '선택 안 함'}</option>
    {schema.enum.map(v => <option key={String(v)} value={String(v)}>{codeLabel(String(v))}</option>)}</Select>
    : schema.type === 'boolean' ? <Select id={`field-${name}`} aria-describedby={`hint-${name}`} value={String(value)} onChange={e => change(e.target.value === 'true')}>
      <option value="false">아니요</option><option value="true">예</option></Select>
    : schema.type === 'array' && schema.items && resolve(schema.items).enum ? <div className="array-options">{resolve(schema.items).enum!.map(item =>
      <label key={String(item)}><input type="checkbox" checked={Array.isArray(value) && value.includes(item)} onChange={event => {
        const values = Array.isArray(value) ? value : []; change(event.target.checked ? [...values, item] : values.filter(v => v !== item));
      }} />{codeLabel(String(item))}</label>)}</div>
    : schema.type === 'array' ? <ArrayInput name={name} value={value} change={change} />
    : <input id={`field-${name}`} type={/password|token/i.test(name) ? 'password' : schema.type === 'integer' || schema.type === 'number' ? 'number' : schema.format === 'email' ? 'email' : schema.format === 'date' ? 'date' : schema.format === 'date-time' ? 'datetime-local' : 'text'}
      aria-describedby={`hint-${name}`} value={schema.format === 'date-time' ? localTime(value) : String(value ?? '')} required={nonEmpty}
      min={schema.minimum} max={schema.maximum} minLength={schema.minLength} maxLength={schema.maxLength}
      placeholder={schema.format === 'date-time' ? 'YYYY-MM-DDThh:mm:ss+09:00' : schema.format === 'date' ? 'YYYY-MM-DD' : schema.format === 'uuid' ? '이미 알고 있는 ID' : ''}
      step={schema.type === 'integer' || schema.format === 'date-time' ? 1 : schema.type === 'number' ? 'any' : undefined}
      autoComplete={/password|token/i.test(name) ? 'off' : undefined}
      onInvalid={e => { if (reference && !e.currentTarget.closest('details')?.open) { e.preventDefault(); setSelectionError('먼저 목록을 불러와 대상을 선택하세요.');
        e.currentTarget.closest('.field')?.querySelector<HTMLButtonElement>('.reference-picker button')?.focus(); } }}
      onChange={e => {
        if (schema.format === 'date-time') { change(e.target.value ? new Date(e.target.value).toISOString() : ''); return; }
        const number = Number(e.target.value);
        e.target.setCustomValidity(schema.type === 'integer' && e.target.value !== '' && !Number.isSafeInteger(number)
          ? '정확하게 표현할 수 있는 정수 범위로 입력하세요.' : '');
        change((schema.type === 'integer' || schema.type === 'number') && e.target.value !== '' ? number : e.target.value);
      }} />);
  return <div className="field">
    {reference ? <div className="reference-label">{(labels[name] ?? name).replace(/ ID$/, '')} 선택{nonEmpty && !nullable ? ' *' : ''}</div> : label}
    {nullable && <label className="null-choice"><input type="checkbox" aria-label={`${labels[name] ?? name} 사용 안 함`} checked={value === null}
      onChange={e => change(e.target.checked ? null : defaults(schema))} />사용 안 함</label>}
    {reference ? <>{assistance}<span className="reference-status">{value ? '대상이 입력에 연결됐습니다.' : value === null && nullable ? '사용하지 않습니다.' : '목록에서 대상을 선택하세요.'}</span>
      {selectionError && <small role="alert" className="error">{selectionError}</small>}
      <details className="reference-manual"><summary>직접 ID 입력 · 고급</summary>{label}{control}</details></> : control}
    <small id={`hint-${name}`} className="field-hint">{reference ? '목록에서 선택하면 필요한 ID가 자동으로 채워집니다.' : hints[name] ?? schema.description ?? (schema.format === 'uuid'
      ? '선행 작업의 결과에서 이 항목을 연결하세요. 직접 ID 입력은 이미 값을 알고 있을 때 사용합니다.' : '명세의 조건에 맞는 값을 직접 입력하거나 선택하세요.')}
      {schema.minimum !== undefined && ` 최소 ${schema.minimum.toLocaleString('ko-KR')}.`}{schema.maximum !== undefined && ` 최대 ${schema.maximum.toLocaleString('ko-KR')}.`}
      {schema.minLength !== undefined && schema.minLength > 0 && ` ${schema.minLength}자 이상.`}{schema.maxLength !== undefined && ` ${schema.maxLength}자 이하.`}
      {schema.format === 'date-time' && ` 달력은 브라우저 시간대(${Intl.DateTimeFormat().resolvedOptions().timeZone})로 입력하며 서버에는 시간대를 포함해 보냅니다.`}</small>
    {!reference && assistance}
  </div>;
}

function localTime(value: Json): string {
  if (typeof value !== 'string' || !value) return '';
  const date = new Date(value); if (!Number.isFinite(date.getTime())) return '';
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 19);
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
export function Fields({ schema: input, value, change, assistance }: { schema: Schema; value: Json; change: (v: Json) => void; assistance?: (name: string) => ReactNode }) {
  const schema = resolve(input); const object = (value && typeof value === 'object' && !Array.isArray(value) ? value : {}) as Record<string, Json>;
  return <div className="fields">{Object.entries(schema.properties ?? {}).map(([key, child]) =>
    <Field key={key} name={key} schema={child} value={object[key] ?? null} required={schema.required?.includes(key)}
      change={v => change({ ...object, [key]: v })} assistance={assistance?.(key)} />)}</div>;
}
