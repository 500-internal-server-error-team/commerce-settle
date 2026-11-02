package com.commercesettle.payment.domain.model;

/** 환불 SAGA의 다음에 기다리는 일. 정산 응답을 기다리는 단계는 {@link #awaitsSettlement()}가 참이다. */
public enum RefundSagaStep {
  /** 정산금 예약 결과를 기다린다. */
  RESERVATION_REQUESTED,
  /** 예약이 확인됐고 외부 환불을 요청하거나 결과를 조회한다. */
  EXTERNAL_REFUND_PENDING,
  /** 외부 환불 성공(또는 생략) 뒤 마일리지 복원을 기다린다. */
  MILEAGE_RESTORE_PENDING,
  /** 금융 환불을 끝내고 정산 원장 상쇄 확인을 기다린다. */
  LEDGER_REVERSAL_PENDING,
  /** 원장 상쇄를 확인하고 커머스 주문 복구 확인을 기다린다. */
  ORDER_RESTORE_PENDING,
  COMPLETED,
  /** 외부 환불이 확정 거절돼 정산금 예약 해제 확인을 기다린다. */
  RELEASE_PENDING,
  /** 예약 거절이나 예약 해제 확인 뒤 실패로 끝났다. */
  FAILED;

  public boolean awaitsSettlement() {
    return this == RESERVATION_REQUESTED
        || this == LEDGER_REVERSAL_PENDING
        || this == RELEASE_PENDING;
  }
}
