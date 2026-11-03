package com.commercesettle.settlement.application.service;

import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.event.SettlementNoticeRequestedPayload;
import com.commercesettle.settlement.application.event.SettlementNoticeRequestedPayload.Kind;
import com.commercesettle.settlement.application.port.SettlementEventOutbox;
import com.commercesettle.settlement.domain.model.Payout;
import com.commercesettle.settlement.domain.model.SettlementBatch;
import com.commercesettle.settlement.domain.repository.SettlementBatchRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 알림 요청을 정산 배치·지급 변경과 같은 트랜잭션의 Outbox에 남긴다. key·집계는 배치이고 집계 버전은 반영한 배치 버전이라 같은 배치의 알림이 바뀐 순서대로
 * 나간다. 지급 미확정은 알림 없이 조회의 holdReason으로만 안내한다.
 */
@Service
public class SettlementNoticeService {
  static final String PROVIDER_DECLINED = "PROVIDER_DECLINED";

  private final SettlementBatchRepository batches;
  private final SettlementEventOutbox outbox;

  SettlementNoticeService(SettlementBatchRepository batches, SettlementEventOutbox outbox) {
    this.batches = batches;
    this.outbox = outbox;
  }

  /** 일배치가 판매자의 배치·지급을 확정했다. 지급 성공이 아니라 마감·합계 안내다. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void ready(SettlementBatch batch, Payout payout, Instant now) {
    publish(
        Kind.SETTLEMENT_READY, batch.getId(), batch, payout, batch.getNetAmount(), null, null, now);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void payoutCompleted(SettlementBatch batch, Payout payout, Instant now) {
    publish(
        Kind.PAYOUT_COMPLETED,
        payout.getId(),
        batch,
        payout,
        payout.getAmount(),
        time(payout.getSucceededAt()),
        null,
        now);
  }

  /** 지급 기관이 확정 거절했다. 판매자의 수취 계정 확인 같은 조치가 필요하다. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void actionRequired(SettlementBatch batch, Payout payout, Instant now) {
    publish(
        Kind.PAYOUT_ACTION_REQUIRED,
        batch.getId(),
        batch,
        payout,
        payout.getAmount(),
        null,
        PROVIDER_DECLINED,
        now);
  }

  private void publish(
      Kind kind,
      UUID businessRef,
      SettlementBatch batch,
      Payout payout,
      long amount,
      OffsetDateTime succeededAt,
      String reasonCode,
      Instant now) {
    // 배치 변경을 먼저 반영해 바뀐 버전을 집계 버전으로 쓴다.
    SettlementBatch saved = batches.save(batch);
    outbox.append(
        new EventEnvelope<>(
            UUID.randomUUID(),
            "SettlementNoticeRequested",
            1,
            time(now),
            saved.getId(),
            saved.getVersion(),
            new SettlementNoticeRequestedPayload(
                kind,
                businessRef,
                saved.getId(),
                payout.getId(),
                saved.getSellerId(),
                time(saved.getCutoffAt()),
                amount,
                succeededAt,
                reasonCode,
                saved.getBatchDate(),
                saved.getPolicyVersion())),
        saved.getId());
  }

  private static OffsetDateTime time(Instant instant) {
    return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
  }
}
