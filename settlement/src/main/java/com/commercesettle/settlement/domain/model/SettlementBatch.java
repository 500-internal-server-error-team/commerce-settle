package com.commercesettle.settlement.domain.model;

import com.commercesettle.settlement.domain.exception.InvalidPayoutTransitionException;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/**
 * 판매자·마감 날짜·정책 버전별 정산 배치. 마감은 batchDate의 10:00 Asia/Seoul이며, 대상을 선점하고 합계를 확정한 상태(RESERVED)로 만든다. 만든
 * 뒤에는 대상·합계를 바꾸지 않는다.
 */
@Entity
@Table(name = "settlement_batches")
public class SettlementBatch extends AuditableEntity {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");
  private static final LocalTime CUTOFF_TIME = LocalTime.of(10, 0);

  @Id private UUID id;
  private UUID sellerId;
  private LocalDate batchDate;
  private Instant cutoffAt;
  private String policyVersion;
  private UUID demoRunId;

  @Enumerated(EnumType.STRING)
  private SettlementBatchStatus status;

  private long netAmount;
  private Instant closedAt;
  @Version private Long version;

  protected SettlementBatch() {}

  private SettlementBatch(
      UUID id,
      UUID sellerId,
      LocalDate batchDate,
      String policyVersion,
      long netAmount,
      Instant now) {
    this(id, sellerId, batchDate, policyVersion, netAmount, null, cutoffOf(batchDate), now);
  }

  private SettlementBatch(
      UUID id,
      UUID sellerId,
      LocalDate batchDate,
      String policyVersion,
      long netAmount,
      UUID demoRunId,
      Instant cutoffAt,
      Instant now) {
    super(now);
    this.id = id;
    this.sellerId = Objects.requireNonNull(sellerId, "sellerId");
    this.batchDate = Objects.requireNonNull(batchDate, "batchDate");
    this.cutoffAt = cutoffAt;
    this.demoRunId = demoRunId;
    this.policyVersion = Objects.requireNonNull(policyVersion, "policyVersion");
    this.status = SettlementBatchStatus.RESERVED;
    this.netAmount = netAmount;
  }

  /** 마감이 지난 날짜의 대상 합계로 배치를 만든다. 대상이 없으면 배치를 만들지 않는다. */
  public static SettlementBatch reserve(
      UUID sellerId, LocalDate batchDate, String policyVersion, long netAmount, Instant now) {
    if (netAmount <= 0) {
      throw new IllegalArgumentException("batch needs at least one payable amount");
    }
    if (cutoffOf(batchDate).isAfter(now)) {
      throw new IllegalArgumentException("cutoff has not passed yet");
    }
    return new SettlementBatch(
        UUID.randomUUID(), sellerId, batchDate, policyVersion, netAmount, now);
  }

  /** batchDate의 마감 시각(10:00 Asia/Seoul). 이 시각에 준비된 항목은 다음 날 대상이다. */
  public static Instant cutoffOf(LocalDate batchDate) {
    return batchDate.atTime(CUTOFF_TIME).atZone(BUSINESS_ZONE).toInstant();
  }

  public static SettlementBatch reserveDemo(
      UUID runId, UUID sellerId, Instant cutoffAt, long netAmount, Instant now) {
    if (netAmount <= 0 || cutoffAt.isAfter(now))
      throw new IllegalArgumentException("Demo cutoff or amount is invalid");
    return new SettlementBatch(
        UUID.randomUUID(),
        sellerId,
        cutoffAt.atZone(BUSINESS_ZONE).toLocalDate(),
        "demo-periods-v1",
        netAmount,
        Objects.requireNonNull(runId),
        cutoffAt,
        now);
  }

  public UUID getDemoRunId() {
    return demoRunId;
  }

  /** 지급을 처음 실행할 때 처리 중으로 바꾼다. */
  public void startProcessing(Instant now) {
    transition(status == SettlementBatchStatus.RESERVED, SettlementBatchStatus.PROCESSING, now);
  }

  /** 지급 성공이 확인되면 완료한다. */
  public void complete(Instant now) {
    transition(status == SettlementBatchStatus.PROCESSING, SettlementBatchStatus.COMPLETED, now);
    closedAt = truncate(now);
  }

  /** 지급 확정 거절이나 재시도 소진은 운영 확인으로 넘긴다. 항목·예약은 그대로 둔다. */
  public void requireReview(Instant now) {
    transition(
        status == SettlementBatchStatus.RESERVED || status == SettlementBatchStatus.PROCESSING,
        SettlementBatchStatus.REVIEW_REQUIRED,
        now);
  }

  /** 운영 재처리로 지급 작업을 다시 열 때 되돌린다. 아직 호출하지 않은 지급이면 예약, 호출한 적이 있으면 처리 중이다. */
  public void resumeAfterReview(boolean payoutStarted, Instant now) {
    transition(
        status == SettlementBatchStatus.REVIEW_REQUIRED,
        payoutStarted ? SettlementBatchStatus.PROCESSING : SettlementBatchStatus.RESERVED,
        now);
  }

  private void transition(boolean allowed, SettlementBatchStatus next, Instant now) {
    if (!allowed) {
      throw new InvalidPayoutTransitionException("settlement batch", status, next);
    }
    this.status = next;
    touch(now);
  }

  public UUID getId() {
    return id;
  }

  public UUID getSellerId() {
    return sellerId;
  }

  public LocalDate getBatchDate() {
    return batchDate;
  }

  public Instant getCutoffAt() {
    return cutoffAt;
  }

  public String getPolicyVersion() {
    return policyVersion;
  }

  public SettlementBatchStatus getStatus() {
    return status;
  }

  public long getNetAmount() {
    return netAmount;
  }

  public Instant getClosedAt() {
    return closedAt;
  }

  public Long getVersion() {
    return version;
  }
}
