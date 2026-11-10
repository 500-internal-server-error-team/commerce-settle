package com.commercesettle.payment.domain.model;

/** 구매 적립 상태. PENDING에서 구매확정이면 AVAILABLE, 확정 전 환불 완료면 CANCELLED로 한 번만 바뀐다. */
public enum MileageAccrualStatus {
  PENDING,
  AVAILABLE,
  CANCELLED
}
