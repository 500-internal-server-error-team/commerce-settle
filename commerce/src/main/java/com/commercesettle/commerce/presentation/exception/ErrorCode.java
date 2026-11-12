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
  PAYMENT_STATE_CONFLICT(HttpStatus.CONFLICT, "현재 주문 상태에서 처리할 수 없습니다."),
  NOTIFICATION_REPLAY_CONFLICT(HttpStatus.CONFLICT, "현재 알림 상태에서 재처리할 수 없습니다."),
  ORDER_REFUND_WINDOW_CLOSED(HttpStatus.CONFLICT, "환불 요청 기한이 지났습니다."),
  ORDER_REFUND_IN_PROGRESS(HttpStatus.CONFLICT, "진행 중인 취소·반품이 있습니다."),
  INVENTORY_INSUFFICIENT(HttpStatus.CONFLICT, "가용 재고가 부족합니다."),
  COUPON_EXPIRED(HttpStatus.CONFLICT, "사용 기한이 지난 쿠폰입니다."),
  COUPON_NOT_APPLICABLE(HttpStatus.CONFLICT, "이 주문에 사용할 수 없는 쿠폰입니다."),
  COUPON_BUDGET_EXHAUSTED(HttpStatus.CONFLICT, "캠페인의 발급 수나 할인 예산이 소진됐습니다."),
  COUPON_ALREADY_ISSUED(HttpStatus.CONFLICT, "이미 이 캠페인의 쿠폰을 받은 구매자입니다."),
  COUPON_CAMPAIGN_NOT_ISSUABLE(HttpStatus.CONFLICT, "지금은 이 캠페인으로 발급할 수 없습니다."),
  COUPON_RUN_NOT_REPLACEABLE(HttpStatus.CONFLICT, "발급 이력이 있거나 만료·취소된 회차는 교정할 수 없습니다."),
  COUPON_RUN_EXECUTION_ACTIVE(
      HttpStatus.CONFLICT, "실행 중이거나 진행 중으로 남은 실행이 있는 회차입니다. 정상 중단·복구 뒤 다시 요청해 주세요."),
  COUPON_RUN_VERSION_CONFLICT(HttpStatus.CONFLICT, "회차가 먼저 바뀌었습니다. 최신 상태를 조회한 뒤 다시 요청해 주세요."),
  COUPON_RUN_EXPIRED(HttpStatus.CONFLICT, "원래 발급 기한이 지난 회차입니다."),
  COUPON_RUN_STATE_UNVERIFIED(HttpStatus.SERVICE_UNAVAILABLE, "회차의 실행·정책·발급 이력 상태를 확인할 수 없습니다."),
  EVENT_ADMISSION_CLOSED(HttpStatus.CONFLICT, "이벤트에 참여할 수 없습니다."),
  PRODUCT_EVENT_IN_PROGRESS(
      HttpStatus.CONFLICT, "공개 후 종료 전 이벤트가 있는 상품은 바꿀 수 없습니다. 이벤트를 먼저 종료해 주세요."),
  ACCOUNT_EMAIL_DUPLICATED(HttpStatus.CONFLICT, "이미 가입한 이메일입니다."),
  COMMON_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 많습니다. Retry-After 이후 다시 시도해 주세요."),
  COMMON_TEMPORARILY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "요청을 처리할 수 없습니다."),
  COMMON_CAPACITY_EXCEEDED(HttpStatus.SERVICE_UNAVAILABLE, "신규 접수가 일시 중단됐습니다. 잠시 후 다시 시도해 주세요."),
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
