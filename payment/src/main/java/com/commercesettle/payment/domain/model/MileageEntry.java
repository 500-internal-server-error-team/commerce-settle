package com.commercesettle.payment.domain.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * 마일리지 원장. 지갑에 일어난 일을 추가만 하고 수정하지 않는다. 같은 지갑·종류·업무 키는 한 번만 기록된다. 예약은 결제 회차 ID, 차감은 결제 ID를 업무 키로 쓴다.
 */
@Entity
@Table(name = "mileage_entries")
public class MileageEntry {
  @Id private UUID id;
  private UUID walletId;
  private UUID paymentId;
  private UUID refundId;

  @Enumerated(EnumType.STRING)
  private MileageEntryKind kind;

  private UUID businessRef;
  private long amount;
  private long balanceAfter;
  private Instant createdAt;

  protected MileageEntry() {}

  private MileageEntry(
      MileageWallet wallet,
      MileageReservation reservation,
      MileageEntryKind kind,
      UUID businessRef,
      Instant now) {
    if (!wallet.getId().equals(reservation.getWalletId())) {
      throw new IllegalArgumentException("다른 지갑의 예약은 기록할 수 없습니다.");
    }
    this.id = UUID.randomUUID();
    this.walletId = wallet.getId();
    this.paymentId = reservation.getPaymentId();
    this.kind = kind;
    this.businessRef = businessRef;
    this.amount = reservation.getAmount();
    this.balanceAfter = wallet.getBalance();
    this.createdAt = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
  }

  /** 예약을 기록한다. 잔액은 바뀌지 않으므로 기록 시점의 잔액을 남긴다. 업무 키는 결제 회차 ID. */
  public static MileageEntry reserve(
      MileageWallet wallet, MileageReservation reservation, Instant now) {
    return new MileageEntry(
        wallet, reservation, MileageEntryKind.RESERVE, reservation.getCycleId(), now);
  }

  /** 차감을 기록한다. 지갑에서 차감한 뒤 불러 차감 후 잔액을 남긴다. 업무 키는 결제 ID. */
  public static MileageEntry debit(
      MileageWallet wallet, MileageReservation reservation, Instant now) {
    return new MileageEntry(
        wallet, reservation, MileageEntryKind.DEBIT, reservation.getPaymentId(), now);
  }

  public UUID getId() {
    return id;
  }

  public UUID getWalletId() {
    return walletId;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public UUID getRefundId() {
    return refundId;
  }

  public MileageEntryKind getKind() {
    return kind;
  }

  public UUID getBusinessRef() {
    return businessRef;
  }

  public long getAmount() {
    return amount;
  }

  public long getBalanceAfter() {
    return balanceAfter;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
