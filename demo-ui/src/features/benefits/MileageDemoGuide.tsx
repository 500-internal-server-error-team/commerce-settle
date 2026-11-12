// 시연 지갑은 Payment의 시연 지갑 fixture가 준비한다. 현재 잔액·적립 예정액은 지갑 조회 응답으로만 확인한다.
const mileageCases = new Set(['benefit.mixed', 'benefit.allmileage', 'benefit.insufficient', 'history.mileage']);

export function showsMileageGuide(caseId: string, path?: string) {
  return mileageCases.has(caseId) || !!path?.startsWith('/api/buyers/me/mileage');
}

export function MileageDemoGuide() {
  return <section className="learning-start mileage-guide" aria-label="마일리지 시연 준비">
    <h2>마일리지 시연 계정</h2>
    <dl>
      <dt>시연 계정</dt><dd>구매자 A(<code>fixture-buyer-a@example.test</code>) · 초기 잔액 100,000점</dd>
      <dt>잔액 부족 확인</dt><dd>구매자 B(<code>fixture-buyer-b@example.test</code>) · 초기 잔액 0점</dd>
      <dt>준비 방법</dt><dd>기본 시연 구성에서 Payment가 기동할 때 지갑이 없으면 한 번만 만들고 지급(SEED) 이력을 남깁니다.
        이미 있는 지갑은 바꾸지 않으므로 사용한 잔액은 재기동해도 다시 채워지지 않습니다. 현재 잔액은 지갑 조회 응답으로 확인하세요.</dd>
      <dt>결제·환불 처리</dt><dd>마일리지 예약·차감·복원은 금융 흐름 구성(<code>compose.financial-flow.yml</code>)을 켠 환경에서 진행됩니다.</dd>
    </dl>
    <p><strong>현재 제공하지 않음</strong> · 마일리지 충전·현금 인출·만료</p>
    <p><strong>구매 적립</strong> · 쿠폰 없는 주문은 결제 완료 뒤 적립 예정액(<code>pendingAccrualAmount</code>)으로 표시되고 자동 구매확정 뒤 잔액에 더해집니다.
      업무 시간 이동 기능이 없어 시연 중에는 사용 가능 전환까지 확인할 수 없습니다.</p>
  </section>;
}
