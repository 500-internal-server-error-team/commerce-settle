package com.commercesettle.settlement.application.port;

import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.event.PurchaseConfirmedPayload;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 수신 원본 저장소. 모든 메서드는 호출자의 트랜잭션에 참여한다. */
public interface InboxStore {
  /**
   * 원본을 RECEIVED로 저장하고 새 Inbox ID를 돌려준다. 같은 eventId의 같은 bytes(재전달)면 아무것도 하지 않고, 다른 bytes면 충돌 기록만
   * 남긴다. 두 경우 모두 빈 값이며 원래 Inbox와 업무 결과를 바꾸지 않는다.
   */
  Optional<UUID> receive(EventEnvelope<?> event, Source source);

  /** 처리 시각이 된 WAITING 하나를 잠그고 원문에서 다시 읽는다. 다른 처리기가 잠근 행은 건너뛴다. WAITING은 구매확정만 생긴다. */
  Optional<WaitingPurchase> claimNextWaiting();

  void markApplied(UUID inboxId, UUID resultRef, Instant now);

  void markReviewRequired(UUID inboxId, Instant now);

  /** 선행 결제가 아직 없어 {@code delay} 뒤 다시 판정한다. */
  void markWaiting(UUID inboxId, Duration delay, Instant now);

  /** 수신 위치와 최초 bytes. */
  record Source(String topic, int partition, long offset, byte[] body) {}

  record WaitingPurchase(UUID inboxId, EventEnvelope<PurchaseConfirmedPayload> event) {}
}
