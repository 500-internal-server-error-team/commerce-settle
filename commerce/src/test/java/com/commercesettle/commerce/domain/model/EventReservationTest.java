package com.commercesettle.commerce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.domain.exception.EventAdmissionClosedException;
import com.commercesettle.commerce.domain.exception.EventAdmissionClosedException.Reason;
import com.commercesettle.commerce.domain.exception.InvalidReservationTransitionException;
import com.commercesettle.commerce.domain.model.InventoryReservation.Outcome;
import com.commercesettle.commerce.domain.model.InventoryReservation.Result;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 선착순 주문 예약의 확정·해제·재시도·복원이 이벤트 S·R, 참여 상태, 재고 수량을 함께 바꾸는지 확인한다. */
class EventReservationTest {
  private static final Instant NOW = Instant.parse("2026-11-02T01:00:00Z");
  private static final Instant STARTS = NOW.minus(Duration.ofHours(1));
  private static final Instant ENDS = NOW.plus(Duration.ofHours(1));
  private static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  private static final UUID BUYER = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
  private static final UUID CYCLE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final UUID NEXT_CYCLE = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

  private final ProductInventory inventory = stocked(10);
  private final SaleEvent event = openEvent(3);
  private final UUID orderId = UUID.randomUUID();
  private final InventoryReservation reservation = reserve(orderId);
  private final EventParticipation participation =
      EventParticipation.reserve(UUID.randomUUID(), event.getId(), BUYER, orderId, NOW);

  @Test
  @DisplayName("선착순 예약은 상품 1개를 이벤트 R로만 잡고 재고 행은 바꾸지 않는다")
  void reservesOneFromEventWithoutInventoryChange() {
    assertThat(reservation.getStatus()).isEqualTo(InventoryReservationStatus.RESERVED);
    assertThat(reservation.getQuantity()).isEqualTo(1);
    assertThat(reservation.getEventId()).isEqualTo(event.getId());
    assertThat(reservation.isForEvent()).isTrue();
    assertThat(event.getReservedCount()).isEqualTo(1);
    assertInventory(7, 3, 0);
  }

  @Test
  @DisplayName("결제 확정은 R을 S로 옮기고 배정분 1개를 판매로 옮기며 참여를 확정한다. 재처리와 다른 회차 결과는 무시한다")
  void consumeMovesSlotToSold() {
    Result result = consume(CYCLE);

    assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
    assertMovement(result.movement(), InventoryOperation.ORDER_CONSUME, 0, -1, 1);
    assertSlots(1, 0);
    assertInventory(7, 2, 1);
    assertThat(participation.getStatus()).isEqualTo(EventParticipationStatus.CONFIRMED);
    assertThat(reservation.getStatus()).isEqualTo(InventoryReservationStatus.CONSUMED);
    assertThat(consume(CYCLE).outcome()).isEqualTo(Outcome.ALREADY_APPLIED);
    assertSlots(1, 0);
  }

  @Test
  @DisplayName("다른 회차의 확정·해제 결과는 이벤트·참여·재고를 바꾸지 않는다")
  void ignoresAnotherCycle() {
    assertThat(consume(NEXT_CYCLE).outcome()).isEqualTo(Outcome.STALE_CYCLE);
    assertThat(release(NEXT_CYCLE).outcome()).isEqualTo(Outcome.STALE_CYCLE);

    assertSlots(0, 1);
    assertThat(participation.getStatus()).isEqualTo(EventParticipationStatus.RESERVED);
    assertInventory(7, 3, 0);
  }

  @Test
  @DisplayName("마지막 수량이 확정되면 진행 중 이벤트를 매진 종료하고 게이지는 100%다")
  void lastConfirmationSellsOut() {
    SaleEvent single = openEvent(1);
    InventoryReservation only = reserveOn(single, orderId);

    only.consumeForEvent(inventory, single, participationOn(single), CYCLE, UUID.randomUUID(), NOW);

    assertThat(single.getStatus()).isEqualTo(SaleEventStatus.SOLD_OUT);
    assertThat(single.getClosedAt()).isEqualTo(NOW);
    assertThat(single.gauge(NOW).percent()).isEqualTo(100);
    assertThat(single.gauge(NOW).admission()).isEqualTo(EventAdmission.SOLD_OUT);
  }

  @Test
  @DisplayName("진행 중 해제는 R만 줄이고 재고 이력을 남기지 않으며 참여를 해제한다")
  void releaseInProgressOnlyFreesSlot() {
    Result result = release(CYCLE);

    assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(result.movement()).isNull();
    assertSlots(0, 0);
    assertInventory(7, 3, 0);
    assertThat(participation.getStatus()).isEqualTo(EventParticipationStatus.RELEASED);
    assertThat(release(CYCLE).outcome()).isEqualTo(Outcome.ALREADY_APPLIED);
  }

  @Test
  @DisplayName("종료 뒤 해제는 종료 때 남겨 둔 배정분 1개를 가용 재고로 돌린다")
  void releaseAfterCloseReturnsAllocation() {
    event.close(inventory, event.getVersion(), "조기 종료", UUID.randomUUID(), NOW);
    assertInventory(9, 1, 0);

    Result result = release(CYCLE);

    assertMovement(result.movement(), InventoryOperation.ORDER_RELEASE, 1, -1, 0);
    assertSlots(0, 0);
    assertInventory(10, 0, 0);
  }

  @Test
  @DisplayName("종료 뒤 확정은 남겨 둔 배정분을 판매로 옮기고 종료 상태를 유지한다")
  void consumeAfterCloseKeepsClosed() {
    event.close(inventory, event.getVersion(), "조기 종료", UUID.randomUUID(), NOW);

    consume(CYCLE);

    assertThat(event.getStatus()).isEqualTo(SaleEventStatus.CLOSED);
    assertSlots(1, 0);
    assertInventory(9, 0, 1);
  }

  @Test
  @DisplayName("재시도는 해제된 예약을 새 회차로 R+1 해 다시 확보하고 같은 회차 재요청은 다시 확보하지 않는다")
  void reserveAgainTakesSlotWithNewCycle() {
    release(CYCLE);

    Result result = reserveAgain(NEXT_CYCLE, NOW);

    assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(result.movement()).isNull();
    assertThat(reservation.getCycleId()).isEqualTo(NEXT_CYCLE);
    assertThat(participation.getStatus()).isEqualTo(EventParticipationStatus.RESERVED);
    assertSlots(0, 1);
    assertThat(reserveAgain(NEXT_CYCLE, NOW).outcome()).isEqualTo(Outcome.ALREADY_APPLIED);
    assertSlots(0, 1);
  }

  @Test
  @DisplayName("남은 수량이 없거나 이벤트가 끝났으면 재시도를 거절하고 해제 상태를 유지한다")
  void rejectsReserveAgainWithoutRemainingOrAfterEnd() {
    release(CYCLE);
    event.reserveSlot(NOW);
    event.reserveSlot(NOW);
    event.reserveSlot(NOW);

    assertThatThrownBy(() -> reserveAgain(NEXT_CYCLE, NOW))
        .extracting(e -> ((EventAdmissionClosedException) e).reason())
        .isEqualTo(Reason.NO_REMAINING);
    assertThatThrownBy(() -> reserveAgain(NEXT_CYCLE, ENDS))
        .extracting(e -> ((EventAdmissionClosedException) e).reason())
        .isEqualTo(Reason.NOT_OPEN);

    assertThat(reservation.getStatus()).isEqualTo(InventoryReservationStatus.RELEASED);
    assertThat(reservation.getCycleId()).isEqualTo(CYCLE);
    assertThat(participation.getStatus()).isEqualTo(EventParticipationStatus.RELEASED);
    assertSlots(0, 3);
  }

  @Test
  @DisplayName("환불 복구는 판매를 가용으로 한 번만 돌리고 S·게이지와 참여 한도는 되돌리지 않는다")
  void restoreKeepsSoldCountAndParticipation() {
    consume(CYCLE);

    Result restored = restore();

    assertMovement(restored.movement(), InventoryOperation.REFUND_RESTORE, 1, 0, -1);
    assertInventory(8, 2, 0);
    assertSlots(1, 0);
    assertThat(participation.getStatus()).isEqualTo(EventParticipationStatus.REFUNDED);
    assertThat(restore().outcome()).isEqualTo(Outcome.ALREADY_APPLIED);
    assertInventory(8, 2, 0);
  }

  @Test
  @DisplayName("결제 확정 뒤 해제는 금지된 전이로 거절하고 이벤트·참여를 바꾸지 않는다")
  void rejectsReleaseAfterConsume() {
    consume(CYCLE);

    assertThatThrownBy(() -> release(CYCLE))
        .isInstanceOf(InvalidReservationTransitionException.class);
    assertSlots(1, 0);
    assertThat(participation.getStatus()).isEqualTo(EventParticipationStatus.CONFIRMED);
  }

  @Test
  @DisplayName("일반 주문과 선착순 주문의 전이 메서드를 섞어 쓰면 거절한다")
  void rejectsMixedTransitions() {
    InventoryReservation general =
        InventoryReservation.reserve(
                UUID.randomUUID(),
                UUID.randomUUID(),
                inventory,
                1,
                CYCLE,
                ENDS,
                UUID.randomUUID(),
                NOW)
            .reservation();

    assertThatThrownBy(() -> reservation.consume(inventory, CYCLE, UUID.randomUUID(), NOW))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                general.consumeForEvent(
                    inventory, event, participation, CYCLE, UUID.randomUUID(), NOW))
        .isInstanceOf(IllegalStateException.class);
    assertThat(reservation.isSameReservation(PRODUCT, 1, CYCLE)).isFalse();
    assertThat(reservation.isSameEventReservation(event.getId(), CYCLE)).isTrue();
  }

  @Test
  @DisplayName("신규 수량 확보는 열린 기간에 남은 수량이 있을 때만 R을 늘린다")
  void reserveSlotChecksPeriodAndRemaining() {
    SaleEvent scheduled =
        SaleEvent.register(
            UUID.randomUUID(),
            UUID.randomUUID(),
            PRODUCT,
            "주말 특가",
            10_000,
            1,
            NOW.plusSeconds(60),
            ENDS,
            NOW);
    scheduled.publish(stocked(1), 0, UUID.randomUUID(), NOW);
    SaleEvent single = openEvent(1);
    single.reserveSlot(NOW);

    assertThatThrownBy(() -> scheduled.reserveSlot(NOW))
        .extracting(e -> ((EventAdmissionClosedException) e).reason())
        .isEqualTo(Reason.NOT_OPEN);
    assertThatThrownBy(() -> single.reserveSlot(NOW))
        .extracting(e -> ((EventAdmissionClosedException) e).reason())
        .isEqualTo(Reason.NO_REMAINING);
    assertThat(scheduled.getReservedCount()).isZero();
    assertThat(single.getReservedCount()).isEqualTo(1);
  }

  private Result consume(UUID cycle) {
    return reservation.consumeForEvent(
        inventory, event, participation, cycle, UUID.randomUUID(), NOW);
  }

  private Result release(UUID cycle) {
    return reservation.releaseForEvent(
        inventory, event, participation, cycle, UUID.randomUUID(), NOW);
  }

  private Result reserveAgain(UUID cycle, Instant now) {
    return reservation.reserveAgainForEvent(
        inventory, event, participation, cycle, now.plusSeconds(600), now);
  }

  private Result restore() {
    return reservation.restoreForEvent(
        inventory,
        event,
        participation,
        InventoryOperation.REFUND_RESTORE,
        UUID.randomUUID(),
        UUID.randomUUID(),
        NOW);
  }

  private InventoryReservation reserve(UUID order) {
    return reserveOn(event, order);
  }

  /** 저장소의 조건부 갱신 대신 이벤트 R을 직접 늘린 뒤 예약을 만든다. */
  private static InventoryReservation reserveOn(SaleEvent target, UUID order) {
    target.reserveSlot(NOW);
    return InventoryReservation.reserveForEvent(
        UUID.randomUUID(), order, PRODUCT, target.getId(), CYCLE, ENDS, NOW);
  }

  private EventParticipation participationOn(SaleEvent target) {
    return EventParticipation.reserve(UUID.randomUUID(), target.getId(), BUYER, orderId, NOW);
  }

  private SaleEvent openEvent(long quota) {
    SaleEvent opened =
        SaleEvent.register(
            UUID.randomUUID(),
            UUID.randomUUID(),
            PRODUCT,
            "주말 특가",
            10_000,
            quota,
            STARTS,
            ENDS,
            NOW);
    opened.publish(inventory, 0, UUID.randomUUID(), NOW);
    return opened;
  }

  private void assertSlots(long sold, long reserved) {
    assertThat(event.getSoldCount()).isEqualTo(sold);
    assertThat(event.getReservedCount()).isEqualTo(reserved);
  }

  private void assertInventory(long available, long allocated, long sold) {
    assertThat(inventory.getAvailableQuantity()).isEqualTo(available);
    assertThat(inventory.getEventAllocatedQuantity()).isEqualTo(allocated);
    assertThat(inventory.getSoldQuantity()).isEqualTo(sold);
    assertThat(inventory.getReservedQuantity()).isZero();
  }

  private static void assertMovement(
      InventoryMovement movement,
      InventoryOperation operation,
      long available,
      long allocated,
      long sold) {
    assertThat(movement.getOperation()).isEqualTo(operation);
    assertThat(movement.getAvailableDelta()).isEqualTo(available);
    assertThat(movement.getAllocatedDelta()).isEqualTo(allocated);
    assertThat(movement.getSoldDelta()).isEqualTo(sold);
    assertThat(movement.getReservedDelta()).isZero();
  }

  private static ProductInventory stocked(long quantity) {
    ProductInventory stocked = ProductInventory.empty(UUID.randomUUID(), PRODUCT, NOW);
    stocked.adjustAvailable(UUID.randomUUID(), UUID.randomUUID(), quantity, "입고", 0, NOW);
    return stocked;
  }
}
