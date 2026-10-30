package com.commercesettle.financemock.domain.model;

import com.commercesettle.financemock.domain.exception.InsufficientMockFundsException;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 대역의 시험 계정. 가용액(잔액 − 예약액) 안에서만 선점하고 소비할 때만 잔액을 줄여 {@code 0 ≤ 예약액 ≤ 잔액}을 유지한다. */
@Entity
@Table(name = "mock_accounts")
public class MockAccount extends AuditableEntity {
  @Id private UUID id;
  private String providerId;
  private String accountRef;

  @Enumerated(EnumType.STRING)
  private AccountPurpose purpose;

  private long openingBalance;
  private long balance;
  private long reservedAmount;
  @Version private Long version;

  protected MockAccount() {}

  private MockAccount(
      UUID id,
      String providerId,
      String accountRef,
      AccountPurpose purpose,
      long openingBalance,
      Instant now) {
    super(now);
    if (openingBalance < 0) {
      throw new IllegalArgumentException("openingBalance must not be negative");
    }
    this.id = id;
    this.providerId = Objects.requireNonNull(providerId, "providerId");
    this.accountRef = Objects.requireNonNull(accountRef, "accountRef");
    this.purpose = Objects.requireNonNull(purpose, "purpose");
    this.openingBalance = openingBalance;
    this.balance = openingBalance;
  }

  public static MockAccount open(
      String providerId,
      String accountRef,
      AccountPurpose purpose,
      long openingBalance,
      Instant now) {
    return new MockAccount(UUID.randomUUID(), providerId, accountRef, purpose, openingBalance, now);
  }

  public void reserve(long amount, Instant now) {
    requirePositive(amount);
    if (balance - reservedAmount < amount) {
      throw new InsufficientMockFundsException("available balance is less than amount");
    }
    reservedAmount += amount;
    touch(now);
  }

  /** 선점한 금액을 잔액에서 차감한다. */
  public void consume(long amount, Instant now) {
    requireReserved(amount);
    reservedAmount -= amount;
    balance -= amount;
    touch(now);
  }

  public void release(long amount, Instant now) {
    requireReserved(amount);
    reservedAmount -= amount;
    touch(now);
  }

  private void requireReserved(long amount) {
    requirePositive(amount);
    if (reservedAmount < amount) {
      throw new IllegalStateException("reserved amount is less than amount");
    }
  }

  private static void requirePositive(long amount) {
    if (amount <= 0) {
      throw new IllegalArgumentException("amount must be positive");
    }
  }

  public UUID getId() {
    return id;
  }

  public String getProviderId() {
    return providerId;
  }

  public String getAccountRef() {
    return accountRef;
  }

  public AccountPurpose getPurpose() {
    return purpose;
  }

  public long getOpeningBalance() {
    return openingBalance;
  }

  public long getBalance() {
    return balance;
  }

  public long getReservedAmount() {
    return reservedAmount;
  }
}
