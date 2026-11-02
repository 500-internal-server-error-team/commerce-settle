package com.commercesettle.commerce.domain.model;

import com.commercesettle.commerce.domain.exception.SaleEventConflictException;
import com.commercesettle.commerce.domain.exception.SaleEventConflictException.Reason;
import com.commercesettle.commerce.domain.exception.VersionConflictException;
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

/**
 * 판매자 상품 하나의 선착순 이벤트. 미공개(DRAFT)일 때만 내용을 바꾸고, 공개 때 한정 수량 Q를 상품 재고에서 배정한다. 공개 후 이름·가격·수량·기간은 고정하며 조기
 * 종료만 허용한다. 종료는 판매(S)·결제 대기 예약(R)이 아닌 배정분 {@code Q−S−R}만 가용 재고로 돌리고 기존 예약은 해제하지 않는다.
 */
@Entity
@Table(name = "sale_events")
public class SaleEvent extends AuditableEntity {
  public static final int MAX_NAME_LENGTH = 80;
  public static final long MIN_EVENT_PRICE = 1;
  public static final long MAX_EVENT_PRICE = 10_000_000;
  public static final long MAX_QUOTA = 10_000_000;
  static final String PERIOD_END_REASON = "이벤트 기간 종료";

  /** 게이지 값. percent는 결제 확정 판매량 기준이며 예약만으로 100%가 되지 않는다. */
  public record Gauge(
      long quota,
      long soldCount,
      long reservedCount,
      long remainingQuantity,
      int percent,
      EventAdmission admission) {}

  @Id private UUID id;
  private UUID sellerId;
  private UUID productId;
  private String name;
  private long eventPrice;
  private long quota;
  private long soldCount;
  private long reservedCount;
  private Instant startsAt;
  private Instant endsAt;

  @Enumerated(EnumType.STRING)
  private SaleEventStatus status;

  private Instant publishedAt;
  private Instant closedAt;
  @Version private Long version;

  protected SaleEvent() {}

  private SaleEvent(UUID id, UUID sellerId, Instant now) {
    super(now);
    this.id = Objects.requireNonNull(id, "id");
    this.sellerId = Objects.requireNonNull(sellerId, "sellerId");
    this.status = SaleEventStatus.DRAFT;
  }

  public static SaleEvent register(
      UUID id,
      UUID sellerId,
      UUID productId,
      String name,
      long eventPrice,
      long quota,
      Instant startsAt,
      Instant endsAt,
      Instant now) {
    SaleEvent event = new SaleEvent(id, sellerId, now);
    event.apply(productId, name, eventPrice, quota, startsAt, endsAt);
    return event;
  }

  /** 미공개 이벤트의 전체 변경. 상품 소유 확인은 응용 서비스가 한다. */
  public void change(
      UUID productId,
      String name,
      long eventPrice,
      long quota,
      Instant startsAt,
      Instant endsAt,
      long expectedVersion,
      Instant now) {
    requireDraft();
    requireVersion(expectedVersion);
    apply(productId, name, eventPrice, quota, startsAt, endsAt);
    touch(now);
  }

  /**
   * 공개하고 한정 수량을 재고에서 배정한다. 시작 전이면 SCHEDULED, 이미 시작했으면 OPEN이다. 상품의 판매 상태와 같은 상품의 기간 중복은 호출자가 같은 재고 행
   * 잠금 아래에서 확인한다. 가용 수량이 부족하면 상태를 바꾸지 않는다.
   */
  public InventoryMovement publish(
      ProductInventory inventory, long expectedVersion, UUID movementId, Instant now) {
    requireSameProduct(inventory);
    requireDraft();
    requireVersion(expectedVersion);
    if (!now.isBefore(endsAt)) {
      throw new SaleEventConflictException(Reason.PERIOD_ENDED);
    }
    InventoryMovement movement = inventory.allocateToEvent(movementId, id, quota, now);
    status = now.isBefore(startsAt) ? SaleEventStatus.SCHEDULED : SaleEventStatus.OPEN;
    publishedAt = truncate(now);
    touch(now);
    return movement;
  }

  /** 시작 시각이 된 예정 이벤트를 연다. 바꾸지 않았으면 false다. */
  public boolean openIfStarted(Instant now) {
    if (status != SaleEventStatus.SCHEDULED || now.isBefore(startsAt) || !now.isBefore(endsAt)) {
      return false;
    }
    status = SaleEventStatus.OPEN;
    touch(now);
    return true;
  }

  /** 판매자의 조기 종료. 종료 시각이 지났지만 아직 기간 종료 처리 전인 이벤트도 같은 방식으로 닫는다. */
  public InventoryMovement close(
      ProductInventory inventory,
      long expectedVersion,
      String reason,
      UUID movementId,
      Instant now) {
    requireSameProduct(inventory);
    if (!status.isInProgress()) {
      throw new SaleEventConflictException(Reason.NOT_IN_PROGRESS);
    }
    requireVersion(expectedVersion);
    return finish(inventory, reason, movementId, now);
  }

  /** 종료 시각이 지난 진행 중 이벤트를 닫는다. 대상이 아니면 아무것도 바꾸지 않는다. */
  public Optional<InventoryMovement> closeIfEnded(
      ProductInventory inventory, UUID movementId, Instant now) {
    requireSameProduct(inventory);
    if (!status.isInProgress() || now.isBefore(endsAt)) {
      return Optional.empty();
    }
    return Optional.of(finish(inventory, PERIOD_END_REASON, movementId, now));
  }

  public Gauge gauge(Instant now) {
    return gauge(status, startsAt, endsAt, quota, soldCount, reservedCount, now);
  }

  /** 저장 상태가 기간 처리 전이어도 서버 시각으로 시작 전·종료를 판단한다. */
  static Gauge gauge(
      SaleEventStatus status,
      Instant startsAt,
      Instant endsAt,
      long quota,
      long soldCount,
      long reservedCount,
      Instant now) {
    long remaining = quota - soldCount - reservedCount;
    EventAdmission admission;
    if (status == SaleEventStatus.SOLD_OUT || soldCount == quota) {
      admission = EventAdmission.SOLD_OUT;
    } else if (status == SaleEventStatus.CLOSED || !now.isBefore(endsAt)) {
      admission = EventAdmission.CLOSED;
    } else if (status == SaleEventStatus.DRAFT || now.isBefore(startsAt)) {
      admission = EventAdmission.NOT_STARTED;
    } else if (remaining == 0) {
      admission = EventAdmission.WAITING_PAYMENTS;
    } else {
      admission = EventAdmission.OPEN;
    }
    int percent = (int) (100 * soldCount / quota);
    return new Gauge(quota, soldCount, reservedCount, remaining, percent, admission);
  }

  public boolean isOwnedBy(UUID sellerId) {
    return this.sellerId.equals(sellerId);
  }

  public boolean isPublished() {
    return status != SaleEventStatus.DRAFT;
  }

  private InventoryMovement finish(
      ProductInventory inventory, String reason, UUID movementId, Instant now) {
    InventoryMovement movement =
        inventory.returnFromEvent(movementId, id, quota - soldCount - reservedCount, reason, now);
    status = SaleEventStatus.CLOSED;
    closedAt = truncate(now);
    touch(now);
    return movement;
  }

  private void apply(
      UUID productId, String name, long eventPrice, long quota, Instant startsAt, Instant endsAt) {
    Objects.requireNonNull(name, "name");
    if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException("name length must be 1.." + MAX_NAME_LENGTH);
    }
    if (eventPrice < MIN_EVENT_PRICE || eventPrice > MAX_EVENT_PRICE) {
      throw new IllegalArgumentException("eventPrice out of range");
    }
    if (quota < 1 || quota > MAX_QUOTA) {
      throw new IllegalArgumentException("quota must be 1.." + MAX_QUOTA);
    }
    Instant start = truncate(startsAt);
    Instant end = truncate(endsAt);
    if (!start.isBefore(end)) {
      throw new IllegalArgumentException("startsAt must be before endsAt");
    }
    this.productId = Objects.requireNonNull(productId, "productId");
    this.name = name;
    this.eventPrice = eventPrice;
    this.quota = quota;
    this.startsAt = start;
    this.endsAt = end;
  }

  private void requireDraft() {
    if (status != SaleEventStatus.DRAFT) {
      throw new SaleEventConflictException(Reason.NOT_DRAFT);
    }
  }

  private void requireVersion(long expectedVersion) {
    if (expectedVersion != getVersion()) {
      throw new VersionConflictException("sale event", expectedVersion, getVersion());
    }
  }

  private void requireSameProduct(ProductInventory inventory) {
    if (!productId.equals(inventory.getProductId())) {
      throw new IllegalArgumentException(
          "inventory of another product: " + inventory.getProductId());
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getSellerId() {
    return sellerId;
  }

  public UUID getProductId() {
    return productId;
  }

  public String getName() {
    return name;
  }

  public long getEventPrice() {
    return eventPrice;
  }

  public long getQuota() {
    return quota;
  }

  public long getSoldCount() {
    return soldCount;
  }

  public long getReservedCount() {
    return reservedCount;
  }

  public Instant getStartsAt() {
    return startsAt;
  }

  public Instant getEndsAt() {
    return endsAt;
  }

  public SaleEventStatus getStatus() {
    return status;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public Instant getClosedAt() {
    return closedAt;
  }

  public long getVersion() {
    return version == null ? 0 : version;
  }
}
