package com.commercesettle.commerce.domain.model;

/** 회차 대상의 처리 결과. 원래 기한이 지나면 새 쿠폰 없이 EXPIRED로 종결한다. */
public enum CouponIssueTargetStatus {
  PENDING,
  ISSUED,
  EXPIRED
}
