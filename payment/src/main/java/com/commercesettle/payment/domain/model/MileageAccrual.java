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

/**
 * 원결제 한 건의 구매 적립. Commerce가 주문 생성 때 고정한 정책 사본(기준액·적립률·주문당 한도·버전)과 예정액을 보존한다. 예정(PENDING)은 잔액에 들어가지
 * 않으며 구매확정 뒤 사용 가능(AVAILABLE)으로 바뀌면서 지갑에 더해진다. 확정 전 환불이 완료되면 취소(CANCELLED)한다.
 */
@Entity
@Table(name = "mileage_accruals")
public class MileageAccrual extends AuditableEntity {
  /** 적립률 분모. 적립률은 basis point(1/10,000) 정수다. */
  public static final long BASIS_POINTS = 10_000;

  @Id private UUID id;
  private UUID paymentId;
  private UUID orderId;
  private UUID buyerId;
  private long baseAmount;
  private int rateBasisPoints;
  private long perOrderCap;
  private long accrualAmount;
  private String policyVersion;

  @Enumerated(EnumType.STRING)
  private MileageAccrualStatus status;

  private Instant paymentConfirmedAt;
  private Instant purchaseConfirmedAt;
  private Instant availableAt;
  private Instant cancelledAt;
  @Version private Long version;

  protected MileageAccrual() {}

  private MileageAccrual(Payment payment, Policy policy, Instant paymentConfirmedAt, Instant now) {
    super(now);
    this.id = UUID.randomUUID();
    this.paymentId = payment.getId();
    this.orderId = payment.getOrderId();
    this.buyerId = payment.getBuyerId();
    this.baseAmount = policy.baseAmount();
    this.rateBasisPoints = policy.rateBasisPoints();
    this.perOrderCap = policy.perOrderCap();
    this.accrualAmount = policy.accrualAmount();
    this.policyVersion = policy.policyVersion();
    this.paymentConfirmedAt = truncate(paymentConfirmedAt);
    this.status = MileageAccrualStatus.PENDING;
  }

  /** 결제 완료 반영으로 받은 적립 예정을 기록한다. 잔액은 바꾸지 않는다. */
  public static MileageAccrual schedule(
      Payment payment, Policy policy, Instant paymentConfirmedAt, Instant now) {
    return new MileageAccrual(payment, policy, paymentConfirmedAt, now);
  }

  /** 구매확정으로 사용 가능하게 한다. 지갑 증액·원장은 같은 트랜잭션에서 호출자가 기록한다. */
  public void makeAvailable(Instant purchaseConfirmedAt, Instant now) {
    requirePending();
    this.status = MileageAccrualStatus.AVAILABLE;
    this.purchaseConfirmedAt = truncate(purchaseConfirmedAt);
    this.availableAt = truncate(now);
    touch(now);
  }

  /** 구매확정 전 환불이 완료됐다. 잔액에 넣은 적이 없으므로 회수할 금액이 없다. */
  public void cancel(Instant now) {
    requirePending();
    this.status = MileageAccrualStatus.CANCELLED;
    this.cancelledAt = truncate(now);
    touch(now);
  }

  /** 같은 결제에 다시 온 예정이 처음 기록과 같은 정책 사본인지 확인한다. */
  public boolean matches(Payment payment, Policy policy) {
    return paymentId.equals(payment.getId())
        && orderId.equals(payment.getOrderId())
        && buyerId.equals(payment.getBuyerId())
        && baseAmount == policy.baseAmount()
        && rateBasisPoints == policy.rateBasisPoints()
        && perOrderCap == policy.perOrderCap()
        && accrualAmount == policy.accrualAmount()
        && policyVersion.equals(policy.policyVersion());
  }

  private void requirePending() {
    if (status != MileageAccrualStatus.PENDING) {
      throw new IllegalStateException("적립 예정 상태에서만 바꿀 수 있습니다: " + status);
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public UUID getBuyerId() {
    return buyerId;
  }

  public long getBaseAmount() {
    return baseAmount;
  }

  public int getRateBasisPoints() {
    return rateBasisPoints;
  }

  public long getPerOrderCap() {
    return perOrderCap;
  }

  public long getAccrualAmount() {
    return accrualAmount;
  }

  public String getPolicyVersion() {
    return policyVersion;
  }

  public MileageAccrualStatus getStatus() {
    return status;
  }

  public Instant getPaymentConfirmedAt() {
    return paymentConfirmedAt;
  }

  public Instant getPurchaseConfirmedAt() {
    return purchaseConfirmedAt;
  }

  public Instant getAvailableAt() {
    return availableAt;
  }

  public Instant getCancelledAt() {
    return cancelledAt;
  }

  public Long getVersion() {
    return version;
  }

  /**
   * 주문 생성 때 고정한 적립 정책 사본. 금액은 {@code min(perOrderCap, floor(baseAmount × rateBasisPoints /
   * 10,000))}과 같아야 하고, 0점은 적립 기록을 만들지 않으므로 양수다.
   */
  public record Policy(
      long baseAmount,
      int rateBasisPoints,
      long perOrderCap,
      long accrualAmount,
      String policyVersion) {
    public Policy {
      Objects.requireNonNull(policyVersion, "policyVersion");
      if (baseAmount <= 0
          || rateBasisPoints < 1
          || rateBasisPoints > BASIS_POINTS
          || perOrderCap <= 0
          || policyVersion.isBlank()
          || policyVersion.length() > 40
          || accrualAmount <= 0
          || accrualAmount != calculate(baseAmount, rateBasisPoints, perOrderCap)) {
        throw new IllegalArgumentException("적립 정책 사본이 계산 규칙과 맞지 않습니다.");
      }
    }

    /** 버림 계산. 기준액 상한(1,000만 원)과 적립률 상한(100%)에서 곱이 long 범위를 넘지 않는다. */
    public static long calculate(long baseAmount, int rateBasisPoints, long perOrderCap) {
      return Math.min(perOrderCap, Math.multiplyExact(baseAmount, rateBasisPoints) / BASIS_POINTS);
    }
  }
}
