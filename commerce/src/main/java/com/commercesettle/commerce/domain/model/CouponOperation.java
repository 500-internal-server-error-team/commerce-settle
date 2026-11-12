package com.commercesettle.commerce.domain.model;

/** 쿠폰 이력의 작업 종류. */
public enum CouponOperation {
  ISSUE,
  RESERVE,
  USE,
  RELEASE,
  RESTORE,
  EXPIRE
}
