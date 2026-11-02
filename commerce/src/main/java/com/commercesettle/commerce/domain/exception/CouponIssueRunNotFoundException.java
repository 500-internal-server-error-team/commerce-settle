package com.commercesettle.commerce.domain.exception;

import java.util.UUID;

public class CouponIssueRunNotFoundException extends RuntimeException {
  public CouponIssueRunNotFoundException(UUID runId) {
    super("coupon issue run not found: " + runId);
  }
}
