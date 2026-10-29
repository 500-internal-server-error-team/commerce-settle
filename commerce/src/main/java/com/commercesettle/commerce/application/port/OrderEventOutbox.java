package com.commercesettle.commerce.application.port;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.application.event.OrderPaymentRequestedPayload;
import java.util.UUID;

public interface OrderEventOutbox {
  /** 주문 상태 저장과 같은 트랜잭션에서 호출한다. 반환값은 저장한 eventId다. */
  UUID appendPaymentRequested(EventEnvelope<OrderPaymentRequestedPayload> event);
}
