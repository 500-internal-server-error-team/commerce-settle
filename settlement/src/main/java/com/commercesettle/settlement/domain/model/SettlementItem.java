package com.commercesettle.settlement.domain.model;

import com.commercesettle.settlement.domain.exception.InvalidSettlementTransitionException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 거래별 정산 항목. 상태는 전이 규칙과 지급 준비 조건을 검사하는 메서드로만 바꾼다. */
public class SettlementItem {
  private final UUID id;
  private final UUID paymentId;
  private final UUID orderId;
  private final UUID productId;
  private final UUID sellerId;
  private final SettlementAmounts amounts;
  private final long orderVersion;
  private SettlementItemStatus status;
  private Instant purchaseConfirmedAt;
  private Instant eligibleReceivedAt;
  private Instant readyAt;
  private UUID payoutId;

  private SettlementItem(
      UUID id,
      UUID paymentId,
      UUID orderId,
      UUID productId,
      UUID sellerId,
      SettlementAmounts amounts,
      long orderVersion,
      SettlementItemStatus status) {
    this.id = id;
    this.paymentId = Objects.requireNonNull(paymentId, "paymentId");
    this.orderId = Objects.requireNonNull(orderId, "orderId");
    this.productId = Objects.requireNonNull(productId, "productId");
    this.sellerId = Objects.requireNonNull(sellerId, "sellerId");
    this.amounts = Objects.requireNonNull(amounts, "amounts");
    this.orderVersion = orderVersion;
    this.status = status;
  }

  public static SettlementItem create(
      UUID paymentId,
      UUID orderId,
      UUID productId,
      UUID sellerId,
      SettlementAmounts amounts,
      long orderVersion) {
    return new SettlementItem(
        UUID.randomUUID(),
        paymentId,
        orderId,
        productId,
        sellerId,
        amounts,
        orderVersion,
        SettlementItemStatus.WAITING_CONFIRMATION);
  }

  /** 구매확정과 선행 결제 원장이 모두 준비되면 지급 가능으로 바꾸고, 두 시각 중 늦은 쪽을 readyAt으로 남긴다. */
  public void markPayable(Instant purchaseConfirmedAt, Instant eligibleReceivedAt) {
    Objects.requireNonNull(purchaseConfirmedAt, "purchaseConfirmedAt");
    Objects.requireNonNull(eligibleReceivedAt, "eligibleReceivedAt");
    if (status != SettlementItemStatus.WAITING_CONFIRMATION) {
      throw new InvalidSettlementTransitionException(status, SettlementItemStatus.PAYABLE);
    }
    transitionTo(SettlementItemStatus.PAYABLE);
    this.purchaseConfirmedAt = purchaseConfirmedAt;
    this.eligibleReceivedAt = eligibleReceivedAt;
    this.readyAt =
        purchaseConfirmedAt.isAfter(eligibleReceivedAt) ? purchaseConfirmedAt : eligibleReceivedAt;
  }

  public void reserveRefund() {
    transitionTo(SettlementItemStatus.REFUND_RESERVED);
  }

  public void completeRefund() {
    transitionTo(SettlementItemStatus.REFUNDED);
  }

  /**
   * 환불 예약을 보상해 예약 직전 상태로 되돌린다. 직전 상태와 최신 구매확정·환불 정보의 대조는 호출하는 쪽이 맡는다. 지급 준비 시각이 없으면 PAYABLE로 되돌리지
   * 않는다.
   */
  public void cancelRefundReservation(SettlementItemStatus previousStatus) {
    Objects.requireNonNull(previousStatus, "previousStatus");
    if (previousStatus == SettlementItemStatus.PAYABLE && readyAt == null) {
      throw new InvalidSettlementTransitionException(status, previousStatus);
    }
    transitionTo(previousStatus);
  }

  /** 지급에 배정한다. payoutId를 연결한 뒤에는 다른 지급에 다시 배정하지 않는다. */
  public void reservePayout(UUID payoutId) {
    Objects.requireNonNull(payoutId, "payoutId");
    transitionTo(SettlementItemStatus.PAYOUT_RESERVED);
    this.payoutId = payoutId;
  }

  public void completePayout() {
    transitionTo(SettlementItemStatus.PAID);
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

  public UUID getProductId() {
    return productId;
  }

  public UUID getSellerId() {
    return sellerId;
  }

  public SettlementAmounts getAmounts() {
    return amounts;
  }

  public long getOrderVersion() {
    return orderVersion;
  }

  public SettlementItemStatus getStatus() {
    return status;
  }

  public Instant getPurchaseConfirmedAt() {
    return purchaseConfirmedAt;
  }

  public Instant getEligibleReceivedAt() {
    return eligibleReceivedAt;
  }

  public Instant getReadyAt() {
    return readyAt;
  }

  public UUID getPayoutId() {
    return payoutId;
  }

  private void transitionTo(SettlementItemStatus next) {
    if (!status.canTransitionTo(next)) {
      throw new InvalidSettlementTransitionException(status, next);
    }
    status = next;
  }
}
