package com.commercesettle.payment.domain.model;

import com.commercesettle.payment.domain.exception.InvalidPaymentTransitionException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 결제. 상태는 전이 규칙과 금액 조건을 검사하는 메서드로만 바꾼다. */
public class Payment {
  private final UUID id;
  private final UUID orderId;
  private final UUID buyerId;
  private final UUID sellerId;
  private final UUID productId;
  private final PaymentAmounts amounts;
  private final String policyVersion;
  private PaymentStatus status;
  private Instant confirmedAt;

  private Payment(
      UUID id,
      UUID orderId,
      UUID buyerId,
      UUID sellerId,
      UUID productId,
      PaymentAmounts amounts,
      String policyVersion,
      PaymentStatus status) {
    this.id = id;
    this.orderId = Objects.requireNonNull(orderId, "orderId");
    this.buyerId = Objects.requireNonNull(buyerId, "buyerId");
    this.sellerId = Objects.requireNonNull(sellerId, "sellerId");
    this.productId = Objects.requireNonNull(productId, "productId");
    this.amounts = Objects.requireNonNull(amounts, "amounts");
    this.policyVersion = Objects.requireNonNull(policyVersion, "policyVersion");
    this.status = status;
  }

  public static Payment create(
      UUID orderId,
      UUID buyerId,
      UUID sellerId,
      UUID productId,
      PaymentAmounts amounts,
      String policyVersion) {
    return new Payment(
        UUID.randomUUID(),
        orderId,
        buyerId,
        sellerId,
        productId,
        amounts,
        policyVersion,
        PaymentStatus.PENDING);
  }

  /** 외부 결제액이 있는 결제만 PG 승인 처리로 넘긴다. */
  public void startProcessing() {
    if (!hasExternalAmount()) {
      throw new InvalidPaymentTransitionException(status, PaymentStatus.PROCESSING);
    }
    transitionTo(PaymentStatus.PROCESSING);
  }

  /** PENDING에서 바로 확정하는 것은 외부 결제액이 0원인 마일리지 전액 결제만 허용한다. */
  public void confirm(Instant confirmedAt) {
    Objects.requireNonNull(confirmedAt, "confirmedAt");
    if (status == PaymentStatus.PENDING && hasExternalAmount()) {
      throw new InvalidPaymentTransitionException(status, PaymentStatus.CONFIRMED);
    }
    transitionTo(PaymentStatus.CONFIRMED);
    this.confirmedAt = confirmedAt;
  }

  public void decline() {
    transitionTo(PaymentStatus.DECLINED);
  }

  public void markUnknown() {
    transitionTo(PaymentStatus.UNKNOWN);
  }

  public void cancel() {
    transitionTo(PaymentStatus.CANCELLED);
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrderId() {
    return orderId;
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

  private boolean hasExternalAmount() {
    return amounts.externalAmount() > 0;
  }

  private void transitionTo(PaymentStatus next) {
    if (!status.canTransitionTo(next)) {
      throw new InvalidPaymentTransitionException(status, next);
    }
    status = next;
  }
}
