package com.commercesettle.commerce.domain.exception;

import java.util.Objects;

/** 판매자 캠페인의 현재 상태·기간·한도 때문에 발급이나 종료를 반영할 수 없다. */
public class CouponCampaignConflictException extends RuntimeException {
  public enum Reason {
    /** 신규 발급을 종료한 캠페인이다. */
    CLOSED,
    /** 발급 시작 전이다. */
    NOT_STARTED,
    /** 발급 기간이 끝났다. */
    ENDED,
    /** 같은 캠페인의 쿠폰을 이미 받은 구매자다. */
    ALREADY_ISSUED,
    /** 발급 수나 할인 예산이 소진됐다. */
    EXHAUSTED
  }

  private final Reason reason;

  public CouponCampaignConflictException(Reason reason) {
    super("coupon campaign conflict: " + reason);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public Reason reason() {
    return reason;
  }
}
