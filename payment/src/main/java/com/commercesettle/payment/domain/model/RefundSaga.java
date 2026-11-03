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
 * 환불 하나의 정산 예약·외부 환불·마일리지 복원·원장 상쇄·주문 복구 진행. 금융 환불 상태와 따로 각 확인 시각을 남겨, 외부 환불 성공 뒤 내부 반영이 늦어도 전체 완료로
 * 표시하지 않는다.
 */
@Entity
@Table(name = "refund_sagas")
public class RefundSaga extends AuditableEntity {
  @Id private UUID id;
  private UUID refundId;

  @Enumerated(EnumType.STRING)
  private RefundSagaStep step;

  private UUID reservationRef;
  private Instant ledgerReversedAt;
  private Instant orderRestoredAt;
  private String lastErrorCode;
  @Version private Long version;

  protected RefundSaga() {}

  private RefundSaga(Refund refund, Instant now) {
    super(now);
    this.id = UUID.randomUUID();
    this.refundId = refund.getId();
    this.step = RefundSagaStep.RESERVATION_REQUESTED;
  }

  /** 접수한 환불의 SAGA를 정산금 예약 요청 단계로 시작한다. */
  public static RefundSaga start(Refund refund, Instant now) {
    return new RefundSaga(Objects.requireNonNull(refund, "refund"), now);
  }

  public void reserved(UUID reservationRef, Instant now) {
    move(RefundSagaStep.RESERVATION_REQUESTED, RefundSagaStep.EXTERNAL_REFUND_PENDING, now);
    this.reservationRef = Objects.requireNonNull(reservationRef, "reservationRef");
  }

  public void reservationRejected(String reasonCode, Instant now) {
    move(RefundSagaStep.RESERVATION_REQUESTED, RefundSagaStep.FAILED, now);
    this.lastErrorCode = reasonCode;
  }

  public void externalSettled(Instant now) {
    move(RefundSagaStep.EXTERNAL_REFUND_PENDING, RefundSagaStep.MILEAGE_RESTORE_PENDING, now);
  }

  public void externalDeclined(String reasonCode, Instant now) {
    move(RefundSagaStep.EXTERNAL_REFUND_PENDING, RefundSagaStep.RELEASE_PENDING, now);
    this.lastErrorCode = reasonCode;
  }

  public void mileageRestored(Instant now) {
    move(RefundSagaStep.MILEAGE_RESTORE_PENDING, RefundSagaStep.LEDGER_REVERSAL_PENDING, now);
  }

  public void ledgerReversed(Instant reversedAt, Instant now) {
    move(RefundSagaStep.LEDGER_REVERSAL_PENDING, RefundSagaStep.ORDER_RESTORE_PENDING, now);
    this.ledgerReversedAt = truncate(reversedAt);
  }

  public void orderRestored(Instant now) {
    move(RefundSagaStep.ORDER_RESTORE_PENDING, RefundSagaStep.COMPLETED, now);
    this.orderRestoredAt = getUpdatedAt();
  }

  public void released(Instant now) {
    move(RefundSagaStep.RELEASE_PENDING, RefundSagaStep.FAILED, now);
  }

  /** 다음 단계로 넘기지 못한 마지막 사유. 단계는 바꾸지 않는다. */
  public void recordError(String errorCode, Instant now) {
    this.lastErrorCode = errorCode;
    touch(now);
  }

  private void move(RefundSagaStep expected, RefundSagaStep next, Instant now) {
    if (step != expected) {
      throw new InvalidRefundTransitionException(step, next);
    }
    step = next;
    touch(now);
  }

  public UUID getId() {
    return id;
  }

  public UUID getRefundId() {
    return refundId;
  }

  public RefundSagaStep getStep() {
    return step;
  }

  public UUID getReservationRef() {
    return reservationRef;
  }

  public Instant getLedgerReversedAt() {
    return ledgerReversedAt;
  }

  public Instant getOrderRestoredAt() {
    return orderRestoredAt;
  }

  public String getLastErrorCode() {
    return lastErrorCode;
  }

  public Long getVersion() {
    return version;
  }
}
