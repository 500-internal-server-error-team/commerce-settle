package com.commercesettle.payment.application.service;

import java.time.Duration;

/** 결제 처리의 일시 오류 재시도. n번째 실패 뒤 1초부터 두 배씩 늘리고 60초를 넘지 않는다. */
final class PaymentRetryPolicy {
  private PaymentRetryPolicy() {}

  static Duration delayAfter(int attempts) {
    return Duration.ofSeconds(Math.min(60, 1L << Math.min(attempts - 1, 6)));
  }
}
