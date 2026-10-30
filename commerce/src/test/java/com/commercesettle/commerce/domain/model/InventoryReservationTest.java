package com.commercesettle.commerce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.domain.exception.InsufficientInventoryException;
import com.commercesettle.commerce.domain.exception.InvalidReservationTransitionException;
import com.commercesettle.commerce.domain.model.InventoryReservation.Outcome;
import com.commercesettle.commerce.domain.model.InventoryReservation.Result;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class InventoryReservationTest {
  private static final Instant NOW = Instant.parse("2026-10-30T01:00:00Z");
  private static final Instant EXPIRES = NOW.plusSeconds(600);
  private static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  private static final UUID CYCLE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final UUID NEXT_CYCLE = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

  private final ProductInventory inventory = stocked(10);

  @Test
  @DisplayName("예약은 가용을 예약으로 옮기고 결제 회차를 업무 참조로 한 이력을 만든다")
  void reserveMovesAvailableToReserved() {
    InventoryReservation.Created created = reserve(3);

    assertThat(created.reservation().getStatus()).isEqualTo(InventoryReservationStatus.RESERVED);
    assertQuantities(7, 3, 0);
    assertMovement(created.movement(), InventoryOperation.ORDER_RESERVE, CYCLE, -3, 3, 0);
  }

  @Test
  @DisplayName("가용 수량보다 많이 예약하면 거절하고 수량을 유지한다")
  void rejectsReservingMoreThanAvailable() {
    assertThatThrownBy(() -> reserve(11)).isInstanceOf(InsufficientInventoryException.class);
    assertQuantities(10, 0, 0);
  }

  @Test
  @DisplayName("0개 이하는 예약하지 않는다")
  void rejectsNonPositiveQuantity() {
    assertThatThrownBy(() -> reserve(0)).isInstanceOf(IllegalArgumentException.class);
    assertQuantities(10, 0, 0);
  }

  @Test
  @DisplayName("같은 회차의 결제 확정은 예약을 판매로 옮긴다")
  void consumeMovesReservedToSold() {
    InventoryReservation reservation = reserve(3).reservation();

    Result result = reservation.consume(inventory, CYCLE, UUID.randomUUID(), NOW);

    assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(reservation.getStatus()).isEqualTo(InventoryReservationStatus.CONSUMED);
    assertQuantities(7, 0, 3);
    assertMovement(result.movement(), InventoryOperation.ORDER_CONSUME, CYCLE, 0, -3, 3);
  }

  @Test
  @DisplayName("같은 회차의 확정 거절은 예약을 가용으로 되돌린다")
  void releaseMovesReservedToAvailable() {
    InventoryReservation reservation = reserve(3).reservation();

    Result result = reservation.release(inventory, CYCLE, UUID.randomUUID(), NOW);

    assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(reservation.getStatus()).isEqualTo(InventoryReservationStatus.RELEASED);
    assertQuantities(10, 0, 0);
    assertMovement(result.movement(), InventoryOperation.ORDER_RELEASE, CYCLE, 3, -3, 0);
  }

  @Test
  @DisplayName("다른 회차의 확정·해제 결과는 상태와 수량을 바꾸지 않는다")
  void ignoresResultsOfAnotherCycle() {
    InventoryReservation reservation = reserve(3).reservation();

    Result consumed = reservation.consume(inventory, NEXT_CYCLE, UUID.randomUUID(), NOW);
    Result released = reservation.release(inventory, NEXT_CYCLE, UUID.randomUUID(), NOW);

    assertThat(consumed.outcome()).isEqualTo(Outcome.STALE_CYCLE);
    assertThat(released.outcome()).isEqualTo(Outcome.STALE_CYCLE);
    assertThat(reservation.getStatus()).isEqualTo(InventoryReservationStatus.RESERVED);
    assertQuantities(7, 3, 0);
  }

  @Test
  @DisplayName("이미 반영한 확정·해제를 다시 받으면 수량을 바꾸지 않는다")
  void ignoresRepeatedResults() {
    InventoryReservation consumed = reserve(3).reservation();
    consumed.consume(inventory, CYCLE, UUID.randomUUID(), NOW);
    InventoryReservation released = reserve(2).reservation();
    released.release(inventory, CYCLE, UUID.randomUUID(), NOW);

    Result again = consumed.consume(inventory, CYCLE, UUID.randomUUID(), NOW);
    Result releasedAgain = released.release(inventory, CYCLE, UUID.randomUUID(), NOW);

    assertThat(again.outcome()).isEqualTo(Outcome.ALREADY_APPLIED);
    assertThat(again.movement()).isNull();
    assertThat(releasedAgain.outcome()).isEqualTo(Outcome.ALREADY_APPLIED);
    assertQuantities(7, 0, 3);
  }

  @Test
  @DisplayName("같은 회차에서 확정과 해제가 엇갈리면 거절하고 수량을 바꾸지 않는다")
  void rejectsContradictingResultsOfSameCycle() {
    InventoryReservation consumed = reserve(3).reservation();
    consumed.consume(inventory, CYCLE, UUID.randomUUID(), NOW);
    InventoryReservation released = reserve(2).reservation();
    released.release(inventory, CYCLE, UUID.randomUUID(), NOW);

    assertThatThrownBy(() -> consumed.release(inventory, CYCLE, UUID.randomUUID(), NOW))
        .isInstanceOf(InvalidReservationTransitionException.class);
    assertThatThrownBy(() -> released.consume(inventory, CYCLE, UUID.randomUUID(), NOW))
        .isInstanceOf(InvalidReservationTransitionException.class);
    assertThat(consumed.getStatus()).isEqualTo(InventoryReservationStatus.CONSUMED);
    assertThat(released.getStatus()).isEqualTo(InventoryReservationStatus.RELEASED);
    assertQuantities(7, 0, 3);
  }

  @Test
  @DisplayName("해제된 예약을 새 회차로 다시 확보하면 같은 행의 회차와 기한을 바꾼다")
  void reserveAgainReplacesCycleOnSameRow() {
    InventoryReservation reservation = reserve(3).reservation();
    reservation.release(inventory, CYCLE, UUID.randomUUID(), NOW);
    Instant nextExpires = EXPIRES.plusSeconds(600);

    Result result =
        reservation.reserveAgain(inventory, NEXT_CYCLE, nextExpires, UUID.randomUUID(), NOW);

    assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(reservation.getStatus()).isEqualTo(InventoryReservationStatus.RESERVED);
    assertThat(reservation.getCycleId()).isEqualTo(NEXT_CYCLE);
    assertThat(reservation.getExpiresAt()).isEqualTo(nextExpires);
    assertQuantities(7, 3, 0);
    assertMovement(result.movement(), InventoryOperation.ORDER_RESERVE, NEXT_CYCLE, -3, 3, 0);
  }

  @Test
  @DisplayName("재시도 뒤에는 이전 회차의 늦은 결과를 무시하고 새 회차 결과만 반영한다")
  void appliesOnlyNewCycleAfterRetry() {
    InventoryReservation reservation = reserve(3).reservation();
    reservation.release(inventory, CYCLE, UUID.randomUUID(), NOW);
    reservation.reserveAgain(inventory, NEXT_CYCLE, EXPIRES, UUID.randomUUID(), NOW);

    Result late = reservation.release(inventory, CYCLE, UUID.randomUUID(), NOW);
    Result current = reservation.consume(inventory, NEXT_CYCLE, UUID.randomUUID(), NOW);

    assertThat(late.outcome()).isEqualTo(Outcome.STALE_CYCLE);
    assertThat(current.outcome()).isEqualTo(Outcome.APPLIED);
    assertQuantities(7, 0, 3);
  }

  @Test
  @DisplayName("재시도 때 가용 수량이 부족하면 거절하고 해제 상태를 유지한다")
  void keepsReleasedWhenRetryCannotReserve() {
    InventoryReservation reservation = reserve(3).reservation();
    reservation.release(inventory, CYCLE, UUID.randomUUID(), NOW);
    reserve(9);

    assertThatThrownBy(
            () -> reservation.reserveAgain(inventory, NEXT_CYCLE, EXPIRES, UUID.randomUUID(), NOW))
        .isInstanceOf(InsufficientInventoryException.class);
    assertThat(reservation.getStatus()).isEqualTo(InventoryReservationStatus.RELEASED);
    assertThat(reservation.getCycleId()).isEqualTo(CYCLE);
    assertQuantities(1, 9, 0);
  }

  @Test
  @DisplayName("같은 회차로 재시도를 다시 받으면 수량을 바꾸지 않는다")
  void ignoresRepeatedRetry() {
    InventoryReservation reservation = reserve(3).reservation();
    reservation.release(inventory, CYCLE, UUID.randomUUID(), NOW);
    reservation.reserveAgain(inventory, NEXT_CYCLE, EXPIRES, UUID.randomUUID(), NOW);

    Result again = reservation.reserveAgain(inventory, NEXT_CYCLE, EXPIRES, UUID.randomUUID(), NOW);

    assertThat(again.outcome()).isEqualTo(Outcome.ALREADY_APPLIED);
    assertQuantities(7, 3, 0);
  }

  @Test
  @DisplayName("해제되지 않은 예약이나 해제한 회차를 다시 쓰는 재시도는 거절한다")
  void rejectsRetryOfUnreleasedReservationOrReleasedCycle() {
    InventoryReservation reserved = reserve(3).reservation();
    InventoryReservation released = reserve(2).reservation();
    released.release(inventory, CYCLE, UUID.randomUUID(), NOW);

    assertThatThrownBy(
            () -> reserved.reserveAgain(inventory, NEXT_CYCLE, EXPIRES, UUID.randomUUID(), NOW))
        .isInstanceOf(InvalidReservationTransitionException.class);
    assertThatThrownBy(
            () -> released.reserveAgain(inventory, CYCLE, EXPIRES, UUID.randomUUID(), NOW))
        .isInstanceOf(InvalidReservationTransitionException.class);
    assertQuantities(7, 3, 0);
  }

  @ParameterizedTest
  @EnumSource(
      value = InventoryOperation.class,
      names = {"REFUND_RESTORE", "RETURN_RESTORE"})
  @DisplayName("확정된 예약의 환불 복구·반품 입고는 판매를 가용으로 되돌린다")
  void restoreMovesSoldToAvailable(InventoryOperation operation) {
    InventoryReservation reservation = consumed(3);
    UUID businessRef = UUID.randomUUID();

    Result result = reservation.restore(inventory, operation, businessRef, UUID.randomUUID(), NOW);

    assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(reservation.getStatus()).isEqualTo(InventoryReservationStatus.RESTORED);
    assertQuantities(10, 0, 0);
    assertMovement(result.movement(), operation, businessRef, 3, 0, -3);
  }

  @Test
  @DisplayName("복원은 주문당 한 번이며 다른 출처의 복원 요청도 수량을 바꾸지 않는다")
  void restoresOnlyOnce() {
    InventoryReservation reservation = consumed(3);
    reservation.restore(
        inventory, InventoryOperation.RETURN_RESTORE, UUID.randomUUID(), UUID.randomUUID(), NOW);

    Result again =
        reservation.restore(
            inventory,
            InventoryOperation.REFUND_RESTORE,
            UUID.randomUUID(),
            UUID.randomUUID(),
            NOW);

    assertThat(again.outcome()).isEqualTo(Outcome.ALREADY_APPLIED);
    assertQuantities(10, 0, 0);
  }

  @Test
  @DisplayName("결제 확정 전의 예약은 복원하지 않는다")
  void rejectsRestoringUnconsumedReservation() {
    InventoryReservation reservation = reserve(3).reservation();

    assertThatThrownBy(
            () ->
                reservation.restore(
                    inventory,
                    InventoryOperation.REFUND_RESTORE,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    NOW))
        .isInstanceOf(InvalidReservationTransitionException.class);
    assertQuantities(7, 3, 0);
  }

  @Test
  @DisplayName("복원 외의 작업 종류로는 복원하지 않는다")
  void rejectsNonRestoreOperation() {
    InventoryReservation reservation = consumed(3);

    assertThatThrownBy(
            () ->
                reservation.restore(
                    inventory,
                    InventoryOperation.ORDER_RELEASE,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(reservation.getStatus()).isEqualTo(InventoryReservationStatus.CONSUMED);
    assertQuantities(7, 0, 3);
  }

  @Test
  @DisplayName("다른 상품의 재고로는 예약을 바꾸지 않는다")
  void rejectsInventoryOfAnotherProduct() {
    InventoryReservation reservation = reserve(3).reservation();
    ProductInventory other = ProductInventory.empty(UUID.randomUUID(), UUID.randomUUID(), NOW);

    assertThatThrownBy(() -> reservation.consume(other, CYCLE, UUID.randomUUID(), NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(reservation.getStatus()).isEqualTo(InventoryReservationStatus.RESERVED);
  }

  private InventoryReservation.Created reserve(int quantity) {
    return InventoryReservation.reserve(
        UUID.randomUUID(),
        UUID.randomUUID(),
        inventory,
        quantity,
        CYCLE,
        EXPIRES,
        UUID.randomUUID(),
        NOW);
  }

  private InventoryReservation consumed(int quantity) {
    InventoryReservation reservation = reserve(quantity).reservation();
    reservation.consume(inventory, CYCLE, UUID.randomUUID(), NOW);
    return reservation;
  }

  private void assertQuantities(long available, long reserved, long sold) {
    assertThat(inventory.getAvailableQuantity()).isEqualTo(available);
    assertThat(inventory.getReservedQuantity()).isEqualTo(reserved);
    assertThat(inventory.getSoldQuantity()).isEqualTo(sold);
  }

  private static void assertMovement(
      InventoryMovement movement,
      InventoryOperation operation,
      UUID businessRef,
      long available,
      long reserved,
      long sold) {
    assertThat(movement.getProductId()).isEqualTo(PRODUCT);
    assertThat(movement.getOperation()).isEqualTo(operation);
    assertThat(movement.getBusinessRef()).isEqualTo(businessRef);
    assertThat(movement.getAvailableDelta()).isEqualTo(available);
    assertThat(movement.getReservedDelta()).isEqualTo(reserved);
    assertThat(movement.getAllocatedDelta()).isZero();
    assertThat(movement.getSoldDelta()).isEqualTo(sold);
  }

  private static ProductInventory stocked(long quantity) {
    ProductInventory inventory = ProductInventory.empty(UUID.randomUUID(), PRODUCT, NOW);
    inventory.adjustAvailable(UUID.randomUUID(), UUID.randomUUID(), quantity, "입고", 0, NOW);
    return inventory;
  }
}
