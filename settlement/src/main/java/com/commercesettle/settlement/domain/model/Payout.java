package com.commercesettle.settlement.domain.model;

import com.commercesettle.settlement.domain.exception.InvalidPayoutTransitionException;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 배치 하나의 지급. 수취·출금 계정 사본, 금융 시나리오 버전, 업무 키를 생성 때 고정하고 재시도에도 그대로 쓴다. 현재 판매자 프로필을 다시 적용하지 않는다. */
@Entity
@Table(name = "payouts")
public class Payout extends AuditableEntity {
  @Id private UUID id;
  private UUID batchId;
  private UUID sellerId;
  private String recipientAccountRef;
  private long recipientAccountVersion;
  private String fundingAccountRef;
  private String scenarioVersion;
  private Instant succeededAt;
  private long amount;
  private UUID businessKey;
  private UUID externalRef;

  @Enumerated(EnumType.STRING)
  private PayoutStatus status;

  @Enumerated(EnumType.STRING)
  private PayoutHoldReason holdReason;

  @Version private Long version;

  protected Payout() {}

  private Payout(
      UUID id,
      UUID batchId,
      UUID sellerId,
      PayoutRecipient recipient,
      String fundingAccountRef,
      String scenarioVersion,
      long amount,
      Instant now) {
    super(now);
    if (amount <= 0) {
      throw new IllegalArgumentException("payout amount must be positive");
    }
    Objects.requireNonNull(recipient, "recipient");
    this.id = Objects.requireNonNull(id, "id");
    this.batchId = Objects.requireNonNull(batchId, "batchId");
    this.sellerId = Objects.requireNonNull(sellerId, "sellerId");
    this.recipientAccountRef = recipient.accountRef();
    this.recipientAccountVersion = recipient.accountVersion();
    this.fundingAccountRef = Objects.requireNonNull(fundingAccountRef, "fundingAccountRef");
    this.scenarioVersion = Objects.requireNonNull(scenarioVersion, "scenarioVersion");
    this.amount = amount;
    this.businessKey = UUID.randomUUID();
    this.status = PayoutStatus.PENDING;
  }

  /**
   * 지급을 만든다. 항목을 먼저 이 ID로 선점하므로 ID는 호출자가 정한다.
   *
   * @param fundingAccountRef 출금할 PAYOUT 재원 계정
   * @param scenarioVersion 지급 대역 호출에 고정할 금융 실행 시나리오 버전
   */
  public static Payout create(
      UUID id,
      UUID batchId,
      UUID sellerId,
      PayoutRecipient recipient,
      String fundingAccountRef,
      String scenarioVersion,
      long amount,
      Instant now) {
    return new Payout(
        id, batchId, sellerId, recipient, fundingAccountRef, scenarioVersion, amount, now);
  }

  /** 첫 대역 호출 전에 처리 중으로 바꾼다. 이후로는 결과를 모르면 실행이 아니라 원거래 조회부터 한다. */
  public void startProcessing(Instant now) {
    require(status == PayoutStatus.PENDING, PayoutStatus.PROCESSING);
    this.status = PayoutStatus.PROCESSING;
    touch(now);
  }

  /** 결과를 확인하지 못했다. 예약과 업무 키를 유지하고 같은 원거래를 다시 조회한다. */
  public void markUnknown(Instant now) {
    require(inFlight(), PayoutStatus.UNKNOWN);
    this.status = PayoutStatus.UNKNOWN;
    this.holdReason = PayoutHoldReason.UNKNOWN;
    touch(now);
  }

  /** 대역이 확인한 성공을 반영한다. 성공 시각은 대역의 최초 성공 시각을 그대로 복사한다. */
  public void succeed(Instant succeededAt, UUID externalRef, Instant now) {
    require(inFlight(), PayoutStatus.SUCCEEDED);
    this.status = PayoutStatus.SUCCEEDED;
    this.succeededAt = truncate(Objects.requireNonNull(succeededAt, "succeededAt"));
    this.externalRef = externalRef;
    this.holdReason = null;
    touch(now);
  }

  /** 대역의 확정 거절을 반영한다. 새 업무 키로 다시 지급하지 않고 운영 확인으로 넘긴다. */
  public void decline(UUID externalRef, Instant now) {
    require(inFlight(), PayoutStatus.DECLINED);
    this.status = PayoutStatus.DECLINED;
    this.externalRef = externalRef;
    this.holdReason = PayoutHoldReason.CONFIRMED_REJECTION;
    touch(now);
  }

  private boolean inFlight() {
    return status == PayoutStatus.PROCESSING || status == PayoutStatus.UNKNOWN;
  }

  private void require(boolean allowed, PayoutStatus next) {
    if (!allowed) {
      throw new InvalidPayoutTransitionException("payout", status, next);
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getBatchId() {
    return batchId;
  }

  public UUID getSellerId() {
    return sellerId;
  }

  public String getRecipientAccountRef() {
    return recipientAccountRef;
  }

  public long getRecipientAccountVersion() {
    return recipientAccountVersion;
  }

  public String getFundingAccountRef() {
    return fundingAccountRef;
  }

  public String getScenarioVersion() {
    return scenarioVersion;
  }

  public Instant getSucceededAt() {
    return succeededAt;
  }

  public long getAmount() {
    return amount;
  }

  public UUID getBusinessKey() {
    return businessKey;
  }

  public UUID getExternalRef() {
    return externalRef;
  }

  public PayoutStatus getStatus() {
    return status;
  }

  public PayoutHoldReason getHoldReason() {
    return holdReason;
  }

  public Long getVersion() {
    return version;
  }
}
