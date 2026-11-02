package com.commercesettle.commerce.domain.exception;

import java.util.UUID;

/** 쿠폰을 받을 구매자가 없거나 활성 계정이 아니다. */
public class CouponRecipientNotFoundException extends RuntimeException {
  public CouponRecipientNotFoundException(UUID buyerId) {
    super("coupon recipient not found: " + buyerId);
  }
}
