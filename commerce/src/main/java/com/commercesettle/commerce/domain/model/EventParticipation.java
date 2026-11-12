package com.commercesettle.commerce.domain.model;

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
 * 구매자의 선착순 이벤트 참여. 주문당 한 행이며 확정 거절 뒤 재시도도 같은 행을 다시 예약한다. 상태 전이는 같은 주문의 {@link
 * InventoryReservation}이 이벤트 수량 변경과 함께 호출하므로 여기서는 직전 상태만 확인한다.
 */
@Entity
@Table(name = "event_participations")
public class EventParticipation extends AuditableEntity {
  @Id private UUID id;
  private UUID eventId;
  private UUID buyerId;
  private UUID orderId;

  @Enumerated(EnumType.STRING)
  private EventParticipationStatus status;

  @Version private Long version;

  protected EventParticipation() {}

  private EventParticipation(UUID id, UUID eventId, UUID buyerId, UUID orderId, Instant now) {
    super(now);
    this.id = Objects.requireNonNull(id, "id");
    this.eventId = Objects.requireNonNull(eventId, "eventId");
    this.buyerId = Objects.requireNonNull(buyerId, "buyerId");
    this.orderId = Objects.requireNonNull(orderId, "orderId");
    this.status = EventParticipationStatus.RESERVED;
  }

  public static EventParticipation reserve(
      UUID id, UUID eventId, UUID buyerId, UUID orderId, Instant now) {
    return new EventParticipation(id, eventId, buyerId, orderId, now);
  }

  void confirm(Instant now) {
    moveFrom(EventParticipationStatus.RESERVED, EventParticipationStatus.CONFIRMED, now);
  }

  void release(Instant now) {
    moveFrom(EventParticipationStatus.RESERVED, EventParticipationStatus.RELEASED, now);
  }

  void reserveAgain(Instant now) {
    moveFrom(EventParticipationStatus.RELEASED, EventParticipationStatus.RESERVED, now);
  }

  void refund(Instant now) {
    moveFrom(EventParticipationStatus.CONFIRMED, EventParticipationStatus.REFUNDED, now);
  }

  private void moveFrom(
      EventParticipationStatus expected, EventParticipationStatus next, Instant now) {
    if (status != expected) {
      throw new IllegalStateException(
          "event participation of order "
              + orderId
              + " cannot move from "
              + status
              + " to "
              + next);
    }
    status = next;
    touch(now);
  }

  public UUID getId() {
    return id;
  }

  public UUID getEventId() {
    return eventId;
  }

  public UUID getBuyerId() {
    return buyerId;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public EventParticipationStatus getStatus() {
    return status;
  }

  public long getVersion() {
    return version == null ? 0 : version;
  }
}
