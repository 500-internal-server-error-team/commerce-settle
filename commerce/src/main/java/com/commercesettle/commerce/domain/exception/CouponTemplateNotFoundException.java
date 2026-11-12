package com.commercesettle.commerce.domain.exception;

import java.util.UUID;

/** 캠페인이 없거나 요청한 판매자의 판매자 쿠폰 캠페인이 아니다. */
public class CouponTemplateNotFoundException extends RuntimeException {
  public CouponTemplateNotFoundException(UUID templateId) {
    super("coupon template not found: " + templateId);
  }
}
