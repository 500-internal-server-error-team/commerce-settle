package com.commercesettle.settlement.application.event;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** OpenAPI {@code EventEnvelope}. Payment의 같은 이름 record와 같은 필드다(서비스 간 코드는 공유하지 않는다). */
public record EventEnvelope<T>(
    UUID eventId,
    String eventType,
    int eventVersion,
    OffsetDateTime occurredAt,
    UUID aggregateId,
    long aggregateVersion,
    T payload) {

  public EventEnvelope {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(aggregateId, "aggregateId");
    Objects.requireNonNull(payload, "payload");
    if (eventType.isBlank()) {
      throw new IllegalArgumentException("eventType must not be blank");
    }
    if (eventVersion < 1) {
      throw new IllegalArgumentException("eventVersion must be positive");
    }
    if (aggregateVersion < 0) {
      throw new IllegalArgumentException("aggregateVersion must not be negative");
    }
  }
}
