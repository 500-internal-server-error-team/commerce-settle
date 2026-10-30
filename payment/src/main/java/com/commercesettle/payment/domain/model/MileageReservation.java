package com.commercesettle.payment.domain.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 결제 하나에 묶인 마일리지 예약. 결제 회차의 사용 마일리지만큼 지갑에서 잡아 둔다. */
@Entity
@Table(name = "mileage_reservations")
public class MileageReservation extends AuditableEntity {
  @Id private UUID id;
  private UUID walletId;
  private UUID paymentId;
  private UUID cycleId;
  private long amount;

  @Enumerated(EnumType.STRING)
  private MileageReservationStatus status;

  @Version private Long version;

  protected MileageReservation() {}

  private MileageReservation(MileageWallet wallet, Payment payment, Instant now) {
    super(now);
    this.id = UUID.randomUUID();
    this.walletId = wallet.getId();
    this.paymentId = payment.getId();
    this.cycleId = payment.getCurrentCycleId();
    this.amount = payment.getAmounts().mileageAmount();
    this.status = MileageReservationStatus.RESERVED;
  }

  /** 결제의 사용 마일리지만큼 예약을 만든다. 사용 마일리지가 없으면 만들지 않는다. */
  public static MileageReservation forPayment(MileageWallet wallet, Payment payment, Instant now) {
    Objects.requireNonNull(wallet, "wallet");
    Objects.requireNonNull(payment, "payment");
    if (payment.getAmounts().mileageAmount() == 0) {
      throw new IllegalArgumentException("마일리지를 쓰지 않는 결제는 예약을 만들지 않습니다.");
    }
    return new MileageReservation(wallet, payment, now);
  }

  /** 예약한 마일리지를 실제로 차감했음을 기록한다. RESERVED에서만 가능하다. */
  public void consume(Instant now) {
    if (status != MileageReservationStatus.RESERVED) {
      throw new IllegalStateException("예약 상태가 아니어서 차감할 수 없습니다: " + status);
    }
    status = MileageReservationStatus.CONSUMED;
    touch(now);
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

  public UUID getCycleId() {
    return cycleId;
  }

  public long getAmount() {
    return amount;
  }

  public MileageReservationStatus getStatus() {
    return status;
  }

  public Long getVersion() {
    return version;
  }
}
