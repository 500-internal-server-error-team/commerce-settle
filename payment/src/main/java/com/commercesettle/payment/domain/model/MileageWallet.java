package com.commercesettle.payment.domain.model;

import com.commercesettle.payment.domain.exception.InsufficientMileageException;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 구매자별 우리 마일리지 잔액과 진행 중 예약액. 가용 잔액(잔액 − 예약액)은 음수가 될 수 없다. */
@Entity
@Table(name = "mileage_wallets")
public class MileageWallet extends AuditableEntity {
  @Id private UUID id;
  private UUID buyerId;
  private long balance;
  private long reservedAmount;
  @Version private Long version;

  protected MileageWallet() {}

  private MileageWallet(UUID buyerId, long balance, Instant now) {
    super(now);
    if (balance < 0) {
      throw new IllegalArgumentException("마일리지 잔액은 0 이상이어야 합니다.");
    }
    this.id = UUID.randomUUID();
    this.buyerId = Objects.requireNonNull(buyerId, "buyerId");
    this.balance = balance;
    this.reservedAmount = 0;
  }

  /** 시험용 사전 지급 잔액으로 지갑을 연다. */
  public static MileageWallet open(UUID buyerId, long balance, Instant now) {
    return new MileageWallet(buyerId, balance, now);
  }

  /** 잔액 − 진행 중 예약액. */
  public long available() {
    return balance - reservedAmount;
  }

  /** 가용 잔액 안에서 예약한다. 잔액은 차감 전까지 그대로 두고 예약액만 늘린다. */
  public void reserve(long amount, Instant now) {
    requirePositive(amount);
    if (available() < amount) {
      throw new InsufficientMileageException(available(), amount);
    }
    reservedAmount += amount;
    touch(now);
  }

  /** 예약해 둔 금액을 실제로 차감한다. 잔액과 예약액이 함께 줄어든다. */
  public void consumeReserved(long amount, Instant now) {
    requirePositive(amount);
    if (amount > reservedAmount) {
      throw new IllegalStateException(
          "예약액 " + reservedAmount + "점보다 많은 " + amount + "점을 차감할 수 없습니다.");
    }
    balance -= amount;
    reservedAmount -= amount;
    touch(now);
  }

  private static void requirePositive(long amount) {
    if (amount <= 0) {
      throw new IllegalArgumentException("마일리지 금액은 0보다 커야 합니다.");
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getBuyerId() {
    return buyerId;
  }

  public long getBalance() {
    return balance;
  }

  public long getReservedAmount() {
    return reservedAmount;
  }

  public Long getVersion() {
    return version;
  }
}
