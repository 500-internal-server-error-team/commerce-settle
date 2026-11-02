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

  /**
   * 처리 시각이 된 WAITING 하나를 현재 트랜잭션에서 잠그고 가져온다. 다른 처리기가 잠근 행은 건너뛴다. 원문을 읽을 수 없으면 {@link
   * UnreadableInboxEventException}을 던진다.
   */
  Optional<WaitingInboxEvent> claimNextWaiting(Instant now);

  Optional<WaitingInboxEvent> claimWaitingEvent(UUID eventId, Instant now);

  void markApplied(UUID inboxId, UUID resultRef, Instant now);

  /** 업무상 거절한 WAITING 행을 확인 대상으로 남기고 거절 사유를 저장한다. */
  void markReviewRequired(UUID inboxId, String errorCode, Instant now);

  /**
   * 롤백된 처리의 실패를 별도 트랜잭션에서 기록한다. 시도 횟수를 하나 늘리고 사유를 저장하며, retryAt이 있으면 그 시각에 다시 처리할 WAITING으로, 없으면
   * REVIEW_REQUIRED로 남긴다. 선점 이후 버전이 바뀌었거나 다른 처리기가 잠근 행이면 아무것도 바꾸지 않고 false를 돌려준다.
   */
  boolean recordFailure(UUID inboxId, long version, String errorCode, Instant retryAt, Instant now);

  /** 결제를 처음 APPLIED로 만든 요청을 원문에서 다시 읽는다. 결제에 저장하지 않는 결제수단 비교에 쓴다. */
  Optional<OrderPaymentRequestedPayload> findOriginalRequest(UUID paymentId);
}
