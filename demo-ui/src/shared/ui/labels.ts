const codeLabels: Record<string, string> = {
  BUYER: '구매자', SELLER: '판매자', OPERATOR: '운영자', PUBLIC: '로그인 없이',
  CREDIT_CARD: '신용카드', DEBIT_CARD: '체크카드', EASY_PAY: '간편결제',
  ACTIVE: '활성', INACTIVE: '비활성', DRAFT: '미공개', ON_SALE: '판매 중', STOPPED: '중지',
  APPROVE: '승인', REJECT: '거절', CANCEL: '취소', RETURN: '반품',
  WELCOME: '가입 혜택', GRADE: '월간 등급 혜택', PLATFORM: '플랫폼',
  AVAILABLE: '사용 가능', RESERVED: '예약 중', USED: '사용 완료', EXPIRED: '기간 만료', CLOSED: '종료',
  PENDING: '처리 대기', PROCESSING: '처리 중', APPROVED: '승인 완료', DECLINED: '확정 거절',
  SUCCEEDED: '성공', FAILED: '실패', UNKNOWN: '결과 미확정', RETRY: '재시도 대기',
  COMPLETED: '처리 완료', CANCELLED: '취소됨', REVIEW_REQUIRED: '확인 필요',
  COLLECTING: '알림 집계 중', SENT: '발송 확인', READY: '준비됨', HELD: '보류',
  INTERNAL_PENDING: '내부 반영 확인 중', SHIPPED: '발송됨', DELIVERED: '배송 완료',
  PURCHASE_CONFIRMED: '구매확정', PAYMENT_PENDING: '결제 대기', PAYMENT_FAILED: '결제 실패',
  AGGREGATION_ERROR: '집계 오류', PREPARING: '발급 준비', ISSUING: '발급 중',
  PAYOUT: '판매자 지급', WALLET: '마일리지', RUNNING: '실행 중', SENDING: '발송 중',
  PUBLISHED: '발행됨',
  ISSUED: '발급 완료', OPEN: '진행 중', SCHEDULED: '시작 예정', SOLD_OUT: '판매 수량 소진',
  PAID: '결제 완료', CONFIRMED: '확정', REFUNDED: '환불 완료',
  UNSHIPPED: '미발송', RETURN_RECEIVED: '반품 입고',
};

/** Human labels accompany the exact API codes used by this experiment UI. */
export function codeLabel(value: string): string {
  return codeLabels[value] ? `${codeLabels[value]} · ${value}` : value;
}

export function actorLabel(actor: string): string {
  return actor.split('|').map(value => codeLabels[value] ?? value).join(' · ');
}
