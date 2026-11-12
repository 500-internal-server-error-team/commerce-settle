package com.commercesettle.payment.domain.model;

public enum PaymentJobStatus {
  PENDING,
  RUNNING,
  RETRY,
  REVIEW_REQUIRED,
  DONE
}
