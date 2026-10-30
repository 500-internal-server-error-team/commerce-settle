package com.commercesettle.payment.application.event;

import java.util.Objects;
import java.util.UUID;

/** 결제 접수 결과로 주문에 결제 ID를 연결하는 이벤트 본문. OpenAPI PaymentAcceptedPayload와 같은 4필드다. */
public record PaymentAcceptedPayload(
    UUID paymentId, UUID orderId, long orderVersion, UUID paymentCycleId) {

  public PaymentAcceptedPayload {
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(paymentCycleId, "paymentCycleId");
    if (orderVersion < 0) {
      throw new IllegalArgumentException("orderVersion must not be negative");
    }
  }
}
