package com.commercesettle.payment.domain.model;

import com.commercesettle.payment.domain.exception.InvalidRefundTransitionException;
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
 * 확정된 원결제의 전액 환불. 외부 결제액(E)과 사용 마일리지(M)는 접수 때 원결제에서 복사하고 바꾸지 않는다. 외부 환불은 E만 원거래에 요청하고, 외부 환불 성공을
 * 확인한 뒤에만 M을 복원한다.
 */
@Entity
@Table(name = "refunds")
public class Refund extends AuditableEntity {
  @Id private UUID id;
  private UUID paymentId;
  private UUID orderRefundRequestId;
  private long externalAmount;
  private long mileageAmount;

  @Enumerated(EnumType.STRING)
  private RefundStatus status;

  private UUID businessKey;

  @Enumerated(EnumType.STRING)
  private ExternalRefundStatus externalStatus;

  private UUID externalRef;
  private Instant mileageRestoredAt;
  private Instant unknownSince;
  @Version private Long version;

  protected Refund() {}

  private Refund(Payment payment, UUID orderRefundRequestId, Instant now) {
    super(now);
    this.id = UUID.randomUUID();
    this.paymentId = payment.getId();
    this.orderRefundRequestId = Objects.requireNonNull(orderRefundRequestId, "requestId");
    this.externalAmount = payment.getAmounts().externalAmount();
    this.mileageAmount = payment.getAmounts().mileageAmount();
    this.status = RefundStatus.RESERVING;
    this.businessKey = UUID.randomUUID();
    this.externalStatus =
        externalAmount > 0 ? ExternalRefundStatus.PENDING : ExternalRefundStatus.NOT_REQUIRED;
  }

  /** 확정된 결제의 전액 환불을 접수한다. 정산금 예약 결과를 기다리는 RESERVING으로 시작한다. */
  public static Refund accept(Payment payment, UUID orderRefundRequestId, Instant now) {
    Objects.requireNonNull(payment, "payment");
    if (payment.getStatus() != PaymentStatus.CONFIRMED) {
      throw new IllegalArgumentException("확정된 결제만 환불할 수 있습니다: " + payment.getStatus());
    }
    return new Refund(payment, orderRefundRequestId, now);
  }

  /** 정산금 예약이 확인됐다. 외부 환불(또는 외부 결제액 0원의 생략)을 진행한다. */
  public void reserved(Instant now) {
    transitionTo(RefundStatus.EXTERNAL_PENDING, now);
  }

  /** 정산금 예약이 거절됐다. 외부 환불을 호출하지 않고 실패로 끝낸다. */
  public void reservationRejected(Instant now) {
    transitionTo(RefundStatus.DECLINED, now);
  }

  /**
   * 외부 대역을 호출하기 직전에 남긴다. 이 기록 뒤 중단되면 호출이 대역에 닿았는지 알 수 없으므로 재개할 때 원거래 조회부터 한다. 외부 결과가 확정되지 않은 상태에서만
   * 허용한다.
   */
  public void externalRequested(Instant now) {
    requireExternalOpen();
    externalStatus = ExternalRefundStatus.REQUESTED;
    touch(now);
  }

  /** 외부 환불 성공을 확인했다. 마일리지 복원 대기로 넘어간다. */
  public void externalSucceeded(UUID externalRef, Instant now) {
    requireExternalOpen();
    transitionTo(RefundStatus.RESTORING, now);
    this.externalRef = Objects.requireNonNull(externalRef, "externalRef");
    externalStatus = ExternalRefundStatus.SUCCEEDED;
  }

  /** 외부 결제액이 0원이라 외부 호출 없이 마일리지 복원 대기로 넘어간다. */
  public void externalSkipped(Instant now) {
    if (externalStatus != ExternalRefundStatus.NOT_REQUIRED) {
      throw new InvalidRefundTransitionException(externalStatus, ExternalRefundStatus.NOT_REQUIRED);
    }
    transitionTo(RefundStatus.RESTORING, now);
  }

  /** 외부 환불이 확정 거절됐다. 마일리지를 복원하지 않고 정산금 예약 해제를 기다린다. */
  public void externalDeclined(Instant now) {
    requireExternalOpen();
    transitionTo(RefundStatus.COMPENSATING, now);
    externalStatus = ExternalRefundStatus.DECLINED;
  }

  /** 외부 결과를 확정하지 못했다. 예약을 유지하고 같은 원거래를 다시 조회한다. */
  public void externalUnknown(Instant now) {
    requireExternalOpen();
    if (status != RefundStatus.UNKNOWN) {
      transitionTo(RefundStatus.UNKNOWN, now);
      unknownSince = getUpdatedAt();
    } else {
      touch(now);
    }
    externalStatus = ExternalRefundStatus.UNKNOWN;
  }

  /** 사용 마일리지를 복원했다(0점이면 복원할 금액 없이 기록). 원장 상쇄와 주문 복구 확인을 기다린다. */
  public void mileageRestored(Instant now) {
    transitionTo(RefundStatus.INTERNAL_PENDING, now);
    mileageRestoredAt = getUpdatedAt();
  }

  /** 커머스의 주문 복구까지 확인했다. 금융 환불 전체가 끝난다. */
  public void complete(Instant now) {
    transitionTo(RefundStatus.COMPLETED, now);
  }

  /** 외부 확정 거절 뒤 정산금 예약 해제가 확인됐다. */
  public void compensated(Instant now) {
    transitionTo(RefundStatus.DECLINED, now);
  }

  /** 외부 대역에 환불을 요청해야 하거나 원거래 조회로 결과를 확인해야 하는 상태다. */
  public boolean awaitsExternalResult() {
    return status == RefundStatus.EXTERNAL_PENDING || status == RefundStatus.UNKNOWN;
  }

  private void requireExternalOpen() {
    if (!awaitsExternalResult() || externalStatus == ExternalRefundStatus.NOT_REQUIRED) {
      throw new InvalidRefundTransitionException(status, externalStatus);
    }
  }

  private void transitionTo(RefundStatus next, Instant now) {
    Objects.requireNonNull(now, "now");
    if (!status.canTransitionTo(next)) {
      throw new InvalidRefundTransitionException(status, next);
    }
    status = next;
    touch(now);
  }

  public UUID getId() {
    return id;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public UUID getOrderRefundRequestId() {
    return orderRefundRequestId;
  }

  public long getExternalAmount() {
    return externalAmount;
  }

  public long getMileageAmount() {
    return mileageAmount;
  }

  public RefundStatus getStatus() {
    return status;
  }

  public UUID getBusinessKey() {
    return businessKey;
  }

  public ExternalRefundStatus getExternalStatus() {
    return externalStatus;
  }

  public UUID getExternalRef() {
    return externalRef;
  }

  public Instant getMileageRestoredAt() {
    return mileageRestoredAt;
  }

  public Long getVersion() {
    return version;
  }

  public Instant getUnknownSince() {
    return unknownSince;
  }
}
