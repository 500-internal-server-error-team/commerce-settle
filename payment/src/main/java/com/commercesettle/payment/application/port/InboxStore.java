package com.commercesettle.payment.application.port;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;

public interface InboxStore {
  /** 수신 원본과 WAITING 작업을 저장한다. 같은 ID의 다른 본문은 별도로 격리한다. */
  void saveWaiting(EventEnvelope<OrderPaymentRequestedPayload> event, Source source);
}
