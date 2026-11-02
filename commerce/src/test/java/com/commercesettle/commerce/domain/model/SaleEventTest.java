package com.commercesettle.commerce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.domain.exception.InsufficientInventoryException;
import com.commercesettle.commerce.domain.exception.SaleEventConflictException;
import com.commercesettle.commerce.domain.exception.SaleEventConflictException.Reason;
import com.commercesettle.commerce.domain.exception.VersionConflictException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SaleEventTest {
  private static final Instant NOW = Instant.parse("2026-11-02T01:00:00Z");
  private static final Instant STARTS = NOW.plus(Duration.ofHours(1));
  private static final Instant ENDS = NOW.plus(Duration.ofDays(1));
  private static final UUID SELLER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

  @Test
  @DisplayName("등록한 이벤트는 미공개이며 판매·예약 수량이 0이다")
  void registersDraft() {
    SaleEvent event = draft(10);

    assertThat(event.getStatus()).isEqualTo(SaleEventStatus.DRAFT);
    assertThat(event.getSoldCount()).isZero();
    assertThat(event.getReservedCount()).isZero();
    assertThat(event.getPublishedAt()).isNull();
    assertThat(event.isPublished()).isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    "'', 10000, 10, 0",
    "이름, 0, 10, 0",
    "이름, 10000001, 10, 0",
    "이름, 10000, 0, 0",
    "이름, 10000, 10000001, 0",
    "이름, 10000, 10, 1"
  })
  @DisplayName("이름·가격·수량 범위를 벗어나거나 시작이 종료보다 늦지 않으면 거절한다")
  void rejectsInvalidValues(String name, long price, long quota, int sameStartAndEnd) {
    Instant ends = sameStartAndEnd == 1 ? STARTS : ENDS;

    assertThatThrownBy(
            () ->
                SaleEvent.register(
                    UUID.randomUUID(), SELLER, PRODUCT, name, price, quota, STARTS, ends, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("이름이 81자이면 거절한다")
  void rejectsLongName() {
    assertThatThrownBy(
            () ->
                SaleEvent.register(
                    UUID.randomUUID(),
                    SELLER,
                    PRODUCT,
                    "가".repeat(81),
                    10_000,
                    10,
                    STARTS,
                    ENDS,
                    NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("미공개 이벤트는 버전을 확인하고 전체 내용을 바꾼다")
  void changesDraft() {
    SaleEvent event = draft(10);
    UUID otherProduct = UUID.randomUUID();

    event.change(otherProduct, "주말 특가", 9_000, 20, STARTS, ENDS, 0, NOW.plusSeconds(1));

    assertThat(event.getProductId()).isEqualTo(otherProduct);
    assertThat(event.getQuota()).isEqualTo(20);
    assertThat(event.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
    assertThatThrownBy(() -> event.change(PRODUCT, "주말 특가", 9_000, 20, STARTS, ENDS, 7, NOW))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  @DisplayName("시작 전 공개는 SCHEDULED가 되고 한정 수량을 가용에서 이벤트 배정으로 옮긴다")
  void publishesBeforeStartAndAllocates() {
    SaleEvent event = draft(10);
    ProductInventory inventory = stocked(25);
    UUID movementId = UUID.randomUUID();

    InventoryMovement movement = event.publish(inventory, 0, movementId, NOW);

    assertThat(event.getStatus()).isEqualTo(SaleEventStatus.SCHEDULED);
    assertThat(event.getPublishedAt()).isEqualTo(NOW);
    assertThat(inventory.getAvailableQuantity()).isEqualTo(15);
    assertThat(inventory.getEventAllocatedQuantity()).isEqualTo(10);
    assertThat(movement.getId()).isEqualTo(movementId);
    assertThat(movement.getOperation()).isEqualTo(InventoryOperation.EVENT_ALLOCATE);
    assertThat(movement.getBusinessRef()).isEqualTo(event.getId());
    assertThat(movement.getAvailableDelta()).isEqualTo(-10);
    assertThat(movement.getAllocatedDelta()).isEqualTo(10);
    assertThat(movement.getReservedDelta()).isZero();
    assertThat(movement.getSoldDelta()).isZero();
  }

  @Test
  @DisplayName("시작 시각이 지난 뒤 공개하면 바로 OPEN이다")
  void publishesAfterStartAsOpen() {
    SaleEvent event = draft(10);

    event.publish(stocked(10), 0, UUID.randomUUID(), STARTS);

    assertThat(event.getStatus()).isEqualTo(SaleEventStatus.OPEN);
  }

  @Test
  @DisplayName("가용 수량이 한정 수량보다 적으면 공개하지 않고 재고·상태를 유지한다")
  void rejectsPublishingBeyondAvailable() {
    SaleEvent event = draft(10);
    ProductInventory inventory = stocked(9);

    assertThatThrownBy(() -> event.publish(inventory, 0, UUID.randomUUID(), NOW))
        .isInstanceOf(InsufficientInventoryException.class);
    assertThat(event.getStatus()).isEqualTo(SaleEventStatus.DRAFT);
    assertThat(inventory.getAvailableQuantity()).isEqualTo(9);
    assertThat(inventory.getEventAllocatedQuantity()).isZero();
  }

  @Test
  @DisplayName("종료 시각이 지났거나 버전이 다르거나 이미 공개했으면 공개하지 않는다")
  void rejectsInvalidPublication() {
    ProductInventory inventory = stocked(30);

    assertThatThrownBy(() -> draft(10).publish(inventory, 0, UUID.randomUUID(), ENDS))
        .isInstanceOf(SaleEventConflictException.class)
        .extracting(e -> ((SaleEventConflictException) e).reason())
        .isEqualTo(Reason.PERIOD_ENDED);
    assertThatThrownBy(() -> draft(10).publish(inventory, 3, UUID.randomUUID(), NOW))
        .isInstanceOf(VersionConflictException.class);
    SaleEvent published = draft(10);
    published.publish(inventory, 0, UUID.randomUUID(), NOW);
    assertThatThrownBy(() -> published.publish(inventory, 0, UUID.randomUUID(), NOW))
        .extracting(e -> ((SaleEventConflictException) e).reason())
        .isEqualTo(Reason.NOT_DRAFT);
    assertThatThrownBy(() -> published.change(PRODUCT, "변경", 9_000, 10, STARTS, ENDS, 0, NOW))
        .extracting(e -> ((SaleEventConflictException) e).reason())
        .isEqualTo(Reason.NOT_DRAFT);
    assertThat(inventory.getEventAllocatedQuantity()).isEqualTo(10);
  }

  @Test
  @DisplayName("다른 상품의 재고로는 공개하지 않는다")
  void rejectsInventoryOfAnotherProduct() {
    ProductInventory other = ProductInventory.empty(UUID.randomUUID(), UUID.randomUUID(), NOW);

    assertThatThrownBy(() -> draft(1).publish(other, 0, UUID.randomUUID(), NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("시작 시각이 되면 예정 이벤트를 열되 updatedAt은 그대로 두고, 시작 전이나 다른 상태는 바꾸지 않는다")
  void opensWhenStarted() {
    SaleEvent event = published(10);
    Instant updatedAt = event.getUpdatedAt();

    assertThat(event.openIfStarted(STARTS.minusNanos(1000))).isFalse();
    assertThat(event.getStatus()).isEqualTo(SaleEventStatus.SCHEDULED);
    assertThat(event.openIfStarted(STARTS)).isTrue();
    assertThat(event.getStatus()).isEqualTo(SaleEventStatus.OPEN);
    assertThat(event.getUpdatedAt()).isEqualTo(updatedAt);
    assertThat(event.openIfStarted(STARTS)).isFalse();
    assertThat(draft(1).openIfStarted(STARTS)).isFalse();
  }

  @Test
  @DisplayName("조기 종료는 사유와 함께 남은 배정분을 가용으로 돌리고 CLOSED가 된다")
  void closesEarlyAndReturnsUnusedAllocation() {
    SaleEvent event = draft(10);
    ProductInventory inventory = stocked(25);
    event.publish(inventory, 0, UUID.randomUUID(), NOW);

    InventoryMovement movement =
        event.close(inventory, 0, "재고 오류", UUID.randomUUID(), NOW.plusSeconds(60));

    assertThat(event.getStatus()).isEqualTo(SaleEventStatus.CLOSED);
    assertThat(event.getClosedAt()).isEqualTo(NOW.plusSeconds(60));
    assertThat(inventory.getAvailableQuantity()).isEqualTo(25);
    assertThat(inventory.getEventAllocatedQuantity()).isZero();
    assertThat(movement.getOperation()).isEqualTo(InventoryOperation.EVENT_RETURN);
    assertThat(movement.getBusinessRef()).isEqualTo(event.getId());
    assertThat(movement.getAvailableDelta()).isEqualTo(10);
    assertThat(movement.getAllocatedDelta()).isEqualTo(-10);
    assertThat(movement.getReason()).isEqualTo("재고 오류");
  }

  @Test
  @DisplayName("미공개·종료 이벤트는 조기 종료할 수 없고 버전이 다르면 종료하지 않는다")
  void rejectsInvalidClosure() {
    ProductInventory inventory = stocked(10);
    SaleEvent event = draft(10);

    assertThatThrownBy(() -> event.close(inventory, 0, "사유", UUID.randomUUID(), NOW))
        .extracting(e -> ((SaleEventConflictException) e).reason())
        .isEqualTo(Reason.NOT_IN_PROGRESS);
    event.publish(inventory, 0, UUID.randomUUID(), NOW);
    assertThatThrownBy(() -> event.close(inventory, 5, "사유", UUID.randomUUID(), NOW))
        .isInstanceOf(VersionConflictException.class);
    event.close(inventory, 0, "사유", UUID.randomUUID(), NOW);
    assertThatThrownBy(() -> event.close(inventory, 0, "사유", UUID.randomUUID(), NOW))
        .extracting(e -> ((SaleEventConflictException) e).reason())
        .isEqualTo(Reason.NOT_IN_PROGRESS);
    assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
  }

  @Test
  @DisplayName("기간 종료는 종료 시각부터 한 번만 닫고 기간 종료 사유를 남긴다")
  void closesOnceWhenPeriodEnds() {
    SaleEvent event = draft(10);
    ProductInventory inventory = stocked(10);
    event.publish(inventory, 0, UUID.randomUUID(), NOW);

    assertThat(event.closeIfEnded(inventory, UUID.randomUUID(), ENDS.minusNanos(1000))).isEmpty();
    InventoryMovement movement =
        event.closeIfEnded(inventory, UUID.randomUUID(), ENDS).orElseThrow();
    assertThat(event.closeIfEnded(inventory, UUID.randomUUID(), ENDS)).isEmpty();

    assertThat(event.getStatus()).isEqualTo(SaleEventStatus.CLOSED);
    assertThat(movement.getReason()).isEqualTo(SaleEvent.PERIOD_END_REASON);
    assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
    assertThat(inventory.getEventAllocatedQuantity()).isZero();
  }

  @ParameterizedTest
  @CsvSource({
    "SCHEDULED, 0, 0, -1, NOT_STARTED, 0",
    "SCHEDULED, 0, 0, 1, OPEN, 0",
    "OPEN, 700, 299, 1, OPEN, 70",
    "OPEN, 700, 300, 1, WAITING_PAYMENTS, 70",
    "OPEN, 999, 0, 1, OPEN, 99",
    "OPEN, 1000, 0, 1, SOLD_OUT, 100",
    "SOLD_OUT, 1000, 0, 1, SOLD_OUT, 100",
    "OPEN, 700, 300, 2, CLOSED, 70",
    "CLOSED, 10, 0, 1, CLOSED, 1"
  })
  @DisplayName("게이지는 확정 판매량 기준 내림 비율이며 예약만으로 매진을 표시하지 않는다")
  void computesGauge(
      SaleEventStatus status,
      long sold,
      long reserved,
      int moment,
      EventAdmission admission,
      int percent) {
    Instant now =
        switch (moment) {
          case -1 -> STARTS.minusSeconds(1);
          case 1 -> STARTS;
          default -> ENDS;
        };

    SaleEvent.Gauge gauge = SaleEvent.gauge(status, STARTS, ENDS, 1000, sold, reserved, now);

    assertThat(gauge.admission()).isEqualTo(admission);
    assertThat(gauge.percent()).isEqualTo(percent);
    assertThat(gauge.remainingQuantity()).isEqualTo(1000 - sold - reserved);
  }

  private static SaleEvent draft(long quota) {
    return SaleEvent.register(
        UUID.randomUUID(), SELLER, PRODUCT, "오픈 기념 한정 판매", 10_000, quota, STARTS, ENDS, NOW);
  }

  private static SaleEvent published(long quota) {
    SaleEvent event = draft(quota);
    event.publish(stocked(quota), 0, UUID.randomUUID(), NOW);
    return event;
  }

  private static ProductInventory stocked(long quantity) {
    ProductInventory inventory = ProductInventory.empty(UUID.randomUUID(), PRODUCT, NOW);
    inventory.adjustAvailable(UUID.randomUUID(), UUID.randomUUID(), quantity, "입고", 0, NOW);
    return inventory;
  }
}
