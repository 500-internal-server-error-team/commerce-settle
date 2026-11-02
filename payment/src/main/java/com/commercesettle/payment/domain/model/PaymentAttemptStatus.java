package com.commercesettle.payment.domain.model;

public enum PaymentAttemptStatus {
  PENDING,
  PROCESSING,
  UNKNOWN,
  APPROVED,
  DECLINED,
  CANCELLED
}
