package com.commercesettle.payment.application.port;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface InboxStore {
  /** 수신 원본과 WAITING 작업을 저장한다. 같은 ID의 다른 본문은 별도로 격리한다. */
  void saveWaiting(EventEnvelope<OrderPaymentRequestedPayload> event, Source source);

  /** 처리 시각이 된 WAITING 하나를 현재 트랜잭션에서 잠그고 가져온다. 다른 처리기가 잠근 행은 건너뛴다. */
  Optional<WaitingInboxEvent> claimNextWaiting(Instant now);

  void markApplied(UUID inboxId, UUID resultRef, Instant now);

  void markReviewRequired(UUID inboxId, Instant now);
}
