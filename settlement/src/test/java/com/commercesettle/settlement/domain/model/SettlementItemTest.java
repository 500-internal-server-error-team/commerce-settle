package com.commercesettle.settlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.settlement.domain.exception.InvalidSettlementTransitionException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SettlementItemTest {

  private static final Instant EARLIER = Instant.parse("2026-10-28T00:00:00Z");
  private static final Instant LATER = Instant.parse("2026-10-28T01:00:00Z");
  private static final Instant CREATED_AT = Instant.parse("2026-10-28T02:00:00Z");
  private static final Instant CHANGED_AT = Instant.parse("2026-10-28T03:00:00Z");
  private static final UUID PAYOUT_ID = UUID.randomUUID();

  @Test
  @DisplayName("정산 항목은 WAITING_CONFIRMATION으로 생성되고 지급 준비 시각과 지급 연결이 없다")
  void startsAsWaitingConfirmation() {
    SettlementItem item = item();

    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.WAITING_CONFIRMATION);
    assertThat(item.getReadyAt()).isNull();
    assertThat(item.getPayoutId()).isNull();
    assertThat(item.getCreatedAt()).isEqualTo(CREATED_AT);
    assertThat(item.getUpdatedAt()).isEqualTo(CREATED_AT);
  }

  @Test
  @DisplayName("상태를 바꾸면 수정 시각만 갱신하고 생성 시각은 유지한다")
  void touchesUpdatedAtOnTransition() {
    SettlementItem item = item();

    item.reserveRefund(CHANGED_AT);

    assertThat(item.getCreatedAt()).isEqualTo(CREATED_AT);
    assertThat(item.getUpdatedAt()).isEqualTo(CHANGED_AT);
  }

  @Test
  @DisplayName("구매확정·원장 준비 시각은 마이크로초 단위로 남긴다")
  void truncatesReadyTimesToMicroseconds() {
    SettlementItem item = item();

    item.markPayable(
        Instant.parse("2026-10-28T00:00:00.123456789Z"),
        Instant.parse("2026-10-28T00:00:00.000000001Z"),
        CHANGED_AT);

    assertThat(item.getPurchaseConfirmedAt())
        .isEqualTo(Instant.parse("2026-10-28T00:00:00.123456Z"));
    assertThat(item.getEligibleReceivedAt()).isEqualTo(Instant.parse("2026-10-28T00:00:00Z"));
    assertThat(item.getReadyAt()).isEqualTo(Instant.parse("2026-10-28T00:00:00.123456Z"));
  }

  @Test
  @DisplayName("구매확정이 원장 준비보다 늦으면 구매확정 시각을 readyAt으로 남긴다")
  void usesPurchaseConfirmedAtWhenLater() {
    SettlementItem item = item();

    item.markPayable(LATER, EARLIER, CHANGED_AT);

    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.PAYABLE);
    assertThat(item.getPurchaseConfirmedAt()).isEqualTo(LATER);
    assertThat(item.getEligibleReceivedAt()).isEqualTo(EARLIER);
    assertThat(item.getReadyAt()).isEqualTo(LATER);
  }

  @Test
  @DisplayName("원장 준비가 구매확정보다 늦으면 원장 준비 시각을 readyAt으로 남긴다")
  void usesEligibleReceivedAtWhenLater() {
    SettlementItem item = item();

    item.markPayable(EARLIER, LATER, CHANGED_AT);

    assertThat(item.getReadyAt()).isEqualTo(LATER);
  }

  @Test
  @DisplayName("이미 지급 가능이면 다시 지급 가능으로 바꾸지 않고 readyAt과 수정 시각을 유지한다")
  void rejectsSecondMarkPayable() {
    SettlementItem item = payableItem();

    assertThatThrownBy(() -> item.markPayable(LATER, LATER, CHANGED_AT))
        .isInstanceOf(InvalidSettlementTransitionException.class);
    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.PAYABLE);
    assertThat(item.getReadyAt()).isEqualTo(EARLIER);
    assertThat(item.getUpdatedAt()).isEqualTo(CREATED_AT);
  }

  @Test
  @DisplayName("환불 예약 중에는 지급 가능으로 바꾸지 않는다")
  void rejectsMarkPayableWhileRefundReserved() {
    SettlementItem item = item();
    item.reserveRefund(CREATED_AT);

    assertThatThrownBy(() -> item.markPayable(EARLIER, EARLIER, CHANGED_AT))
        .isInstanceOf(InvalidSettlementTransitionException.class);
    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.REFUND_RESERVED);
    assertThat(item.getReadyAt()).isNull();
  }

  @Test
  @DisplayName("구매확정 전 항목도 환불 예약 뒤 환불 완료할 수 있다")
  void refundsWaitingItem() {
    SettlementItem item = item();

    item.reserveRefund(CHANGED_AT);
    item.completeRefund(CHANGED_AT);

    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.REFUNDED);
  }

  @Test
  @DisplayName("환불 예약을 보상하면 예약 직전의 PAYABLE로 되돌린다")
  void restoresPayableAfterRefundReservation() {
    SettlementItem item = payableItem();
    item.reserveRefund(CHANGED_AT);

    item.cancelRefundReservation(SettlementItemStatus.PAYABLE, CHANGED_AT);

    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.PAYABLE);
    assertThat(item.getReadyAt()).isEqualTo(EARLIER);
  }

  @Test
  @DisplayName("환불 예약을 보상하면 예약 직전의 WAITING_CONFIRMATION으로 되돌린다")
  void restoresWaitingAfterRefundReservation() {
    SettlementItem item = item();
    item.reserveRefund(CHANGED_AT);

    item.cancelRefundReservation(SettlementItemStatus.WAITING_CONFIRMATION, CHANGED_AT);

    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.WAITING_CONFIRMATION);
  }

  @Test
  @DisplayName("지급 준비 시각이 없으면 환불 예약 보상으로 PAYABLE이 되지 않는다")
  void rejectsRestoringPayableWithoutReadyAt() {
    SettlementItem item = item();
    item.reserveRefund(CREATED_AT);

    assertThatThrownBy(() -> item.cancelRefundReservation(SettlementItemStatus.PAYABLE, CHANGED_AT))
        .isInstanceOf(InvalidSettlementTransitionException.class);
    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.REFUND_RESERVED);
    assertThat(item.getUpdatedAt()).isEqualTo(CREATED_AT);
  }

  @Test
  @DisplayName("환불 예약이 아닌 항목은 환불 예약 보상으로 지급 예약이 되지 않고 상태·필드·수정 시각을 유지한다")
  void rejectsCancelingRefundOnPayableItem() {
    SettlementItem item = payableItem();

    assertThatThrownBy(
            () -> item.cancelRefundReservation(SettlementItemStatus.PAYOUT_RESERVED, CHANGED_AT))
        .isInstanceOf(InvalidSettlementTransitionException.class);
    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.PAYABLE);
    assertThat(item.getPayoutId()).isNull();
    assertThat(item.getReadyAt()).isEqualTo(EARLIER);
    assertThat(item.getUpdatedAt()).isEqualTo(CREATED_AT);
  }

  @Test
  @DisplayName("환불 예약이 아닌 항목은 환불 예약 보상으로 환불 예약이 되지 않는다")
  void rejectsCancelingRefundWithoutReservation() {
    SettlementItem payable = payableItem();
    SettlementItem waiting = item();

    assertThatThrownBy(
            () -> payable.cancelRefundReservation(SettlementItemStatus.REFUND_RESERVED, CHANGED_AT))
        .isInstanceOf(InvalidSettlementTransitionException.class);
    assertThatThrownBy(
            () -> waiting.cancelRefundReservation(SettlementItemStatus.REFUND_RESERVED, CHANGED_AT))
        .isInstanceOf(InvalidSettlementTransitionException.class);
    assertThat(payable.getStatus()).isEqualTo(SettlementItemStatus.PAYABLE);
    assertThat(payable.getUpdatedAt()).isEqualTo(CREATED_AT);
    assertThat(waiting.getStatus()).isEqualTo(SettlementItemStatus.WAITING_CONFIRMATION);
    assertThat(waiting.getUpdatedAt()).isEqualTo(CREATED_AT);
  }

  @ParameterizedTest
  @EnumSource(
      value = SettlementItemStatus.class,
      names = {"PAYOUT_RESERVED", "REFUNDED", "PAID"})
  @DisplayName("환불 예약 보상은 WAITING_CONFIRMATION·PAYABLE로만 되돌린다")
  void rejectsCancelingRefundToOtherStatus(SettlementItemStatus target) {
    SettlementItem item = payableItem();
    item.reserveRefund(CREATED_AT);

    assertThatThrownBy(() -> item.cancelRefundReservation(target, CHANGED_AT))
        .isInstanceOf(InvalidSettlementTransitionException.class);
    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.REFUND_RESERVED);
    assertThat(item.getPayoutId()).isNull();
    assertThat(item.getUpdatedAt()).isEqualTo(CREATED_AT);
  }

  @Test
  @DisplayName("지급 가능 항목을 지급에 배정하고 지급 완료한다")
  void paysPayableItem() {
    SettlementItem item = payableItem();

    item.reservePayout(PAYOUT_ID, CHANGED_AT);
    item.completePayout(CHANGED_AT);

    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.PAID);
    assertThat(item.getPayoutId()).isEqualTo(PAYOUT_ID);
  }

  @Test
  @DisplayName("구매확정 전 항목은 지급에 배정하지 않는다")
  void rejectsPayoutBeforePayable() {
    SettlementItem item = item();

    assertThatThrownBy(() -> item.reservePayout(PAYOUT_ID, CHANGED_AT))
        .isInstanceOf(InvalidSettlementTransitionException.class);
    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.WAITING_CONFIRMATION);
    assertThat(item.getPayoutId()).isNull();
  }

  @Test
  @DisplayName("지급에 배정된 항목은 환불 예약하거나 지급 가능으로 되돌리지 않는다")
  void keepsPayoutReservation() {
    SettlementItem item = payableItem();
    item.reservePayout(PAYOUT_ID, CHANGED_AT);

    assertThatThrownBy(() -> item.reserveRefund(CHANGED_AT))
        .isInstanceOf(InvalidSettlementTransitionException.class);
    assertThatThrownBy(() -> item.cancelRefundReservation(SettlementItemStatus.PAYABLE, CHANGED_AT))
        .isInstanceOf(InvalidSettlementTransitionException.class);
    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.PAYOUT_RESERVED);
    assertThat(item.getPayoutId()).isEqualTo(PAYOUT_ID);
  }

  private static SettlementItem item() {
    return SettlementItem.create(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        new SettlementAmounts(10_000, 0, 10_000, 8_000, 2_000, 1_500, 8_500),
        1L,
        CREATED_AT);
  }

  private static SettlementItem payableItem() {
    SettlementItem item = item();
    item.markPayable(EARLIER, EARLIER, CREATED_AT);
    return item;
  }
}
