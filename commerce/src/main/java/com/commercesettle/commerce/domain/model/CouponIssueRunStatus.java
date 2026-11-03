package com.commercesettle.commerce.domain.model;

/** 월간 발급 회차 상태. 대상 확정·검증을 마친 뒤에만 ISSUING이 된다. */
public enum CouponIssueRunStatus {
  PREPARING,
  ISSUING,
  COMPLETED,
  EXPIRED,
  CANCELLED
}
