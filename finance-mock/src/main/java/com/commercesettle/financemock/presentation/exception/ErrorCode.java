package com.commercesettle.financemock.presentation.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/** 공통 오류 계약의 code 목록. 상수 이름이 응답 code 값이다. */
enum ErrorCode {
  COMMON_VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
  COMMON_RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 대상을 찾을 수 없습니다."),
  COMMON_IDEMPOTENCY_CONFLICT(
      HttpStatus.CONFLICT, "같은 업무 키의 다른 요청이 있거나 처리 중입니다. 같은 본문이면 원거래를 조회해 주세요."),
  MOCK_TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "조회 시점에 해당 거래 기록이 없습니다. 금융 실패나 취소 확정이 아닙니다."),
  MOCK_SCENARIO_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "요청한 시나리오 설정이 없습니다."),
  MOCK_LOOKUP_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "거래를 조회하지 못했습니다. 같은 업무 키로 다시 조회해 주세요."),
  MOCK_FUNDS_NOT_PREPARED(HttpStatus.INTERNAL_SERVER_ERROR, "시험 지급 재원이 준비되지 않았습니다."),
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

  /** 프레임워크 오류에 붙일 상태별 공통 코드. */
  static ErrorCode forStatus(HttpStatusCode status) {
    return switch (status.value()) {
      case 400 -> COMMON_VALIDATION_FAILED;
      case 404 -> COMMON_RESOURCE_NOT_FOUND;
      case 503 -> COMMON_TEMPORARILY_UNAVAILABLE;
      default -> COMMON_INTERNAL_ERROR;
    };
  }
}
