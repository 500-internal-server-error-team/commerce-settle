package com.commercesettle.payment.domain.exception;

/** 요청의 예상 버전이 현재 버전과 다르다. 응답 code는 COMMON_VERSION_CONFLICT다. */
public class VersionConflictException extends RuntimeException {
  public VersionConflictException() {
    super("Version conflict");
  }
}
