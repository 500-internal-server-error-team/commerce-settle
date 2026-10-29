package com.commercesettle.gateway.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 인증 저장소로 판정할 수 없어 요청을 백엔드에 전달하지 않았다. */
public class AuthenticationUnavailableException extends ResponseStatusException {
  public AuthenticationUnavailableException() {
    super(HttpStatus.SERVICE_UNAVAILABLE);
  }
}
