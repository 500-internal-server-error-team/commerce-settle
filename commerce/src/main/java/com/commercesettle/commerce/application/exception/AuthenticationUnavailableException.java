package com.commercesettle.commerce.application.exception;

/** 인증 저장소 오류·시간 초과, 변경 중이거나 손상된 인증 가드. 인증 실패로 바꾸지 않고 503으로 거절한다. */
public class AuthenticationUnavailableException extends RuntimeException {
  public AuthenticationUnavailableException(String message) {
    super(message);
  }

  public AuthenticationUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
