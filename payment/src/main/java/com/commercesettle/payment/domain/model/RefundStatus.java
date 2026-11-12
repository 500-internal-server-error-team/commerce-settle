package com.commercesettle.payment.domain.model;

/**
 * 금융 환불 상태. 정상 흐름은 RESERVING → EXTERNAL_PENDING → RESTORING → INTERNAL_PENDING → COMPLETED이다. 외부 환불
 * 확정 거절은 COMPENSATING을 거쳐 DECLINED, 외부 결과 불명은 UNKNOWN이다. 정산금 예약이 거절되면 외부 호출 없이 DECLINED다.
 */
public enum RefundStatus {
  RESERVING,
  EXTERNAL_PENDING,
  RESTORING,
  INTERNAL_PENDING,
  COMPLETED,
  COMPENSATING,
  DECLINED,
  UNKNOWN;

  public boolean canTransitionTo(RefundStatus next) {
    return switch (this) {
      case RESERVING -> next == EXTERNAL_PENDING || next == DECLINED;
      case EXTERNAL_PENDING -> next == RESTORING || next == COMPENSATING || next == UNKNOWN;
      case UNKNOWN -> next == RESTORING || next == COMPENSATING;
      case RESTORING -> next == INTERNAL_PENDING;
      case INTERNAL_PENDING -> next == COMPLETED;
      case COMPENSATING -> next == DECLINED;
      case COMPLETED, DECLINED -> false;
    };
  }
}
