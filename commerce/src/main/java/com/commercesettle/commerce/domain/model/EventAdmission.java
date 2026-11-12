package com.commercesettle.commerce.domain.model;

/** 게이지에 표시하는 신청 상태. WAITING_PAYMENTS는 남은 수량이 모두 결제 대기 예약이라 일시 마감된 상태이며 매진이 아니다. */
public enum EventAdmission {
  OPEN,
  WAITING_PAYMENTS,
  SOLD_OUT,
  NOT_STARTED,
  CLOSED
}
