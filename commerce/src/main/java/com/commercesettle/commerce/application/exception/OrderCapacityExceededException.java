package com.commercesettle.commerce.application.exception;

/** 신규 주문 접수 용량 부족. 응답의 {@code Retry-After}로 재시도 전 대기 초를 알린다. */
public final class OrderCapacityExceededException extends RuntimeException {
  /** 관측 기반 신규 접수 제한의 재시도 대기 초. */
  public static final long ADMISSION_RETRY_AFTER_SECONDS = 30;

  private final long retryAfterSeconds;

  public OrderCapacityExceededException() {
    this(ADMISSION_RETRY_AFTER_SECONDS);
  }

  public OrderCapacityExceededException(long retryAfterSeconds) {
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
