package com.commercesettle.payment.application.event;

import java.util.UUID;

/** Payment가 발행하는 환불 이벤트 본문. Kafka key와 Outbox 집계는 원결제 ID다. */
public interface RefundEventPayload {
  UUID refundId();

  UUID paymentId();
}
