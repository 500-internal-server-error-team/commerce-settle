package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.event.PaymentCancellationRequestedPayload;
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

  @Override
  @Transactional
  public void acceptCancellation(
      EventEnvelope<PaymentCancellationRequestedPayload> event, Source source) {
    inbox.saveWaiting(event, source);
  }

  @Override
  @Transactional
  public void acceptRefundEvent(EventEnvelope<?> event, Source source) {
    // WAITING은 영속 메시지 인계이며 금융 환불 접수나 완료, 정산 응답 반영이 아니다.
    inbox.saveWaiting(event, source);
  }
}
