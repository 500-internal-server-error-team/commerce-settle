package com.commercesettle.settlement.application.port;

import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.event.PurchaseConfirmedPayload;
import com.commercesettle.settlement.application.event.RefundFinancialCompletedPayload;
import com.commercesettle.settlement.application.event.RefundReleaseRequestedPayload;
import com.commercesettle.settlement.application.event.RefundReservationRequestedPayload;
import com.commercesettle.settlement.domain.model.InboxReviewReason;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 수신 원본 저장소. 모든 메서드는 호출자의 트랜잭션에 참여한다. */
public interface InboxStore {
  /**
   * 원본을 RECEIVED로 저장하고 새 Inbox ID를 돌려준다. 같은 eventId의 같은 bytes(재전달)면 아무것도 하지 않고, 다른 bytes면 충돌 기록만
   * 남긴다. 두 경우 모두 빈 값이며 원래 Inbox와 업무 결과를 바꾸지 않는다.
   */
  Optional<UUID> receive(EventEnvelope<?> event, Source source);

  /**
   * 처리 시각이 된 WAITING 하나를 잠그고 원문에서 다시 읽는다. 다른 처리기가 잠근 행은 건너뛴다. WAITING은 선행 결제를 기다리는 구매확정과 환불 예약 요청만
   * 생긴다.
   */
  Optional<Pending> claimNextWaiting();

  /** 대상 행을 잠그고 원문에서 판정 입력을 읽는다. HTTP 응답에는 사용하지 않는다. */
  Optional<Review> lockReview(UUID inboxId);

  void reopenReview(UUID inboxId, Instant now);

  void markApplied(UUID inboxId, UUID resultRef, Instant now);

  void markReviewRequired(UUID inboxId, InboxReviewReason reason, Instant now);

  /**
   * 선행 결제가 아직 없어 시도 횟수를 늘리고 {@code baseDelay}부터 두 배씩, 최대 60초 뒤 다시 판정한다. 이번이 {@code maxAttempts}번째면
   * REVIEW_REQUIRED로 남기고 false를 돌려준다.
   */
  boolean markWaiting(UUID inboxId, Duration baseDelay, int maxAttempts, Instant now);

  /**
   * 결제가 오지 않아 {@code maxAttempts}번 판정 뒤 REVIEW_REQUIRED로 남은 같은 결제의 구매확정을 WAITING·시도 0으로 되돌리고 되돌린
   * Inbox ID를 돌려준다. 아직 대기 중인 같은 결제의 구매확정도 시도 0으로 바로 다시 판정하게 한다. 처리기가 마지막 판정 중이면 그 커밋을 기다렸다가 되돌린다.
   * 다른 사유로 운영 확인에 남은 행은 건드리지 않는다.
   */
  List<UUID> reviveExhaustedPurchases(UUID paymentId, int maxAttempts, Instant now);

  /**
   * {@code inboxId}보다 먼저 받은 같은 환불의 예약 요청 중 가장 오래된 것. 결과(예약·거절)와 상관없이 같은 환불의 원요청을 찾는다. 이미 커밋된 행만
   * 보인다.
   */
  Optional<EarlierRefundRequest> findEarlierRefundRequest(UUID refundId, UUID inboxId);

  /** 수신 위치와 최초 bytes. */
  record Source(String topic, int partition, long offset, byte[] body) {}

  /** 같은 환불의 원요청과 그 판정. 대기 중이면 {@code resultRef}가 없다. */
  record EarlierRefundRequest(String status, UUID paymentId, long netAmount, UUID resultRef) {}

  /** 이벤트 종류별로 원문을 다시 읽은 대기 건. */
  record Review(Pending event, long version, String status, String reasonCode) {}

  sealed interface Pending {
    UUID inboxId();

    record Purchase(UUID inboxId, EventEnvelope<PurchaseConfirmedPayload> event)
        implements Pending {}

    record RefundReservation(UUID inboxId, EventEnvelope<RefundReservationRequestedPayload> event)
        implements Pending {}

    record RefundCompleted(UUID inboxId, EventEnvelope<RefundFinancialCompletedPayload> event)
        implements Pending {}

    record RefundRelease(UUID inboxId, EventEnvelope<RefundReleaseRequestedPayload> event)
        implements Pending {}
  }
}
