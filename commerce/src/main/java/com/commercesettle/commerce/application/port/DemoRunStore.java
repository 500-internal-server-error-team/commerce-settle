package com.commercesettle.commerce.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DemoRunStore {
  void requireEnvironment(boolean enabled);

  Run create(Run run);

  Optional<Run> find(UUID id, boolean lock);

  void freeze(UUID id, List<UUID> orderIds, Instant now);

  List<Member> members(UUID runId);

  boolean atCapacity(UUID runId);

  boolean eventMatches(UUID eventId, UUID productId);

  boolean eventSoldOut(UUID eventId);

  record Run(
      UUID id,
      UUID sellerId,
      UUID productId,
      UUID eventId,
      int deliverySeconds,
      int refundSeconds,
      Instant createdAt,
      Instant frozenAt,
      List<UUID> orderIds) {}

  record Member(UUID id, String status, String fulfillmentStatus, long version) {}
}
