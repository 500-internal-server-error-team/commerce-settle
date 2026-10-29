package com.commercesettle.commerce.presentation.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/** 공통 오류 계약의 code 목록. 상수 이름이 응답 code 값이며, 상황별 설명이 없을 때 기본 HTTP 상태와 설명을 쓴다. */
enum ErrorCode {
  COMMON_VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
  COMMON_AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
  COMMON_ROLE_FORBIDDEN(HttpStatus.FORBIDDEN, "이 작업을 수행할 권한이 없습니다."),
  COMMON_RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 대상을 찾을 수 없습니다."),
  COMMON_METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "요청을 처리할 수 없습니다."),
  COMMON_NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "요청을 처리할 수 없습니다."),
  COMMON_UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "요청을 처리할 수 없습니다."),
  COMMON_VERSION_CONFLICT(HttpStatus.CONFLICT, "다른 변경이 먼저 반영됐습니다. 최신 상태를 조회한 뒤 다시 요청해 주세요."),
  COMMON_IDEMPOTENCY_CONFLICT(
      HttpStatus.CONFLICT, "같은 멱등키의 다른 요청이 있거나 처리 중입니다. 같은 본문이면 잠시 후 다시 요청해 주세요."),
  INVENTORY_INSUFFICIENT(HttpStatus.CONFLICT, "가용 재고보다 많이 줄일 수 없습니다."),
  ACCOUNT_EMAIL_DUPLICATED(HttpStatus.CONFLICT, "이미 가입한 이메일입니다."),
  COMMON_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 많습니다. Retry-After 이후 다시 시도해 주세요."),
  COMMON_TEMPORARILY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "요청을 처리할 수 없습니다."),
  COMMON_INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "요청을 처리할 수 없습니다.");

  private final HttpStatus status;
  private final String detail;

  ErrorCode(HttpStatus status, String detail) {
    this.status = status;
    this.detail = detail;
  }

  HttpStatus status() {
    return status;
  }

  String detail() {
    return detail;
  }

  /** 프레임워크 오류에 붙일 상태별 공통 코드. 대응 코드가 없는 상태는 COMMON_INTERNAL_ERROR로 묶는다. */
  static ErrorCode forStatus(HttpStatusCode status) {
    return switch (status.value()) {
      case 400 -> COMMON_VALIDATION_FAILED;
      case 401 -> COMMON_AUTHENTICATION_REQUIRED;
      case 403 -> COMMON_ROLE_FORBIDDEN;
      case 404 -> COMMON_RESOURCE_NOT_FOUND;
      case 405 -> COMMON_METHOD_NOT_ALLOWED;
      case 406 -> COMMON_NOT_ACCEPTABLE;
      case 415 -> COMMON_UNSUPPORTED_MEDIA_TYPE;
      case 429 -> COMMON_RATE_LIMITED;
      case 503 -> COMMON_TEMPORARILY_UNAVAILABLE;
      default -> COMMON_INTERNAL_ERROR;
    };
  }
}
