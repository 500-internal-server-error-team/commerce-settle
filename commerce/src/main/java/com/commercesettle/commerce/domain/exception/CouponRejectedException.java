package com.commercesettle.commerce.domain.exception;

import java.util.Objects;

/** 이 주문에 쿠폰을 쓸 수 없다. 쿠폰 상태·이력·예산을 바꾸지 않고 거절한다. */
public class CouponRejectedException extends RuntimeException {
  public enum Reason {
    /** 사용 기한이 지났다. */
    EXPIRED,
    /** 사용 시작 전이다. */
    NOT_STARTED,
    /** 다른 주문에 예약됐거나 이미 사용·만료 처리됐다. */
    NOT_AVAILABLE,
    /** 요청한 구매자의 쿠폰이 아니다. */
    NOT_OWNED,
    /** 쿠폰이 허용하지 않는 결제수단이다. */
    METHOD_NOT_ALLOWED,
    /** 판매자 쿠폰의 판매자·지정 상품과 다르다. */
    PRODUCT_MISMATCH,
    /** 쿠폰 적용 전 상품대금이 최소 주문금액보다 작다. */
    BELOW_MIN_ORDER
  }

  private final Reason reason;

  public CouponRejectedException(Reason reason) {
    super("coupon rejected: " + reason);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public Reason reason() {
    return reason;
  }
}
