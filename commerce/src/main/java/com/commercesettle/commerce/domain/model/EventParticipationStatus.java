package com.commercesettle.commerce.domain.model;

/** 선착순 이벤트 참여 상태. RELEASED만 같은 구매자가 기간 내 다시 참여할 수 있고, 환불(REFUNDED)은 참여 한도를 돌려주지 않는다. */
public enum EventParticipationStatus {
  RESERVED,
  CONFIRMED,
  RELEASED,
  REFUNDED
}
