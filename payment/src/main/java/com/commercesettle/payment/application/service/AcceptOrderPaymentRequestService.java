package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AcceptOrderPaymentRequestService implements OrderPaymentRequestAcceptor {
  private final InboxStore inbox;

  public AcceptOrderPaymentRequestService(InboxStore inbox) {
    this.inbox = inbox;
  }

  @Override
  @Transactional
  public void accept(EventEnvelope<OrderPaymentRequestedPayload> event, Source source) {
    // WAITING은 영속 메시지 인계이며 결제 접수·마일리지 예약 완료가 아니다.
    inbox.saveWaiting(event, source);
  }
}
