package com.commercesettle.payment.application.port;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.MileageAccrualConfirmedPayload;
import com.commercesettle.payment.application.event.MileageAccrualScheduledPayload;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.event.PaymentCancellationRequestedPayload;
import com.commercesettle.payment.application.event.RefundApprovedPayload;
import com.commercesettle.payment.application.event.RefundLedgerReversedPayload;
import com.commercesettle.payment.application.event.RefundOrderRestoredPayload;
import com.commercesettle.payment.application.event.RefundReservationReleasedPayload;
import com.commercesettle.payment.application.event.RefundReservationResultPayload;
import java.time.Instant;
import java.util.UUID;

/**
 * 처리기가 선점한 WAITING Inbox 행과 원문에서 다시 읽은 이벤트. version과 attempts는 선점을 반영한 값이다. attempts는 이번 시도를 포함하고,
 * version은 처리·실패 기록이 그 사이 다른 처리기가 선점한 행을 덮어쓰지 않게 한다.
 */
public record WaitingInboxEvent(
    UUID inboxId, long version, int attempts, Instant registeredAt, EventEnvelope<?> event) {

  /**
   * 처리 전에 잡는 잠금의 대상. 같은 주문의 결제 요청·취소·환불·구매 적립은 주문 ID로 직렬화된다. Settlement 환불 응답에는 주문 ID가 없어 원결제 ID로
   * 잠근다. 같은 환불의 응답·주문 복구 확인은 반영 때 잡는 환불 행 잠금으로도 직렬화된다.
   */
  public UUID lockKey() {
    return switch (event.payload()) {
      case OrderPaymentRequestedPayload request -> request.orderId();
      case PaymentCancellationRequestedPayload request -> request.orderId();
      case RefundApprovedPayload approved -> approved.orderId();
      case RefundOrderRestoredPayload restored -> restored.orderId();
      // 같은 주문의 환불 승인·복구 확인과 직렬화해 적립 확정과 환불 완료가 엇갈리지 않게 한다.
      case MileageAccrualScheduledPayload scheduled -> scheduled.orderId();
      case MileageAccrualConfirmedPayload confirmed -> confirmed.orderId();
      case RefundReservationResultPayload reply -> reply.paymentId();
      case RefundLedgerReversedPayload reply -> reply.paymentId();
      case RefundReservationReleasedPayload reply -> reply.paymentId();
      default -> throw new IllegalArgumentException("Unsupported inbox payload");
    };
  }
}
