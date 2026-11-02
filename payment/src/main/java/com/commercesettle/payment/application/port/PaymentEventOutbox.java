package com.commercesettle.payment.application.port;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.PaymentAcceptedPayload;
import com.commercesettle.payment.application.event.PaymentCancellationResultPayload;
import java.util.UUID;

public interface PaymentEventOutbox {
  /** 결제 저장과 같은 트랜잭션에서 호출한다. 반환값은 저장한 eventId다. */
  UUID appendPaymentAccepted(EventEnvelope<PaymentAcceptedPayload> event);

  /** PaymentCancelled 또는 PaymentCancellationRejected를 결제 변경과 같은 트랜잭션에 저장한다. */
  UUID appendCancellationResult(EventEnvelope<PaymentCancellationResultPayload> event);
}
