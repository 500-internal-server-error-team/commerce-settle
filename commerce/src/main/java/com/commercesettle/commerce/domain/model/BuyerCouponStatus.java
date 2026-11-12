package com.commercesettle.commerce.domain.model;

/** 구매자 쿠폰 상태. 예약은 결제 회차별로, 사용·복구는 원결제별로 한 번 반영한다. */
public enum BuyerCouponStatus {
  ISSUED,
  RESERVED,
  USED,
  EXPIRED
}
