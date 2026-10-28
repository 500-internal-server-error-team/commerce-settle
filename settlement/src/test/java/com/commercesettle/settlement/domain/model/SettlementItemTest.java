package com.commercesettle.settlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.settlement.domain.exception.InvalidSettlementTransitionException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementItemTest {

  private static final Instant EARLIER = Instant.parse("2026-10-28T00:00:00Z");
  private static final Instant LATER = Instant.parse("2026-10-28T01:00:00Z");
  private static final UUID PAYOUT_ID = UUID.randomUUID();

  @Test
  @DisplayName("정산 항목은 WAITING_CONFIRMATION으로 생성되고 지급 준비 시각과 지급 연결이 없다")
  void startsAsWaitingConfirmation() {
    SettlementItem item = item();

    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.WAITING_CONFIRMATION);
    assertThat(item.getReadyAt()).isNull();
    assertThat(item.getPayoutId()).isNull();
  }

  @Test
  @DisplayName("구매확정이 원장 준비보다 늦으면 구매확정 시각을 readyAt으로 남긴다")
  void usesPurchaseConfirmedAtWhenLater() {
    SettlementItem item = item();

    item.markPayable(LATER, EARLIER);

    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.PAYABLE);
    assertThat(item.getPurchaseConfirmedAt()).isEqualTo(LATER);
    assertThat(item.getEligibleReceivedAt()).isEqualTo(EARLIER);
    assertThat(item.getReadyAt()).isEqualTo(LATER);
  }

  @Test
  @DisplayName("원장 준비가 구매확정보다 늦으면 원장 준비 시각을 readyAt으로 남긴다")
  void usesEligibleReceivedAtWhenLater() {
    SettlementItem item = item();

    item.markPayable(EARLIER, LATER);

    assertThat(item.getReadyAt()).isEqualTo(LATER);
  }

  @Test
  @DisplayName("이미 지급 가능이면 다시 지급 가능으로 바꾸지 않고 readyAt을 유지한다")
  void rejectsSecondMarkPayable() {
    SettlementItem item = payableItem();

    assertThatThrownBy(() -> item.markPayable(LATER, LATER))
        .isInstanceOf(InvalidSettlementTransitionException.class);
    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.PAYABLE);
    assertThat(item.getReadyAt()).isEqualTo(EARLIER);
  }

  @Test
  @DisplayName("환불 예약 중에는 지급 가능으로 바꾸지 않는다")
  void rejectsMarkPayableWhileRefundReserved() {
    SettlementItem item = item();
    item.reserveRefund();

    assertThatThrownBy(() -> item.markPayable(EARLIER, EARLIER))
        .isInstanceOf(InvalidSettlementTransitionException.class);
    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.REFUND_RESERVED);
    assertThat(item.getReadyAt()).isNull();
  }

  @Test
  @DisplayName("구매확정 전 항목도 환불 예약 뒤 환불 완료할 수 있다")
  void refundsWaitingItem() {
    SettlementItem item = item();

    item.reserveRefund();
    item.completeRefund();

    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.REFUNDED);
  }

  @Test
  @DisplayName("환불 예약을 보상하면 예약 직전의 PAYABLE로 되돌린다")
  void restoresPayableAfterRefundReservation() {
    SettlementItem item = payableItem();
    item.reserveRefund();

    item.cancelRefundReservation(SettlementItemStatus.PAYABLE);

    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.PAYABLE);
    assertThat(item.getReadyAt()).isEqualTo(EARLIER);
  }

  @Test
  @DisplayName("환불 예약을 보상하면 예약 직전의 WAITING_CONFIRMATION으로 되돌린다")
  void restoresWaitingAfterRefundReservation() {
    SettlementItem item = item();
    item.reserveRefund();

    item.cancelRefundReservation(SettlementItemStatus.WAITING_CONFIRMATION);

    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.WAITING_CONFIRMATION);
  }

  @Test
  @DisplayName("지급 준비 시각이 없으면 환불 예약 보상으로 PAYABLE이 되지 않는다")
  void rejectsRestoringPayableWithoutReadyAt() {
    SettlementItem item = item();
    item.reserveRefund();

    assertThatThrownBy(() -> item.cancelRefundReservation(SettlementItemStatus.PAYABLE))
        .isInstanceOf(InvalidSettlementTransitionException.class);
    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.REFUND_RESERVED);
  }

  @Test
  @DisplayName("지급 가능 항목을 지급에 배정하고 지급 완료한다")
  void paysPayableItem() {
    SettlementItem item = payableItem();

    item.reservePayout(PAYOUT_ID);
    item.completePayout();

    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.PAID);
    assertThat(item.getPayoutId()).isEqualTo(PAYOUT_ID);
  }

  @Test
  @DisplayName("구매확정 전 항목은 지급에 배정하지 않는다")
  void rejectsPayoutBeforePayable() {
    SettlementItem item = item();

    assertThatThrownBy(() -> item.reservePayout(PAYOUT_ID))
        .isInstanceOf(InvalidSettlementTransitionException.class);
    assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.WAITING_CONFIRMATION);
    assertThat(item.getPayoutId()).isNull();
  }

  @Test
  @DisplayName("지급에 배정된 항목은 환불 예약하거나 지급 가능으로 되돌리지 않는다")
  void keepsPayoutReservation() {
    SettlementItem item = payableItem();
    item.reservePayout(PAYOUT_ID);

    assertThatThrownBy(item::reserveRefund)
        .isInstanceOf(InvalidSettlementTransitionException.class);
    assertThatThrownBy(() -> item.cancelRefundReservation(SettlementItemStatus.PAYABLE))
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
        1L);
  }

  private static SettlementItem payableItem() {
    SettlementItem item = item();
    item.markPayable(EARLIER, EARLIER);
    return item;
  }
}
