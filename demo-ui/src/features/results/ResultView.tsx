import type { Json } from '../../shared/api/contract';
import { redact } from '../../shared/api/contract';
import { codeLabel } from '../../shared/ui/labels';

const labels: Record<string, string> = { totalAmount: '결제 금액', grossAmount: '상품대금', couponAmount: '할인액',
  externalAmount: '외부 결제', mileageAmount: '사용 마일리지', settlementBaseAmount: '정산 기준액', feeAmount: '수수료',
  netAmount: '판매자 정산액', amount: '금액', balance: '잔액', reservedAmount: '예약액', availableAmount: '가용액',
  rankNo: '순위', externalSpend: '전월 외부 결제액', grade: '혜택 등급', discountAmount: '할인액',
  targetCount: '선정 인원', issuedCount: '발급 완료', quota: '한정 수량', soldQuantity: '판매 확정',
  reservedQuantity: '예약', progressPercent: '판매 진행률', status: '상태', fulfillmentStatus: '배송 상태',
  deliveryDueAt: '모의 배송 예정', refundDeadlineAt: '반품 마감', purchaseConfirmedAt: '구매확정',
  readyAt: '정산 준비', cutoffAt: '마감', succeededAt: '지급 성공', asOf: '조회 기준', version: '버전',
  id: 'ID', name: '이름', productName: '상품명', kind: '종류', funder: '할인 부담', targetMonth: '혜택 월',
  productId: '상품 ID', buyerCouponId: '쿠폰 ID', orderId: '주문 ID', createdAt: '생성 시각', updatedAt: '변경 시각',
  eventId: '이벤트 ID', recipient: '수령인', batchDate: '정산 마감일', issuedAt: '발급 시각', validUntil: '사용 종료',
  unitPrice: '상품 가격', eventPrice: '행사 가격', totalBudget: '총 할인 예산', availableQuantity: '판매 가능 수량' };
const money = /Amount$|Spend$|balance|Balance$|Budget$|unitPrice|eventPrice/;
export function format(key: string, value: unknown): string {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'number') return `${value.toLocaleString('ko-KR')}${money.test(key) ? '원' : ''}`;
  if (typeof value === 'string' && /^-?\d+$/.test(value) && money.test(key)) return `${BigInt(value).toLocaleString('ko-KR')}원`;
  if (typeof value === 'object') return JSON.stringify(redact(value));
  if (typeof value === 'string' && /status$|^kind$|^funder$|^externalMethod$|^actorKind$/i.test(key)) return codeLabel(value);
  return String(value);
}
export function ResultView({ data, select }: { data: Json; select?: (row: Record<string, Json>) => void }) {
  if (!data || typeof data !== 'object') return <p className="empty">반환 본문이 없습니다.</p>;
  const object = data as Record<string, Json>;
  const rows = Array.isArray(data) ? data : Array.isArray(object.items) ? object.items : null;
  if (rows) {
    if (!rows.length) return <div className="empty"><strong>아직 조회된 내역이 없습니다</strong><p>데이터를 준비하거나 다른 조회 조건을 선택하세요.</p></div>;
    const records = rows.filter(v => v && typeof v === 'object' && !Array.isArray(v)) as Record<string, Json>[];
    const columns = [...new Set(records.flatMap(r => Object.keys(r)))].filter(k => !/password|token|address|body/i.test(k)).slice(0, 8);
    return <><div className="table-scroll"><table><thead><tr>{select && <th>선택</th>}{columns.map(k => <th key={k}>{labels[k] ?? k}</th>)}</tr></thead>
      <tbody>{records.map((row, index) => <tr key={String(row.id ?? index)}>{select && <td><button className="text-button" onClick={() => select(row)}>입력에 연결</button></td>}
        {columns.map(k => <td key={k} title={format(k, row[k])}>{format(k, row[k])}</td>)}</tr>)}</tbody></table></div>
      {object.totalPages !== undefined && <p className="caption">페이지 {Number(object.page) + 1} / {String(object.totalPages)} · 총 {String(object.totalElements)}건 · 다음 조회의 page 값을 변경할 수 있습니다.</p>}</>;
  }
  const isMoney = (key: string, value: Json) => money.test(key) && (typeof value === 'number' || typeof value === 'string' && /^-?\d+$/.test(value));
  const amounts = Object.entries(object).filter(([k, v]) => isMoney(k, v));
  return <>{amounts.length > 0 && <div className="metrics">{amounts.map(([k, v]) =>
    <div key={k} className="metric"><span>{labels[k] ?? k}</span><strong>{format(k, v)}</strong></div>)}</div>}
    <dl className="details">{Object.entries(object).filter(([k, v]) => !isMoney(k, v) && !/password|token/i.test(k)).map(([k, v]) =>
      <div key={k}><dt>{labels[k] ?? k}</dt><dd>{format(k, v)}</dd></div>)}</dl>
    {select && <button className="secondary" onClick={() => select(object)}>ID·버전을 입력에 연결</button>}</>;
}
