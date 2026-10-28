package com.commercesettle.payment.domain.model;

import com.commercesettle.payment.domain.exception.InvalidPaymentTransitionException;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 결제. 상태는 전이 규칙과 금액 조건을 검사하는 메서드로만 바꾼다. */
@Entity
@Table(name = "payments")
public class Payment extends AuditableEntity {
  @Id private UUID id;
  private UUID orderId;
  private long orderVersion;
  private UUID currentCycleId;
  private UUID buyerId;
  private UUID sellerId;
  private UUID productId;
  @Embedded private PaymentAmounts amounts;

  // 조회·정산용 파생 금액. 생성 시 amounts에서 한 번 계산해 저장한다.
  private long couponAmount;
  private long settlementBaseAmount;
  private long totalAmount;
  private long externalAmount;
  private long feeAmount;
  private long sellerNetAmount;

  private String policyVersion;

  @Enumerated(EnumType.STRING)
  private PaymentStatus status;

  private Instant confirmedAt;
  @Version private Long version;

  protected Payment() {}

  private Payment(
      UUID orderId,
      long orderVersion,
      UUID currentCycleId,
      UUID buyerId,
      UUID sellerId,
      UUID productId,
      PaymentAmounts amounts,
      String policyVersion,
      Instant now) {
    super(now);
    this.id = UUID.randomUUID();
    this.orderId = Objects.requireNonNull(orderId, "orderId");
    this.orderVersion = orderVersion;
    this.currentCycleId = Objects.requireNonNull(currentCycleId, "currentCycleId");
    this.buyerId = Objects.requireNonNull(buyerId, "buyerId");
    this.sellerId = Objects.requireNonNull(sellerId, "sellerId");
    this.productId = Objects.requireNonNull(productId, "productId");
    this.amounts = Objects.requireNonNull(amounts, "amounts");
    this.couponAmount = amounts.couponAmount();
    this.settlementBaseAmount = amounts.settlementBaseAmount();
    this.totalAmount = amounts.totalAmount();
    this.externalAmount = amounts.externalAmount();
    this.feeAmount = amounts.feeAmount();
    this.sellerNetAmount = amounts.sellerNetAmount();
    this.policyVersion = Objects.requireNonNull(policyVersion, "policyVersion");
    this.status = PaymentStatus.PENDING;
  }

  public static Payment create(
      UUID orderId,
      long orderVersion,
      UUID currentCycleId,
      UUID buyerId,
      UUID sellerId,
      UUID productId,
      PaymentAmounts amounts,
      String policyVersion,
      Instant now) {
    return new Payment(
        orderId,
        orderVersion,
        currentCycleId,
        buyerId,
        sellerId,
        productId,
        amounts,
        policyVersion,
        now);
  }

  /** 외부 결제액이 있는 결제만 PG 승인 처리로 넘긴다. */
  public void startProcessing(Instant now) {
    if (!hasExternalAmount()) {
      throw new InvalidPaymentTransitionException(status, PaymentStatus.PROCESSING);
    }
    transitionTo(PaymentStatus.PROCESSING, now);
  }

  /** PENDING에서 바로 확정하는 것은 외부 결제액이 0원인 마일리지 전액 결제만 허용한다. */
  public void confirm(Instant now) {
    if (status == PaymentStatus.PENDING && hasExternalAmount()) {
      throw new InvalidPaymentTransitionException(status, PaymentStatus.CONFIRMED);
    }
    transitionTo(PaymentStatus.CONFIRMED, now);
    this.confirmedAt = getUpdatedAt();
  }

  public void decline(Instant now) {
    transitionTo(PaymentStatus.DECLINED, now);
  }

  public void markUnknown(Instant now) {
    transitionTo(PaymentStatus.UNKNOWN, now);
  }

  public void cancel(Instant now) {
    transitionTo(PaymentStatus.CANCELLED, now);
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public long getOrderVersion() {
    return orderVersion;
  }

  public UUID getCurrentCycleId() {
    return currentCycleId;
  }

  public UUID getBuyerId() {
    return buyerId;
  }

  public UUID getSellerId() {
    return sellerId;
  }

  public UUID getProductId() {
    return productId;
  }

  public PaymentAmounts getAmounts() {
    return amounts;
  }

  public String getPolicyVersion() {
    return policyVersion;
  }

  public PaymentStatus getStatus() {
    return status;
  }

  public Instant getConfirmedAt() {
    return confirmedAt;
  }

  public Long getVersion() {
    return version;
  }

  private boolean hasExternalAmount() {
    return amounts.externalAmount() > 0;
  }

  private void transitionTo(PaymentStatus next, Instant now) {
    Objects.requireNonNull(now, "now");
    if (!status.canTransitionTo(next)) {
      throw new InvalidPaymentTransitionException(status, next);
    }
    status = next;
    touch(now);
  }
}
