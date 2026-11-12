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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 주문 재고 예약. 주문당 한 행이며 재시도도 같은 행을 갱신한다. 상태 전이와 {@link ProductInventory} 수량 변경을 한 메서드에서 함께 수행해 둘이
 * 어긋나지 않게 한다. 확정·해제는 결과의 결제 회차가 현재 회차와 같을 때만 반영하고, 이전 회차의 늦은 결과와 이미 반영된 결과는 수량을 바꾸지 않는다.
 *
 * <p>선착순 주문(eventId 있음)은 수량 1개를 상품 가용 재고가 아닌 이벤트 배정분에서 예약하며, {@link SaleEvent}의 S·R과 {@link
 * EventParticipation} 상태를 함께 바꾸는 ForEvent 메서드로만 전이한다.
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

  /** 상태 전이 결과. movement는 재고 행 수량을 바꾼 APPLIED에만 있고, 이벤트 R만 바꾼 선착순 예약·해제에는 없다. */
  public record Result(Outcome outcome, InventoryMovement movement) {
    static Result applied(InventoryMovement movement) {
      return new Result(Outcome.APPLIED, Objects.requireNonNull(movement, "movement"));
    }

    static Result appliedWithoutMovement() {
      return new Result(Outcome.APPLIED, null);
    }

    static Result unchanged(Outcome outcome) {
      return new Result(outcome, null);
    }
  }

  /** 선착순 주문은 이벤트당 구매자 1명·상품 1개다. */
  public static final int EVENT_QUANTITY = 1;

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
      UUID eventId,
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
    this.eventId = eventId;
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
            id, orderId, inventory.getProductId(), null, quantity, cycleId, expiresAt, now);
    InventoryMovement movement = inventory.reserve(movementId, cycleId, quantity, now);
    return new Created(reservation, movement);
  }

  /** 선착순 주문 생성: 이벤트 R+1은 호출자가 이벤트 행의 조건부 갱신으로 먼저 확보한다. 상품 재고 행은 바꾸지 않으므로 이력이 없다. */
  public static InventoryReservation reserveForEvent(
      UUID id,
      UUID orderId,
      UUID productId,
      UUID eventId,
      UUID cycleId,
      Instant expiresAt,
      Instant now) {
    return new InventoryReservation(
        id,
        orderId,
        productId,
        Objects.requireNonNull(eventId, "eventId"),
        EVENT_QUANTITY,
        cycleId,
        expiresAt,
        now);
  }

  /** 결제 확정: 예약 −q, 판매 +q. */
  public Result consume(
      ProductInventory inventory, UUID resultCycleId, UUID movementId, Instant now) {
    requireGeneral(inventory);
    if (!cycleId.equals(resultCycleId)) {
      return Result.unchanged(Outcome.STALE_CYCLE);
    }
    return switch (status) {
      case RESERVED -> {
        InventoryMovement movement = inventory.consume(movementId, cycleId, quantity, now);
        moveTo(InventoryReservationStatus.CONSUMED, now);
        yield Result.applied(movement);
      }
      case CONSUMED, RESTORED, DISPOSED -> Result.unchanged(Outcome.ALREADY_APPLIED);
      case RELEASED -> throw rejected(InventoryReservationStatus.CONSUMED);
    };
  }

  /** 확정 거절 또는 승인을 차단한 취소: 예약 −q, 가용 +q. */
  public Result release(
      ProductInventory inventory, UUID resultCycleId, UUID movementId, Instant now) {
    requireGeneral(inventory);
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
      case CONSUMED, RESTORED, DISPOSED -> throw rejected(InventoryReservationStatus.RELEASED);
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
    requireGeneral(inventory);
    if (isRetryApplied(newCycleId)) {
      return Result.unchanged(Outcome.ALREADY_APPLIED);
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
    requireGeneral(inventory);
    return restoreSold(inventory, operation, businessRef, movementId, now, () -> {});
  }

  /**
   * 발송 주문에서 재판매 불가로 검수한 반품의 입고(RETURN_DISPOSE, 반품 요청 ID): 판매 −q. 가용 재고에는 더하지 않는다. 복원과 같이 주문당 한 번이며
   * 이후 복원·폐기 요청은 수량을 바꾸지 않는다.
   */
  public Result dispose(
      ProductInventory inventory, UUID businessRef, UUID movementId, Instant now) {
    requireGeneral(inventory);
    return disposeSold(inventory, businessRef, movementId, now, () -> {});
  }

  /** 선착순 주문의 결제 확정: R−1, S+1, 배정 재고 −1·판매 +1. 참여는 확정 상태가 된다. */
  public Result consumeForEvent(
      ProductInventory inventory,
      SaleEvent event,
      EventParticipation participation,
      UUID resultCycleId,
      UUID movementId,
      Instant now) {
    requireEvent(inventory, event, participation);
    if (!cycleId.equals(resultCycleId)) {
      return Result.unchanged(Outcome.STALE_CYCLE);
    }
    return switch (status) {
      case RESERVED -> {
        InventoryMovement movement = event.confirmSale(inventory, cycleId, movementId, now);
        participation.confirm(now);
        moveTo(InventoryReservationStatus.CONSUMED, now);
        yield Result.applied(movement);
      }
      case CONSUMED, RESTORED, DISPOSED -> Result.unchanged(Outcome.ALREADY_APPLIED);
      case RELEASED -> throw rejected(InventoryReservationStatus.CONSUMED);
    };
  }

  /** 선착순 주문의 확정 거절·승인 차단된 취소: R−1. 이미 종료된 이벤트면 배정분 1개를 가용 재고로 돌린다. 참여는 해제되어 기간 내 다시 참여할 수 있다. */
  public Result releaseForEvent(
      ProductInventory inventory,
      SaleEvent event,
      EventParticipation participation,
      UUID resultCycleId,
      UUID movementId,
      Instant now) {
    requireEvent(inventory, event, participation);
    if (!cycleId.equals(resultCycleId)) {
      return Result.unchanged(Outcome.STALE_CYCLE);
    }
    return switch (status) {
      case RESERVED -> {
        Optional<InventoryMovement> movement =
            event.releaseSlot(inventory, cycleId, movementId, now);
        participation.release(now);
        moveTo(InventoryReservationStatus.RELEASED, now);
        yield movement.map(Result::applied).orElseGet(Result::appliedWithoutMovement);
      }
      case RELEASED -> Result.unchanged(Outcome.ALREADY_APPLIED);
      case CONSUMED, RESTORED, DISPOSED -> throw rejected(InventoryReservationStatus.RELEASED);
    };
  }

  /**
   * 선착순 주문의 확정 거절 뒤 재시도: 열린 이벤트에 남은 수량이 있을 때만 R+1로 다시 확보한다. 확보하지 못하면 상태를 바꾸지 않고
   * EventAdmissionClosedException으로 거절한다.
   */
  public Result reserveAgainForEvent(
      ProductInventory inventory,
      SaleEvent event,
      EventParticipation participation,
      UUID newCycleId,
      Instant newExpiresAt,
      Instant now) {
    requireEvent(inventory, event, participation);
    if (isRetryApplied(newCycleId)) {
      return Result.unchanged(Outcome.ALREADY_APPLIED);
    }
    event.reserveSlot(now);
    participation.reserveAgain(now);
    cycleId = newCycleId;
    expiresAt = truncate(newExpiresAt);
    moveTo(InventoryReservationStatus.RESERVED, now);
    return Result.appliedWithoutMovement();
  }

  /** 선착순 주문의 환불 복구·반품 입고: 판매 −1, 가용 +1. 이벤트 S·게이지와 참여 한도는 되돌리지 않는다. */
  public Result restoreForEvent(
      ProductInventory inventory,
      SaleEvent event,
      EventParticipation participation,
      InventoryOperation operation,
      UUID businessRef,
      UUID movementId,
      Instant now) {
    requireEvent(inventory, event, participation);
    return restoreSold(
        inventory, operation, businessRef, movementId, now, () -> participation.refund(now));
  }

  /** 선착순 주문의 재판매 불가 반품 입고: 판매 −1. 이벤트 S·게이지와 참여 한도는 되돌리지 않는다. */
  public Result disposeForEvent(
      ProductInventory inventory,
      SaleEvent event,
      EventParticipation participation,
      UUID businessRef,
      UUID movementId,
      Instant now) {
    requireEvent(inventory, event, participation);
    return disposeSold(inventory, businessRef, movementId, now, () -> participation.refund(now));
  }

  /** 주문당 한 번만 복원하며 이후 복원 요청은 출처와 관계없이 수량을 바꾸지 않는다. */
  private Result restoreSold(
      ProductInventory inventory,
      InventoryOperation operation,
      UUID businessRef,
      UUID movementId,
      Instant now,
      Runnable alsoApply) {
    return endSale(
        InventoryReservationStatus.RESTORED,
        () -> inventory.restore(movementId, operation, businessRef, quantity, now),
        alsoApply,
        now);
  }

  private Result disposeSold(
      ProductInventory inventory,
      UUID businessRef,
      UUID movementId,
      Instant now,
      Runnable alsoApply) {
    return endSale(
        InventoryReservationStatus.DISPOSED,
        () -> inventory.disposeReturn(movementId, businessRef, quantity, now),
        alsoApply,
        now);
  }

  /** 확정 판매를 복원 또는 폐기로 끝낸다. 이미 둘 중 하나로 끝났으면 출처와 관계없이 수량을 바꾸지 않는다. */
  private Result endSale(
      InventoryReservationStatus target,
      Supplier<InventoryMovement> change,
      Runnable alsoApply,
      Instant now) {
    return switch (status) {
      case CONSUMED -> {
        InventoryMovement movement = change.get();
        alsoApply.run();
        moveTo(target, now);
        yield Result.applied(movement);
      }
      case RESTORED, DISPOSED -> Result.unchanged(Outcome.ALREADY_APPLIED);
      case RESERVED, RELEASED -> throw rejected(target);
    };
  }

  /** 같은 회차로 이미 다시 확보했으면 true다. 해제 상태가 아니거나 해제된 회차를 다시 쓰는 요청은 금지된 전이다. */
  private boolean isRetryApplied(UUID newCycleId) {
    Objects.requireNonNull(newCycleId, "newCycleId");
    if (cycleId.equals(newCycleId) && status != InventoryReservationStatus.RELEASED) {
      return true;
    }
    if (status != InventoryReservationStatus.RELEASED || cycleId.equals(newCycleId)) {
      throw rejected(InventoryReservationStatus.RESERVED);
    }
    return false;
  }

  /** 주문 생성 재처리가 처음 예약과 같은 요청인지 확인한다. */
  public boolean isSameReservation(UUID productId, int quantity, UUID cycleId) {
    return eventId == null
        && this.productId.equals(productId)
        && this.quantity == quantity
        && this.cycleId.equals(cycleId);
  }

  /** 선착순 주문 생성 재처리가 처음 예약과 같은 요청인지 확인한다. */
  public boolean isSameEventReservation(UUID eventId, UUID cycleId) {
    return eventId.equals(this.eventId) && this.cycleId.equals(cycleId);
  }

  public boolean isForEvent() {
    return eventId != null;
  }

  private void requireGeneral(ProductInventory inventory) {
    if (eventId != null) {
      throw new IllegalStateException("event reservation needs an event transition: " + orderId);
    }
    requireSameProduct(inventory);
  }

  private void requireEvent(
      ProductInventory inventory, SaleEvent event, EventParticipation participation) {
    if (eventId == null) {
      throw new IllegalStateException("general reservation has no event: " + orderId);
    }
    requireSameProduct(inventory);
    if (!eventId.equals(event.getId())
        || !eventId.equals(participation.getEventId())
        || !orderId.equals(participation.getOrderId())) {
      throw new IllegalArgumentException("event or participation of another order: " + orderId);
    }
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
