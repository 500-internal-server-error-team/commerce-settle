package com.commercesettle.payment.application.port;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.PaymentAcceptedPayload;
import java.util.UUID;

public interface PaymentEventOutbox {
  /** 결제 저장과 같은 트랜잭션에서 호출한다. 반환값은 저장한 eventId다. */
  UUID appendPaymentAccepted(EventEnvelope<PaymentAcceptedPayload> event);
}
