package com.commercesettle.commerce.domain.model;

/** 쿠폰 캠페인의 신규 발급 가능 여부. 이미 발급한 쿠폰의 사용에는 영향을 주지 않는다. */
public enum CouponTemplateStatus {
  ACTIVE,
  CLOSED
}
