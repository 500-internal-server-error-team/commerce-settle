package com.commercesettle.payment.domain.exception;

/** 가용 마일리지보다 많이 예약하려 할 때 던진다. */
public class InsufficientMileageException extends RuntimeException {
  public InsufficientMileageException(long available, long requested) {
    super("가용 마일리지 " + available + "점보다 많은 " + requested + "점을 예약할 수 없습니다.");
  }
}
