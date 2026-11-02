package com.commercesettle.payment.application.port;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface InboxStore {
  /**
   * 수신 원본과 WAITING 작업을 저장한다. 같은 ID의 다른 본문은 별도로 격리한다. 결제 요청(OrderPaymentRequested)과 미결제 취소
   * 요청(PaymentCancellationRequested)을 받는다.
   */
  void saveWaiting(EventEnvelope<?> event, Source source);

  /**
   * 처리 시각이 된 WAITING 하나를 선점한다. 시도 횟수를 늘리고 다음 처리 시각을 leaseUntil로 미루므로, 호출 트랜잭션이 커밋된 뒤에는 처리 결과나 실패를
   * 기록하기 전까지 다른 처리기가 다시 선점하지 않는다. 한 번에 한 행만 다룬다. 이미 maxAttempts번 시도한 행과 원문을 읽을 수 없는 행은 사유와 함께
   * REVIEW_REQUIRED로 남기고 {@link InboxClaim.Reviewed}를 돌려준다. 다른 처리기가 잠근 행은 건너뛴다.
   */
  Optional<InboxClaim> claimNextWaiting(Instant now, Instant leaseUntil, int maxAttempts);

  /** {@link #claimNextWaiting}과 같지만 지정한 이벤트만 선점한다. */
  Optional<InboxClaim> claimWaitingEvent(
      UUID eventId, Instant now, Instant leaseUntil, int maxAttempts);

  /** 선점한 버전 그대로인 WAITING 행을 현재 트랜잭션에서 잠근다. 그 사이 다른 처리기가 선점했거나 처리했으면 false를 돌려준다. */
  boolean lockClaimed(UUID inboxId, long version);

  void markApplied(UUID inboxId, UUID resultRef, Instant now);

  /** 업무상 거절한 WAITING 행을 확인 대상으로 남기고 거절 사유를 저장한다. */
  void markReviewRequired(UUID inboxId, String errorCode, Instant now);

  /**
   * 롤백된 처리의 실패를 별도 트랜잭션에서 기록한다. 시도 횟수는 선점 때 이미 늘렸으므로 사유만 저장하고, retryAt이 있으면 그 시각에 다시 처리할 WAITING으로,
   * 없으면 REVIEW_REQUIRED로 남긴다. 선점 이후 버전이 바뀐 행이면 아무것도 바꾸지 않고 false를 돌려준다.
   */
  boolean recordFailure(UUID inboxId, long version, String errorCode, Instant retryAt, Instant now);

  /** 결제를 처음 APPLIED로 만든 요청을 원문에서 다시 읽는다. 결제에 저장하지 않는 결제수단 비교에 쓴다. */
  Optional<OrderPaymentRequestedPayload> findOriginalRequest(UUID paymentId);

  /**
   * 결제가 생기기 전에 처리한 같은 주문·회차의 취소 요청을 찾는다. 결제 없이 APPLIED로 남긴 취소이며, 늦게 온 결제 요청이 결제를 살리지 않게 하는 데 쓴다.
   */
  Optional<UUID> findEarlyCancellation(UUID orderId, UUID paymentCycleId);

  /** 같은 주문·회차로 받은 결제 요청 원문을 상태와 관계없이 찾는다. 결제가 없을 때 취소를 확정하는 데 쓴다. */
  Optional<OrderPaymentRequestedPayload> findPaymentRequest(UUID orderId, UUID paymentCycleId);

  /** 결제 없이 APPLIED로 남긴 취소 요청을 나중에 만든 결제에 연결한다. */
  void linkResult(UUID inboxId, UUID resultRef, Instant now);
}
