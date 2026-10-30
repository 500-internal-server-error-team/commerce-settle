package com.commercesettle.commerce.application.dto;

import com.commercesettle.commerce.application.dto.CreateOrderCommand.ExternalMethod;
import java.util.UUID;

/** 정식 OrderCreate 입력. 쿠폰·이벤트 값은 일반 주문에서 null이어야 한다. */
public record RegularOrderCommand(
    UUID productId,
    int quantity,
    UUID eventId,
    UUID buyerCouponId,
    long mileageAmount,
    UUID addressId,
    long expectedProductVersion,
    Long expectedEventVersion,
    long expectedTotalAmount,
    ExternalMethod externalMethod,
    String methodAccountRef) {
  @Override
  public String toString() {
    return "RegularOrderCommand[redacted]";
  }
}
