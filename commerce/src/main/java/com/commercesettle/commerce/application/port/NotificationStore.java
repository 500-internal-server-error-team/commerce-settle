package com.commercesettle.commerce.application.port;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.Notification;
import com.commercesettle.commerce.domain.model.Notification.Kind;
import com.commercesettle.commerce.domain.model.Notification.Status;
import com.commercesettle.commerce.domain.repository.PageResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface NotificationStore {
  void append(Draft draft);

  Optional<Notification> find(UUID id);

  PageResult<Notification> page(ActorKind kind, UUID recipient, Status status, int page, int size);

  Claim claim(Instant now);

  Notification finish(
      Lease lease,
      Status status,
      String errorCode,
      Instant sentAt,
      String attemptStatus,
      Instant now);

  void replay(UUID id, long expected, String operator, String reason, Instant now);

  PageResult<Attempt> attempts(UUID id, int page, int size);

  Acceptance accept(EventEnvelope<JsonNode> event, Source source, Instant now);

  Optional<Notice> claimNotice(UUID eventId, Instant now);

  boolean completeNotice(UUID id, long expectedVersion, Instant now);

  String retryNotice(UUID id, long expectedVersion, String errorCode, boolean review, Instant now);

  void quarantine(Source source, String errorCode);

  record Draft(
      String source,
      UUID eventId,
      Kind kind,
      UUID businessRef,
      ActorKind recipientKind,
      UUID recipientId,
      Instant occurredAt,
      JsonNode payload) {}

  record Lease(Notification notification, String operation, UUID attemptId) {}

  record Claim(Notification notification, Lease lease, Notification expired) {
    public boolean processed() {
      return notification != null;
    }
  }

  enum Acceptance {
    NEW,
    DUPLICATE,
    CONFLICT
  }

  record Source(String topic, int partition, long offset, String key, byte[] body) {}

  record Notice(UUID id, long version, EventEnvelope<JsonNode> event) {}

  record Attempt(
      UUID id,
      UUID deliveryId,
      int attemptNo,
      String operation,
      String status,
      Instant startedAt,
      Instant finishedAt,
      String errorCode,
      String operatorRef,
      String reason) {}
}
