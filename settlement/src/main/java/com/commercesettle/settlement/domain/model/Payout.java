package com.commercesettle.settlement.domain.model;

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
