package com.commercesettle.financemock.domain.model;

import com.commercesettle.financemock.domain.exception.InvalidMockTransitionException;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
  private UUID accountId;

  @Enumerated(EnumType.STRING)
  private MockPaymentMethod method;

  private UUID fundingAccountId;
  private String pgCalendarVersion;

  @JdbcTypeCode(SqlTypes.JSON)
  private Calendar pgCalendar;

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

  public static MockTransaction approve(
      PaymentProvider provider,
      UUID businessKey,
      MockPaymentMethod method,
      UUID accountId,
      UUID fundingAccountId,
      long amount,
      String scenarioVersion,
      long resultSeed,
      Instant now) {
    provider.requireMethod(method);
    if ((method == MockPaymentMethod.EASY_PAY) != (accountId != null)) {
      throw new IllegalArgumentException("wallet approval requires its original account");
    }
    MockTransaction transaction =
        new MockTransaction(
            UUID.randomUUID(),
            provider.name(),
            MockOperation.APPROVE,
            businessKey,
            fundingAccountId,
            amount,
            scenarioVersion,
            resultSeed,
            now);
    transaction.method = method;
    transaction.accountId = accountId;
    return transaction;
  }

  public void scheduleReceipt(int businessDays, String calendarVersion, List<LocalDate> holidays) {
    if (operation != MockOperation.APPROVE
        || status != MockTransactionStatus.SUCCEEDED
        || pgReceiptDueAt != null
        || businessDays < 0
        || businessDays > 365) {
      throw new InvalidMockTransitionException(
          "receipt can only be scheduled once for an approval");
    }
    pgCalendarVersion = Objects.requireNonNull(calendarVersion);
    pgCalendar = new Calendar("Asia/Seoul", holidays);
    var due = succeededAt.atZone(ZoneId.of(pgCalendar.timeZone()));
    int remaining = businessDays;
    while (remaining > 0) {
      due = due.plusDays(1);
      if (due.getDayOfWeek() != DayOfWeek.SATURDAY
          && due.getDayOfWeek() != DayOfWeek.SUNDAY
          && !pgCalendar.holidays().contains(due.toLocalDate())) remaining--;
    }
    pgReceiptDueAt = due.toInstant();
  }

  public MockTransaction refund(
      PaymentProvider provider, UUID businessKey, long refundAmount, long resultSeed, Instant now) {
    requireRefundable(provider, refundAmount);
    MockTransaction refund =
        new MockTransaction(
            UUID.randomUUID(),
            providerId,
            MockOperation.REFUND,
            businessKey,
            fundingAccountId,
            amount,
            scenarioVersion,
            resultSeed,
            now);
    refund.originalId = id;
    refund.accountId = accountId;
    refund.method = method;
    return refund;
  }

  public void requireRefundable(PaymentProvider provider, long refundAmount) {
    if (operation != MockOperation.APPROVE
        || status != MockTransactionStatus.SUCCEEDED
        || !provider.name().equals(providerId)
        || refundAmount != amount
        || pgReceiptDueAt == null) {
      throw new InvalidMockTransitionException(
          "refund requires the original successful full approval");
    }
  }

  public boolean receiptDue(Instant now) {
    return operation == MockOperation.APPROVE
        && status == MockTransactionStatus.SUCCEEDED
        && pgReceiptDueAt != null
        && !pgReceiptDueAt.isAfter(now)
        && pgReceivedAt == null;
  }

  public void receive(long receivedAmount, Instant now) {
    if (operation != MockOperation.APPROVE
        || status != MockTransactionStatus.SUCCEEDED
        || pgReceivedAt != null
        || (receivedAmount != 0 && receivedAmount != amount)) {
      throw new InvalidMockTransitionException("receipt is already settled or invalid");
    }
    pgReceivedAt = truncate(now);
    pgReceivedAmount = receivedAmount;
    touch(now);
  }

  public UUID getAccountId() {
    return accountId;
  }

  public MockPaymentMethod getMethod() {
    return method;
  }

  public String getPgCalendarVersion() {
    return pgCalendarVersion;
  }

  public Calendar getPgCalendar() {
    return pgCalendar;
  }

  public record Calendar(String timeZone, List<LocalDate> holidays) {
    public Calendar {
      holidays = List.copyOf(holidays);
    }
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
