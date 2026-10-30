package com.commercesettle.financemock.domain.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** PAYOUT 계정의 추가 전용 현금 기록. 지급 차감은 음수로 남기고 수정·삭제하지 않는다. */
@Entity
@Table(name = "mock_cash_entries")
public class CashEntry {
  @Id private UUID id;
  private UUID accountId;

  @Enumerated(EnumType.STRING)
  private CashEntryKind kind;

  private UUID transactionId;
  private long amount;
  private Instant createdAt;

  protected CashEntry() {}

  private CashEntry(
      UUID id, UUID accountId, CashEntryKind kind, UUID transactionId, long amount, Instant now) {
    this.id = id;
    this.accountId = Objects.requireNonNull(accountId, "accountId");
    this.kind = kind;
    this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
    this.amount = amount;
    this.createdAt = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
  }

  public static CashEntry payoutDebit(
      UUID accountId, UUID transactionId, long payoutAmount, Instant now) {
    if (payoutAmount <= 0) {
      throw new IllegalArgumentException("payoutAmount must be positive");
    }
    return new CashEntry(
        UUID.randomUUID(),
        accountId,
        CashEntryKind.PAYOUT_DEBIT,
        transactionId,
        -payoutAmount,
        now);
  }

  public UUID getAccountId() {
    return accountId;
  }

  public CashEntryKind getKind() {
    return kind;
  }

  public UUID getTransactionId() {
    return transactionId;
  }

  public long getAmount() {
    return amount;
  }
}
