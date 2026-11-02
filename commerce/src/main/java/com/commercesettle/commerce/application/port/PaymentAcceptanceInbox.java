package com.commercesettle.commerce.application.port;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.application.event.PaymentAcceptedPayload;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 원문 인계와 주문 ID 연결의 처리 대기를 Commerce DB에 보존한다. */
public interface PaymentAcceptanceInbox {
  void saveWaiting(EventEnvelope<PaymentAcceptedPayload> event, Source source, Instant now);

  /** eventId가 null이면 도래한 한 건을 선점하고, 다른 처리기의 잠긴 행은 건너뛴다. */
  Optional<Waiting> claim(UUID eventId, Instant now);

  void applied(UUID inboxId, UUID orderId, Instant now);

  void review(UUID inboxId, String errorCode, Instant now);

  /** WAITING인 현재 행에만 시도 횟수·1~60초 지수 대기 또는 10회 소진 결과를 기록한다. */
  void retry(UUID inboxId, String errorCode, Instant now);

  record Waiting(UUID id, EventEnvelope<PaymentAcceptedPayload> event) {}

  record Source(String topic, int partition, long offset, byte[] body) {
    public Source {
      Objects.requireNonNull(body, "body");
      if (!"commerce-settle.payment.v1".equals(topic) || partition < 0 || offset < 0) {
        throw new IllegalArgumentException("Invalid payment event source");
      }
      body = body.clone();
    }

    @Override
    public byte[] body() {
      return body.clone();
    }

    @Override
    public String toString() {
      return "Source[topic=%s, partition=%d, offset=%d, body=redacted]"
          .formatted(topic, partition, offset);
    }
  }
}
