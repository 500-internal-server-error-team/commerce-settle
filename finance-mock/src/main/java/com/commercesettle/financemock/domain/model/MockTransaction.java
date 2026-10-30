package com.commercesettle.financemock.domain.model;

import com.commercesettle.financemock.domain.exception.InvalidMockTransitionException;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 모의 금융 거래. PROCESSING에서 한 번만 SUCCEEDED 또는 DECLINED로 확정하며 최초 성공 시각은 바뀌지 않는다. 원거래·입금 컬럼은 PG 대역이 쓴다.
 */
@Entity
@Table(name = "mock_transactions")
public class MockTransaction extends AuditableEntity {
  @Id private UUID id;
  private String providerId;

  @Enumerated(EnumType.STRING)
  private MockOperation operation;

  private UUID businessKey;
  private UUID originalId;
  private UUID fundingAccountId;
  private Instant pgReceiptDueAt;
  private Instant pgReceivedAt;
  private Long pgReceivedAmount;
  private long amount;

  @Enumerated(EnumType.STRING)
  private MockTransactionStatus status;

  private Instant succeededAt;
  private String scenarioVersion;
  private long resultSeed;
  @Version private Long version;

  protected MockTransaction() {}

  private MockTransaction(
      UUID id,
      String providerId,
      MockOperation operation,
      UUID businessKey,
      UUID fundingAccountId,
      long amount,
      String scenarioVersion,
      long resultSeed,
      Instant now) {
    super(now);
    if (amount <= 0) {
      throw new IllegalArgumentException("amount must be positive");
    }
    this.id = id;
    this.providerId = Objects.requireNonNull(providerId, "providerId");
    this.operation = Objects.requireNonNull(operation, "operation");
    this.businessKey = Objects.requireNonNull(businessKey, "businessKey");
    this.fundingAccountId = Objects.requireNonNull(fundingAccountId, "fundingAccountId");
    this.amount = amount;
    this.status = MockTransactionStatus.PROCESSING;
    this.scenarioVersion = Objects.requireNonNull(scenarioVersion, "scenarioVersion");
    this.resultSeed = resultSeed;
  }

  /** 지급 거래를 접수한다. 결과는 접수 때 정한 결과 seed로 나중에 확정한다. */
  public static MockTransaction acceptPayout(
      UUID businessKey,
      UUID fundingAccountId,
      long amount,
      String scenarioVersion,
      long resultSeed,
      Instant now) {
    return new MockTransaction(
        UUID.randomUUID(),
        MockScenario.PAYOUT_PROVIDER,
        MockOperation.PAYOUT,
        businessKey,
        fundingAccountId,
        amount,
        scenarioVersion,
        resultSeed,
        now);
  }

  public void succeed(Instant now) {
    requireProcessing();
    this.status = MockTransactionStatus.SUCCEEDED;
    this.succeededAt = truncate(now);
    touch(now);
  }

  public void decline(Instant now) {
    requireProcessing();
    this.status = MockTransactionStatus.DECLINED;
    touch(now);
  }

  private void requireProcessing() {
    if (status != MockTransactionStatus.PROCESSING) {
      throw new InvalidMockTransitionException("transaction is already " + status);
    }
  }

  public UUID getId() {
    return id;
  }

  public String getProviderId() {
    return providerId;
  }

  public MockOperation getOperation() {
    return operation;
  }

  public UUID getBusinessKey() {
    return businessKey;
  }

  public UUID getOriginalId() {
    return originalId;
  }

  public UUID getFundingAccountId() {
    return fundingAccountId;
  }

  public Instant getPgReceiptDueAt() {
    return pgReceiptDueAt;
  }

  public Instant getPgReceivedAt() {
    return pgReceivedAt;
  }

  public Long getPgReceivedAmount() {
    return pgReceivedAmount;
  }

  public long getAmount() {
    return amount;
  }

  public MockTransactionStatus getStatus() {
    return status;
  }

  public Instant getSucceededAt() {
    return succeededAt;
  }

  public String getScenarioVersion() {
    return scenarioVersion;
  }

  public long getResultSeed() {
    return resultSeed;
  }
}
