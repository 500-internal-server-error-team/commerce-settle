package com.commercesettle.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.payment.domain.exception.InvalidRefundTransitionException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefundSagaTest {

  private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

  @Test
  @DisplayName("예약·외부 환불·복원·원장 상쇄·주문 복구를 차례로 기록한다")
  void recordsEachConfirmationInOrder() {
    RefundSaga saga = RefundSaga.start(refund(), NOW);
    UUID reservation = UUID.randomUUID();
    Instant reversedAt = Instant.parse("2026-11-02T00:00:05Z");

    assertThat(saga.getStep()).isEqualTo(RefundSagaStep.RESERVATION_REQUESTED);
    saga.reserved(reservation, NOW);
    saga.externalSettled(NOW);
    saga.mileageRestored(NOW);
    saga.ledgerReversed(reversedAt, NOW);
    assertThat(saga.getOrderRestoredAt()).isNull();
    saga.orderRestored(NOW);

    assertThat(saga.getStep()).isEqualTo(RefundSagaStep.COMPLETED);
    assertThat(saga.getReservationRef()).isEqualTo(reservation);
    assertThat(saga.getLedgerReversedAt()).isEqualTo(reversedAt);
    assertThat(saga.getOrderRestoredAt()).isEqualTo(NOW);
  }

  @Test
  @DisplayName("외부 확정 거절은 예약 해제 확인 뒤 실패로 끝난다")
  void failsAfterReleaseConfirmed() {
    RefundSaga saga = RefundSaga.start(refund(), NOW);
    saga.reserved(UUID.randomUUID(), NOW);

    saga.externalDeclined("REFUND_EXTERNAL_DECLINED", NOW);
    assertThat(saga.getStep()).isEqualTo(RefundSagaStep.RELEASE_PENDING);
    saga.released(NOW);

    assertThat(saga.getStep()).isEqualTo(RefundSagaStep.FAILED);
    assertThat(saga.getLastErrorCode()).isEqualTo("REFUND_EXTERNAL_DECLINED");
  }

  @Test
  @DisplayName("기다리는 확인이 아닌 응답으로 단계를 건너뛰지 않는다")
  void rejectsOutOfOrderConfirmation() {
    RefundSaga saga = RefundSaga.start(refund(), NOW);

    assertThatThrownBy(() -> saga.ledgerReversed(NOW, NOW))
        .isInstanceOf(InvalidRefundTransitionException.class);
    assertThatThrownBy(() -> saga.released(NOW))
        .isInstanceOf(InvalidRefundTransitionException.class);
    assertThat(saga.getStep()).isEqualTo(RefundSagaStep.RESERVATION_REQUESTED);
  }

  @Test
  @DisplayName("정산 응답을 기다리는 단계는 예약·원장 상쇄·예약 해제뿐이다")
  void identifiesStepsAwaitingSettlement() {
    assertThat(RefundSagaStep.values())
        .filteredOn(RefundSagaStep::awaitsSettlement)
        .containsExactlyInAnyOrder(
            RefundSagaStep.RESERVATION_REQUESTED,
            RefundSagaStep.LEDGER_REVERSAL_PENDING,
            RefundSagaStep.RELEASE_PENDING);
  }

  private static Refund refund() {
    return Refund.accept(
        RefundTest.confirmed(new PaymentAmounts(10_000, 0, 0, 0)), UUID.randomUUID(), NOW);
  }
}
