package com.commercesettle.payment.application.port;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.event.PaymentCancellationRequestedPayload;
import com.commercesettle.payment.application.event.RefundApprovedPayload;
import com.commercesettle.payment.application.event.RefundOrderRestoredPayload;
import java.util.UUID;

/**
 * 처리기가 선점한 WAITING Inbox 행과 원문에서 다시 읽은 이벤트. version과 attempts는 선점을 반영한 값이다. attempts는 이번 시도를 포함하고,
 * version은 처리·실패 기록이 그 사이 다른 처리기가 선점한 행을 덮어쓰지 않게 한다.
 */
public record WaitingInboxEvent(UUID inboxId, long version, int attempts, EventEnvelope<?> event) {

  /** 처리 전에 잡는 주문 단위 잠금의 대상. 같은 주문의 결제 요청·취소·환불은 이 순서로 직렬화된다. */
  public UUID orderId() {
    return switch (event.payload()) {
      case OrderPaymentRequestedPayload request -> request.orderId();
      case PaymentCancellationRequestedPayload request -> request.orderId();
      case RefundApprovedPayload approved -> approved.orderId();
      case RefundOrderRestoredPayload restored -> restored.orderId();
      default -> throw new IllegalArgumentException("Unsupported inbox payload");
    };
  }
}
