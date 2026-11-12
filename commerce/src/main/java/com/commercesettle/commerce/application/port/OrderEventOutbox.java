package com.commercesettle.commerce.application.port;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.application.event.OrderPaymentRequestedPayload;
import java.time.Instant;
import java.util.UUID;

public interface OrderEventOutbox {
  /** 주문 상태 저장과 같은 트랜잭션에서 호출한다. 반환값은 저장한 eventId다. */
  UUID appendPaymentRequested(EventEnvelope<OrderPaymentRequestedPayload> event);

  /** 원문 발생 시각과 분리해 최초 주문 시각을 관측 메타데이터로 전달한다. */
  default UUID appendPaymentRequested(
      EventEnvelope<OrderPaymentRequestedPayload> event, Instant orderCreatedAt) {
    return appendPaymentRequested(event);
  }

  UUID append(EventEnvelope<?> event, UUID messageKey);
}
