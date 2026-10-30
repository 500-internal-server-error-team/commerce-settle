package com.commercesettle.commerce.domain.model;

import com.commercesettle.commerce.domain.exception.InsufficientInventoryException;
import com.commercesettle.commerce.domain.exception.VersionConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 상품별 재고. 판매자 조정은 일반 가용 수량만 바꾸며 예약·이벤트 배정·판매 수량은 주문·이벤트 흐름이 소유한다. 모든 수량 변경은 같은 트랜잭션에서 {@link
 * InventoryMovement}로 기록한다.
 */
@Entity
@Table(name = "product_inventory")
public class ProductInventory extends AuditableEntity {
  public static final long MAX_ADJUSTMENT = 10_000_000;

  @Id private UUID id;
  private UUID productId;

  @Column(name = "available_qty")
  private long availableQuantity;

  @Column(name = "reserved_qty")
  private long reservedQuantity;

  @Column(name = "event_allocated_qty")
  private long eventAllocatedQuantity;

  @Column(name = "sold_qty")
  private long soldQuantity;

  @Version private Long version;

  protected ProductInventory() {}

  private ProductInventory(UUID id, UUID productId, Instant now) {
    super(now);
    this.id = Objects.requireNonNull(id, "id");
    this.productId = Objects.requireNonNull(productId, "productId");
  }

  /** 상품 등록과 함께 모든 수량이 0인 재고를 만든다. */
  public static ProductInventory empty(UUID id, UUID productId, Instant now) {
    return new ProductInventory(id, productId, now);
  }

  public InventoryMovement adjustAvailable(
      UUID movementId,
      UUID businessRef,
      long delta,
      String reason,
      long expectedVersion,
      Instant now) {
    if (delta == 0 || Math.abs(delta) > MAX_ADJUSTMENT) {
      throw new IllegalArgumentException("delta must be non-zero within " + MAX_ADJUSTMENT);
    }
    if (expectedVersion != getVersion()) {
      throw new VersionConflictException("inventory", expectedVersion, getVersion());
    }
    if (availableQuantity + delta < 0) {
      throw new InsufficientInventoryException(productId);
    }
    return change(
        movementId, InventoryOperation.SELLER_ADJUSTMENT, businessRef, delta, 0, 0, reason, now);
  }

  /** 주문 수량을 가용에서 예약으로 옮긴다. 예약 상태 전이와 함께 {@link InventoryReservation}이 호출한다. */
  InventoryMovement reserve(UUID movementId, UUID cycleId, int quantity, Instant now) {
    if (availableQuantity < quantity) {
      throw new InsufficientInventoryException(productId);
    }
    return change(
        movementId,
        InventoryOperation.ORDER_RESERVE,
        cycleId,
        -quantity,
        quantity,
        0,
        "주문 예약",
        now);
  }

  InventoryMovement consume(UUID movementId, UUID cycleId, int quantity, Instant now) {
    requireReserved(quantity);
    return change(
        movementId,
        InventoryOperation.ORDER_CONSUME,
        cycleId,
        0,
        -quantity,
        quantity,
        "결제 확정",
        now);
  }

  InventoryMovement release(UUID movementId, UUID cycleId, int quantity, Instant now) {
    requireReserved(quantity);
    return change(
        movementId,
        InventoryOperation.ORDER_RELEASE,
        cycleId,
        quantity,
        -quantity,
        0,
        "예약 해제",
        now);
  }

  /** 순판매 수량을 가용으로 되돌린다. 환불 복구와 반품 입고는 업무 참조가 달라 서로 다른 이력 작업으로 남긴다. */
  InventoryMovement restore(
      UUID movementId, InventoryOperation operation, UUID businessRef, int quantity, Instant now) {
    if (operation != InventoryOperation.REFUND_RESTORE
        && operation != InventoryOperation.RETURN_RESTORE) {
      throw new IllegalArgumentException("not a restore operation: " + operation);
    }
    if (soldQuantity < quantity) {
      throw new IllegalStateException("sold quantity is less than restore: " + productId);
    }
    String reason = operation == InventoryOperation.REFUND_RESTORE ? "환불 복구" : "반품 입고";
    return change(movementId, operation, businessRef, quantity, 0, -quantity, reason, now);
  }

  private void requireReserved(int quantity) {
    if (reservedQuantity < quantity) {
      throw new IllegalStateException("reserved quantity is less than requested: " + productId);
    }
  }

  private InventoryMovement change(
      UUID movementId,
      InventoryOperation operation,
      UUID businessRef,
      long availableDelta,
      long reservedDelta,
      long soldDelta,
      String reason,
      Instant now) {
    InventoryMovement movement =
        InventoryMovement.of(
            movementId,
            productId,
            operation,
            businessRef,
            availableDelta,
            reservedDelta,
            soldDelta,
            reason,
            now);
    availableQuantity += availableDelta;
    reservedQuantity += reservedDelta;
    soldQuantity += soldDelta;
    touch(now);
    return movement;
  }

  public UUID getId() {
    return id;
  }

  public UUID getProductId() {
    return productId;
  }

  public long getAvailableQuantity() {
    return availableQuantity;
  }

  public long getReservedQuantity() {
    return reservedQuantity;
  }

  public long getEventAllocatedQuantity() {
    return eventAllocatedQuantity;
  }

  public long getSoldQuantity() {
    return soldQuantity;
  }

  public long getVersion() {
    return version == null ? 0 : version;
  }
}
