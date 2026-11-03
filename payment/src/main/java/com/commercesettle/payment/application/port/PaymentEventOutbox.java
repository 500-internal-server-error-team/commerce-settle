package com.commercesettle.payment.application.port;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.PaymentAcceptedPayload;
import com.commercesettle.payment.application.event.PaymentCancellationResultPayload;
import com.commercesettle.payment.application.event.PaymentConfirmedPayload;
import com.commercesettle.payment.application.event.PaymentDeclinedPayload;
import com.commercesettle.payment.application.event.PaymentNoticeRequestedPayload;
import com.commercesettle.payment.application.event.RefundEventPayload;
import java.util.UUID;

public interface PaymentEventOutbox {
  /** 결제 확정·마일리지 복원과 같은 트랜잭션에 처리 직후 알림 사본을 저장한다. */
  UUID appendNoticeRequested(EventEnvelope<PaymentNoticeRequestedPayload> event);

  /** 결제 저장과 같은 트랜잭션에서 호출한다. 반환값은 저장한 eventId다. */
  UUID appendPaymentAccepted(EventEnvelope<PaymentAcceptedPayload> event);

  /** PaymentCancelled 또는 PaymentCancellationRejected를 결제 변경과 같은 트랜잭션에 저장한다. */
  UUID appendCancellationResult(EventEnvelope<PaymentCancellationResultPayload> event);

  /** 결제 확정과 같은 트랜잭션에서 PaymentConfirmed를 저장한다. */
  UUID appendPaymentConfirmed(EventEnvelope<PaymentConfirmedPayload> event);

  /** 외부 승인 확정 거절과 같은 트랜잭션에서 PaymentDeclined를 저장한다. */
  UUID appendPaymentDeclined(EventEnvelope<PaymentDeclinedPayload> event);

  /** 환불 상태 변경과 같은 트랜잭션에서 환불 이벤트를 저장한다. 집계와 Kafka key는 원결제 ID이며, 이벤트 종류와 본문 타입이 맞아야 한다. */
  UUID appendRefundEvent(EventEnvelope<? extends RefundEventPayload> event);
}
