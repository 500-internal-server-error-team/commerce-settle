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

/** 거래 하나의 자금 선점. RESERVED에서 한 번만 소비(CONSUMED)하거나 해제(RELEASED)한다. */
@Entity
@Table(name = "mock_funds_reservations")
public class FundsReservation extends AuditableEntity {
  @Id private UUID id;
  private UUID accountId;
  private UUID transactionId;
  private long amount;

  @Enumerated(EnumType.STRING)
  private FundsReservationStatus status;

  @Version private Long version;

  protected FundsReservation() {}

  private FundsReservation(UUID id, UUID accountId, UUID transactionId, long amount, Instant now) {
    super(now);
    if (amount <= 0) {
      throw new IllegalArgumentException("amount must be positive");
    }
    this.id = id;
    this.accountId = Objects.requireNonNull(accountId, "accountId");
    this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
    this.amount = amount;
    this.status = FundsReservationStatus.RESERVED;
  }

  public static FundsReservation reserve(
      UUID accountId, UUID transactionId, long amount, Instant now) {
    return new FundsReservation(UUID.randomUUID(), accountId, transactionId, amount, now);
  }

  public void consume(Instant now) {
    finish(FundsReservationStatus.CONSUMED, now);
  }

  public void release(Instant now) {
    finish(FundsReservationStatus.RELEASED, now);
  }

  private void finish(FundsReservationStatus next, Instant now) {
    if (status != FundsReservationStatus.RESERVED) {
      throw new InvalidMockTransitionException("reservation is already " + status);
    }
    this.status = next;
    touch(now);
  }

  public UUID getAccountId() {
    return accountId;
  }

  public UUID getTransactionId() {
    return transactionId;
  }

  public long getAmount() {
    return amount;
  }

  public FundsReservationStatus getStatus() {
    return status;
  }
}
