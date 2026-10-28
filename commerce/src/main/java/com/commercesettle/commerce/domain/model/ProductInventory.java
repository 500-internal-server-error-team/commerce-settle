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
    InventoryMovement movement =
        InventoryMovement.sellerAdjustment(movementId, productId, businessRef, delta, reason, now);
    availableQuantity += delta;
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
