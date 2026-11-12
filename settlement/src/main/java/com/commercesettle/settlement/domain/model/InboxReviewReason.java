package com.commercesettle.settlement.domain.model;

/** 업무 Inbox의 확인 사유. 알려지지 않았거나 기록되지 않은 사유는 재판정하지 않는다. */
public enum InboxReviewReason {
  AMOUNT_CONTRACT_MISMATCH(Category.CONTRACT),
  PURCHASE_TIME_CONTRACT_MISMATCH(Category.CONTRACT),
  PAYMENT_ALREADY_RECORDED_WITH_DIFFERENT_VALUES(Category.SOURCE_CONFLICT),
  PURCHASE_DOES_NOT_MATCH_PAYMENT(Category.SOURCE_CONFLICT),
  PURCHASE_ALREADY_RECORDED_WITH_DIFFERENT_VALUES(Category.SOURCE_CONFLICT),
  REFUND_ALREADY_RESERVED_WITH_DIFFERENT_VALUES(Category.SOURCE_CONFLICT),
  REFUND_ALREADY_REQUESTED_WITH_DIFFERENT_VALUES(Category.SOURCE_CONFLICT),
  REFUND_AMOUNT_MISMATCH(Category.SOURCE_CONFLICT),
  REFUND_RESERVATION_RELEASED(Category.TERMINAL_CONFLICT),
  REFUND_RELEASE_NOT_ALLOWED(Category.TERMINAL_CONFLICT),
  REFUND_ALREADY_REVERSED(Category.TERMINAL_CONFLICT),
  PAYMENT_NOT_ARRIVED(Category.ORDER_DEPENDENT),
  REFUND_REQUEST_UNDER_REVIEW(Category.ORDER_DEPENDENT),
  REFUND_NOT_RESERVED(Category.ORDER_DEPENDENT),
  REFUND_RESERVATION_NOT_FOUND(Category.ORDER_DEPENDENT),
  PURCHASE_DURING_REFUND(Category.ORDER_DEPENDENT);

  private final Category category;

  InboxReviewReason(Category category) {
    this.category = category;
  }

  public static Category categoryOf(String code) {
    try {
      return valueOf(code).category;
    } catch (IllegalArgumentException | NullPointerException e) {
      return Category.UNRECORDED;
    }
  }

  public enum Category {
    CONTRACT,
    SOURCE_CONFLICT,
    TERMINAL_CONFLICT,
    ORDER_DEPENDENT,
    UNRECORDED
  }
}
