package com.commercesettle.payment.application.port;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import java.util.UUID;

/**
 * 처리기가 선점한 WAITING Inbox 행과 원문에서 다시 읽은 이벤트. version과 attempts는 선점을 반영한 값이다. attempts는 이번 시도를 포함하고,
 * version은 처리·실패 기록이 그 사이 다른 처리기가 선점한 행을 덮어쓰지 않게 한다.
 */
public record WaitingInboxEvent(UUID inboxId, long version, int attempts, EventEnvelope<?> event) {

  /** 결제 요청이면 그 본문을, 아니면 빈 값을 돌려준다. */
  public java.util.Optional<OrderPaymentRequestedPayload> paymentRequest() {
    return event.payload() instanceof OrderPaymentRequestedPayload payload
        ? java.util.Optional.of(payload)
        : java.util.Optional.empty();
  }
}
