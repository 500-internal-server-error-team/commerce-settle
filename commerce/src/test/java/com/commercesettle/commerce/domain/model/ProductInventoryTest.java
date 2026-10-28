package com.commercesettle.commerce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.domain.exception.InsufficientInventoryException;
import com.commercesettle.commerce.domain.exception.VersionConflictException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductInventoryTest {
  private static final Instant NOW = Instant.parse("2026-10-28T01:00:00Z");
  private static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

  @Test
  @DisplayName("새 재고는 모든 수량이 0이다")
  void startsEmpty() {
    ProductInventory inventory = ProductInventory.empty(UUID.randomUUID(), PRODUCT, NOW);

    assertThat(inventory.getAvailableQuantity()).isZero();
    assertThat(inventory.getReservedQuantity()).isZero();
    assertThat(inventory.getEventAllocatedQuantity()).isZero();
    assertThat(inventory.getSoldQuantity()).isZero();
  }

  @Test
  @DisplayName("조정은 가용 수량만 바꾸고 같은 증감의 이력을 만든다")
  void adjustsOnlyAvailableQuantityWithMovement() {
    ProductInventory inventory = ProductInventory.empty(UUID.randomUUID(), PRODUCT, NOW);
    UUID businessRef = UUID.randomUUID();

    InventoryMovement movement =
        inventory.adjustAvailable(UUID.randomUUID(), businessRef, 5, "입고", 0, NOW);

    assertThat(inventory.getAvailableQuantity()).isEqualTo(5);
    assertThat(inventory.getReservedQuantity()).isZero();
    assertThat(movement.getProductId()).isEqualTo(PRODUCT);
    assertThat(movement.getOperation()).isEqualTo(InventoryOperation.SELLER_ADJUSTMENT);
    assertThat(movement.getBusinessRef()).isEqualTo(businessRef);
    assertThat(movement.getAvailableDelta()).isEqualTo(5);
    assertThat(movement.getReservedDelta()).isZero();
    assertThat(movement.getAllocatedDelta()).isZero();
    assertThat(movement.getSoldDelta()).isZero();
  }

  @Test
  @DisplayName("가용 수량을 정확히 0까지는 줄일 수 있다")
  void allowsReducingToZero() {
    ProductInventory inventory = stocked(3);

    inventory.adjustAvailable(UUID.randomUUID(), UUID.randomUUID(), -3, "폐기", 0, NOW);

    assertThat(inventory.getAvailableQuantity()).isZero();
  }

  @Test
  @DisplayName("가용 수량보다 많이 줄이면 거절하고 수량을 유지한다")
  void rejectsReducingBelowZero() {
    ProductInventory inventory = stocked(3);

    assertThatThrownBy(
            () -> inventory.adjustAvailable(UUID.randomUUID(), UUID.randomUUID(), -4, "폐기", 0, NOW))
        .isInstanceOf(InsufficientInventoryException.class);
    assertThat(inventory.getAvailableQuantity()).isEqualTo(3);
  }

  @Test
  @DisplayName("기대 버전이 다르면 조정하지 않는다")
  void rejectsStaleVersion() {
    ProductInventory inventory = stocked(3);

    assertThatThrownBy(
            () -> inventory.adjustAvailable(UUID.randomUUID(), UUID.randomUUID(), 1, "입고", 7, NOW))
        .isInstanceOf(VersionConflictException.class);
    assertThat(inventory.getAvailableQuantity()).isEqualTo(3);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 10_000_001, -10_000_001})
  @DisplayName("0이거나 한도를 넘는 증감은 거절한다")
  void rejectsZeroOrOutOfRangeDelta(long delta) {
    ProductInventory inventory = ProductInventory.empty(UUID.randomUUID(), PRODUCT, NOW);

    assertThatThrownBy(
            () ->
                inventory.adjustAvailable(
                    UUID.randomUUID(), UUID.randomUUID(), delta, "입고", 0, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ProductInventory stocked(long quantity) {
    ProductInventory inventory = ProductInventory.empty(UUID.randomUUID(), PRODUCT, NOW);
    inventory.adjustAvailable(UUID.randomUUID(), UUID.randomUUID(), quantity, "입고", 0, NOW);
    return inventory;
  }
}
