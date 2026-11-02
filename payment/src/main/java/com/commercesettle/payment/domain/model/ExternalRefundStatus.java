package com.commercesettle.payment.domain.model;

/**
 * 원외부 거래 환불의 진행. REQUESTED는 호출했을 수 있다는 기록이므로 재개할 때 원거래 조회부터 한다. 외부 결제액이 0원이면 NOT_REQUIRED이고 외부 대역을
 * 호출하지 않는다.
 */
public enum ExternalRefundStatus {
  NOT_REQUIRED,
  PENDING,
  REQUESTED,
  SUCCEEDED,
  DECLINED,
  UNKNOWN
}
