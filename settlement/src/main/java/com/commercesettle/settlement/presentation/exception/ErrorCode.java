package com.commercesettle.settlement.presentation.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/** 공통 오류 계약의 code 목록. 상수 이름이 응답 code 값이다. 업무 오류 코드는 해당 API를 구현할 때 추가한다. */
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
  SETTLEMENT_INBOX_TARGETS_CHANGED(HttpStatus.CONFLICT, "조건·대상 수·버전이 달라졌습니다. 목록을 다시 확인해 주세요."),
  SETTLEMENT_INBOX_NOT_REJUDGEABLE(HttpStatus.CONFLICT, "근거를 확인할 수 있는 순서 의존 사유만 재판정할 수 있습니다."),
  SETTLEMENT_INBOX_REJUDGEMENT_ACTIVE(HttpStatus.CONFLICT, "같은 대상을 소유한 미완료 재판정 실행이 있습니다."),
  SETTLEMENT_INBOX_RUN_STATE_CONFLICT(HttpStatus.CONFLICT, "현재 실행 상태에서는 요청한 전환을 할 수 없습니다."),
  PAYOUT_JOB_LEASE_ACTIVE(HttpStatus.CONFLICT, "지급 작업이 실행 중입니다. 실행이 끝난 뒤 다시 요청해 주세요."),
  PAYOUT_JOB_NOT_REPLAYABLE(HttpStatus.CONFLICT, "현재 지급 작업 상태에서는 재처리할 수 없습니다."),
  SETTLEMENT_BATCH_NOT_DUE(HttpStatus.CONFLICT, "배치 마감이 아직 지나지 않았습니다."),
  SETTLEMENT_POLICY_UNSUPPORTED(HttpStatus.CONFLICT, "지원하지 않는 정산 정책 버전입니다."),
  SETTLEMENT_NO_ELIGIBLE_ITEMS(HttpStatus.CONFLICT, "마감 전 지급 대상 항목이 없습니다."),
  QUARANTINE_REPLAY_ACTIVE(HttpStatus.CONFLICT, "같은 격리 원문의 재처리가 아직 발행 중입니다. 발행이 끝난 뒤 다시 요청해 주세요."),
  QUARANTINE_DELIVERY_NOT_RESUMABLE(HttpStatus.CONFLICT, "시도를 다 쓴 발행 작업만 재개할 수 있습니다."),
  SETTLEMENT_PAYOUT_NOT_ALLOWED(HttpStatus.CONFLICT, "지급 전 검증을 통과하지 못했습니다."),
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
      case 503 -> COMMON_TEMPORARILY_UNAVAILABLE;
      default -> COMMON_INTERNAL_ERROR;
    };
  }
}
