package com.commercesettle.payment.application.dto;

import java.time.Duration;
import java.util.Objects;

/** 환불 Worker 설정. 호출 제한 시간은 임대보다 짧아야 한다. */
public record RefundWorkerSettings(Duration leaseDuration, Duration httpTimeout, int maxAttempts) {
  public RefundWorkerSettings {
    Objects.requireNonNull(leaseDuration, "leaseDuration");
    Objects.requireNonNull(httpTimeout, "httpTimeout");
    if (httpTimeout.isZero()
        || httpTimeout.isNegative()
        || httpTimeout.compareTo(leaseDuration) >= 0
        || maxAttempts < 1) {
      throw new IllegalArgumentException("Invalid refund worker settings");
    }
  }
}
