package com.commercesettle.commerce.domain.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** 추가 전용 재고 변경 이력. 상품·작업·업무 참조가 같으면 한 번만 기록된다. */
@Entity
@Table(name = "inventory_movements")
public class InventoryMovement {
  public static final int MAX_REASON_LENGTH = 200;

  @Id private UUID id;
  private UUID productId;

  @Enumerated(EnumType.STRING)
  private InventoryOperation operation;

  private UUID businessRef;
  private long availableDelta;
  private long reservedDelta;
  private long allocatedDelta;
  private long soldDelta;
  private String reason;
  private Instant createdAt;

  protected InventoryMovement() {}

  static InventoryMovement of(
      UUID id,
      UUID productId,
      InventoryOperation operation,
      UUID businessRef,
      long availableDelta,
      long reservedDelta,
      long soldDelta,
      String reason,
      Instant now) {
    Objects.requireNonNull(reason, "reason");
    if (reason.isEmpty() || reason.length() > MAX_REASON_LENGTH) {
      throw new IllegalArgumentException("reason length must be 1.." + MAX_REASON_LENGTH);
    }
    InventoryMovement movement = new InventoryMovement();
    movement.id = Objects.requireNonNull(id, "id");
    movement.productId = Objects.requireNonNull(productId, "productId");
    movement.operation = Objects.requireNonNull(operation, "operation");
    movement.businessRef = Objects.requireNonNull(businessRef, "businessRef");
    movement.availableDelta = availableDelta;
    movement.reservedDelta = reservedDelta;
    movement.soldDelta = soldDelta;
    movement.reason = reason;
    movement.createdAt = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
    return movement;
  }

  public UUID getId() {
    return id;
  }

  public UUID getProductId() {
    return productId;
  }

  public InventoryOperation getOperation() {
    return operation;
  }

  public UUID getBusinessRef() {
    return businessRef;
  }

  public long getAvailableDelta() {
    return availableDelta;
  }

  public long getReservedDelta() {
    return reservedDelta;
  }

  public long getAllocatedDelta() {
    return allocatedDelta;
  }

  public long getSoldDelta() {
    return soldDelta;
  }

  public String getReason() {
    return reason;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
