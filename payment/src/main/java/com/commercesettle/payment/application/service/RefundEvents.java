package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.RefundEventPayload;
import com.commercesettle.payment.application.port.PaymentEventOutbox;
import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.model.Refund;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 환불 이벤트 Envelope를 만들어 Outbox에 남긴다. eventId는 이벤트 종류와 환불 ID에서 고정해 같은 단계의 재처리가 한 건으로 합쳐진다. 집계는 원결제이며,
 * 확정 뒤 바뀌지 않는 결제 버전을 집계 버전으로 써 결제 이벤트보다 뒤에 놓는다.
 */
@Component
public class RefundEvents {
  private final PaymentEventOutbox outbox;

  RefundEvents(PaymentEventOutbox outbox) {
    this.outbox = outbox;
  }

  void append(
      String eventType, Payment payment, Refund refund, RefundEventPayload payload, Instant now) {
    outbox.appendRefundEvent(
        new EventEnvelope<>(
            eventId(eventType, refund),
            eventType,
            1,
            now.atOffset(ZoneOffset.UTC),
            payment.getId(),
            Objects.requireNonNullElse(payment.getVersion(), 0L),
            payload));
  }

  static UUID eventId(String eventType, Refund refund) {
    return UUID.nameUUIDFromBytes(
        (eventType + ":" + refund.getId()).getBytes(StandardCharsets.UTF_8));
  }
}
