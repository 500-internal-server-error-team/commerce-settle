package com.commercesettle.settlement.domain.model;

public enum PayoutJobStatus {
  PENDING,
  RUNNING,
  RETRY,
  REVIEW_REQUIRED,
  DONE
}
