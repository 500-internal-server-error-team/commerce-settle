package com.commercesettle.commerce.domain.model;

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
 * 쿠폰 발급·예약·사용·해제·복구 이력과 예산 이동. (쿠폰, 작업, 업무 키)가 유일하므로 같은 회차의 예약·해제와 같은 원결제의 사용·복구는 한 번만 남는다. 템플릿의
 * 약속액·사용액은 이 이력의 증감을 같은 트랜잭션에서 더한 값이다.
 */
@Entity
@Table(name = "coupon_history")
public class CouponHistory {
  @Id private UUID id;
  private UUID buyerCouponId;
  private UUID orderId;

  @Enumerated(EnumType.STRING)
  private CouponOperation operation;

  private UUID businessRef;

  @Enumerated(EnumType.STRING)
  private BuyerCouponStatus fromStatus;

  @Enumerated(EnumType.STRING)
  private BuyerCouponStatus toStatus;

  private long committedDelta;
  private long consumedDelta;
  private Instant createdAt;

  protected CouponHistory() {}

  static CouponHistory of(
      UUID buyerCouponId,
      UUID orderId,
      CouponOperation operation,
      UUID businessRef,
      BuyerCouponStatus fromStatus,
      BuyerCouponStatus toStatus,
      long committedDelta,
      long consumedDelta,
      Instant now) {
    CouponHistory history = new CouponHistory();
    history.id = UUID.randomUUID();
    history.buyerCouponId = Objects.requireNonNull(buyerCouponId, "buyerCouponId");
    history.orderId = orderId;
    history.operation = Objects.requireNonNull(operation, "operation");
    history.businessRef = Objects.requireNonNull(businessRef, "businessRef");
    history.fromStatus = fromStatus;
    history.toStatus = Objects.requireNonNull(toStatus, "toStatus");
    history.committedDelta = committedDelta;
    history.consumedDelta = consumedDelta;
    history.createdAt = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
    return history;
  }

  public UUID getId() {
    return id;
  }

  public UUID getBuyerCouponId() {
    return buyerCouponId;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public CouponOperation getOperation() {
    return operation;
  }

  public UUID getBusinessRef() {
    return businessRef;
  }

  public BuyerCouponStatus getFromStatus() {
    return fromStatus;
  }

  public BuyerCouponStatus getToStatus() {
    return toStatus;
  }

  public long getCommittedDelta() {
    return committedDelta;
  }

  public long getConsumedDelta() {
    return consumedDelta;
  }
}
