package com.commercesettle.payment.application.port;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;

public interface OrderPaymentRequestAcceptor {

  /** 원본과 처리 대기 작업의 저장을 커밋한 뒤 반환한다. 금융 접수 완료와 구분한다. */
  void accept(EventEnvelope<OrderPaymentRequestedPayload> event, Source source);

  record Source(String topic, int partition, long offset, byte[] body) {
    public Source {
      body = body.clone();
    }

    @Override
    public byte[] body() {
      return body.clone();
    }
  }
}
