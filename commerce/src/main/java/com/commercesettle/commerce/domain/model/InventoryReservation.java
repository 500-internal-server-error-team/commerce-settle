package com.commercesettle.commerce.domain.model;

import com.commercesettle.commerce.domain.exception.InvalidReservationTransitionException;
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
 * 주문 재고 예약. 주문당 한 행이며 재시도도 같은 행을 갱신한다. 상태 전이와 {@link ProductInventory} 수량 변경을 한 메서드에서 함께 수행해 둘이
 * 어긋나지 않게 한다. 확정·해제는 결과의 결제 회차가 현재 회차와 같을 때만 반영하고, 이전 회차의 늦은 결과와 이미 반영된 결과는 수량을 바꾸지 않는다.
 */
@Entity
@Table(name = "inventory_reservations")
public class InventoryReservation extends AuditableEntity {
  /** 수량을 바꿨는지, 이미 반영된 요청인지, 이전 결제 회차의 결과라 무시했는지. */
  public enum Outcome {
    APPLIED,
    ALREADY_APPLIED,
    STALE_CYCLE
  }

  /** 상태 전이 결과. movement는 수량을 바꾼 APPLIED에만 있다. */
  public record Result(Outcome outcome, InventoryMovement movement) {
    static Result applied(InventoryMovement movement) {
      return new Result(Outcome.APPLIED, Objects.requireNonNull(movement, "movement"));
    }

    static Result unchanged(Outcome outcome) {
      return new Result(outcome, null);
    }
  }

  /** 새 예약과 그 수량 변경 이력. */
  public record Created(InventoryReservation reservation, InventoryMovement movement) {}

  @Id private UUID id;
  private UUID orderId;
  private UUID productId;
  private UUID eventId;
  private int quantity;
  private UUID cycleId;

  @Enumerated(EnumType.STRING)
  private InventoryReservationStatus status;

  private Instant expiresAt;

  @Version private Long version;

  protected InventoryReservation() {}

  private InventoryReservation(
      UUID id,
      UUID orderId,
      UUID productId,
      int quantity,
      UUID cycleId,
      Instant expiresAt,
      Instant now) {
    super(now);
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be positive");
    }
    this.id = Objects.requireNonNull(id, "id");
    this.orderId = Objects.requireNonNull(orderId, "orderId");
    this.productId = Objects.requireNonNull(productId, "productId");
    this.quantity = quantity;
    this.cycleId = Objects.requireNonNull(cycleId, "cycleId");
    this.expiresAt = truncate(expiresAt);
    this.status = InventoryReservationStatus.RESERVED;
  }

  /** 일반 주문 생성: 가용 −q, 예약 +q. 가용 수량이 부족하면 예약을 만들지 않는다. */
  public static Created reserve(
      UUID id,
      UUID orderId,
      ProductInventory inventory,
      int quantity,
      UUID cycleId,
      Instant expiresAt,
      UUID movementId,
      Instant now) {
    InventoryReservation reservation =
        new InventoryReservation(
            id, orderId, inventory.getProductId(), quantity, cycleId, expiresAt, now);
    InventoryMovement movement = inventory.reserve(movementId, cycleId, quantity, now);
    return new Created(reservation, movement);
  }

  /** 결제 확정: 예약 −q, 판매 +q. */
  public Result consume(
      ProductInventory inventory, UUID resultCycleId, UUID movementId, Instant now) {
    requireSameProduct(inventory);
    if (!cycleId.equals(resultCycleId)) {
      return Result.unchanged(Outcome.STALE_CYCLE);
    }
    return switch (status) {
      case RESERVED -> {
        InventoryMovement movement = inventory.consume(movementId, cycleId, quantity, now);
        moveTo(InventoryReservationStatus.CONSUMED, now);
        yield Result.applied(movement);
      }
      case CONSUMED, RESTORED -> Result.unchanged(Outcome.ALREADY_APPLIED);
      case RELEASED -> throw rejected(InventoryReservationStatus.CONSUMED);
    };
  }

  /** 확정 거절 또는 승인을 차단한 취소: 예약 −q, 가용 +q. */
  public Result release(
      ProductInventory inventory, UUID resultCycleId, UUID movementId, Instant now) {
    requireSameProduct(inventory);
    if (!cycleId.equals(resultCycleId)) {
      return Result.unchanged(Outcome.STALE_CYCLE);
    }
    return switch (status) {
      case RESERVED -> {
        InventoryMovement movement = inventory.release(movementId, cycleId, quantity, now);
        moveTo(InventoryReservationStatus.RELEASED, now);
        yield Result.applied(movement);
      }
      case RELEASED -> Result.unchanged(Outcome.ALREADY_APPLIED);
      case CONSUMED, RESTORED -> throw rejected(InventoryReservationStatus.RELEASED);
    };
  }

  /**
   * 확정 거절 뒤 재시도: 해제된 예약을 새 결제 회차로 다시 확보한다. 가용 수량이 부족하면 상태를 바꾸지 않고 거절한다. 해제된 회차를 다시 쓰는 요청은 금지된 전이다.
   */
  public Result reserveAgain(
      ProductInventory inventory,
      UUID newCycleId,
      Instant newExpiresAt,
      UUID movementId,
      Instant now) {
    requireSameProduct(inventory);
    Objects.requireNonNull(newCycleId, "newCycleId");
    if (cycleId.equals(newCycleId) && status != InventoryReservationStatus.RELEASED) {
      return Result.unchanged(Outcome.ALREADY_APPLIED);
    }
    if (status != InventoryReservationStatus.RELEASED || cycleId.equals(newCycleId)) {
      throw rejected(InventoryReservationStatus.RESERVED);
    }
    InventoryMovement movement = inventory.reserve(movementId, newCycleId, quantity, now);
    cycleId = newCycleId;
    expiresAt = truncate(newExpiresAt);
    moveTo(InventoryReservationStatus.RESERVED, now);
    return Result.applied(movement);
  }

  /**
   * 미발송 주문의 환불 복구(REFUND_RESTORE, 환불 ID) 또는 발송 주문의 반품 입고(RETURN_RESTORE, 반품 요청 ID): 판매 −q, 가용 +q.
   * 주문당 한 번만 복원하며 이후 복원 요청은 출처와 관계없이 수량을 바꾸지 않는다.
   */
  public Result restore(
      ProductInventory inventory,
      InventoryOperation operation,
      UUID businessRef,
      UUID movementId,
      Instant now) {
    requireSameProduct(inventory);
    return switch (status) {
      case CONSUMED -> {
        InventoryMovement movement =
            inventory.restore(movementId, operation, businessRef, quantity, now);
        moveTo(InventoryReservationStatus.RESTORED, now);
        yield Result.applied(movement);
      }
      case RESTORED -> Result.unchanged(Outcome.ALREADY_APPLIED);
      case RESERVED, RELEASED -> throw rejected(InventoryReservationStatus.RESTORED);
    };
  }

  /** 주문 생성 재처리가 처음 예약과 같은 요청인지 확인한다. */
  public boolean isSameReservation(UUID productId, int quantity, UUID cycleId) {
    return this.productId.equals(productId)
        && this.quantity == quantity
        && this.cycleId.equals(cycleId);
  }

  private void requireSameProduct(ProductInventory inventory) {
    if (!productId.equals(inventory.getProductId())) {
      throw new IllegalArgumentException(
          "inventory of another product: " + inventory.getProductId());
    }
  }

  private void moveTo(InventoryReservationStatus next, Instant now) {
    status = next;
    touch(now);
  }

  private InvalidReservationTransitionException rejected(InventoryReservationStatus target) {
    return new InvalidReservationTransitionException(orderId, status, target);
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public UUID getProductId() {
    return productId;
  }

  public UUID getEventId() {
    return eventId;
  }

  public int getQuantity() {
    return quantity;
  }

  public UUID getCycleId() {
    return cycleId;
  }

  public InventoryReservationStatus getStatus() {
    return status;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public long getVersion() {
    return version == null ? 0 : version;
  }
}
