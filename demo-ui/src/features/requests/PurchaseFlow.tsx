import { useEffect, useRef, type ReactNode } from 'react';
import type { Execution } from '../../shared/api/client';
import type { Json } from '../../shared/api/contract';
import { ResultView } from '../results/ResultView';

const titles = ['상품 선택', '견적 확인', '주문 생성', '결제·배송 상태 확인'];

export function PurchaseFlow({ active, records, productId, children }: {
  active: number; records: Partial<Record<number, Execution>>; productId?: Json; children: ReactNode;
}) {
  const previous = useRef(active);
  const products = records[0]?.response as { items?: Record<string, Json>[] } | undefined;
  const product = products?.items?.find(row => row.id === productId);
  useEffect(() => {
    if (previous.current === active) return;
    previous.current = active;
    const heading = document.getElementById(`purchase-step-${active}`);
    heading?.focus({ preventScroll: true });
    if (heading) window.scrollTo({ top: heading.getBoundingClientRect().top + window.scrollY - 24,
      behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
  }, [active]);
  return <ol className="purchase-flow" aria-label="정상 구매 단계">
    {titles.map((title, index) => <li key={title} className={`purchase-step ${index === active ? 'active' : ''}`}
      aria-current={index === active ? 'step' : undefined}>
      <div className="purchase-step-heading"><span className="purchase-step-number">{index + 1}</span>
        <h2 id={`purchase-step-${index}`} tabIndex={-1}>{title}</h2>
        <span className="caption">{index < active ? '응답 확인' : index === active ? '현재 단계' : '실행 전'}</span></div>
      {index < active && records[index] && <div className="purchase-step-result">
        {index === 0 && productId && <p className="caption">선택한 상품 · {String(product?.name ?? productId)}</p>}
        <details><summary>HTTP {records[index].status} · 이전 결과 보기</summary>
          <ResultView data={records[index].response as Json} /></details>
      </div>}
      {index === active && children}
    </li>)}
  </ol>;
}
