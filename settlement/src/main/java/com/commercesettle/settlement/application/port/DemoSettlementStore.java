package com.commercesettle.settlement.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface DemoSettlementStore {
  void requireEnvironment(boolean enabled);

  Optional<Run> find(UUID runId);

  Run create(Run run);

  List<Item> items(Set<UUID> orderIds);

  void status(UUID runId, String status);

  List<Batch> batches(UUID runId);

  record Run(
      UUID id,
      UUID sellerId,
      Instant cutoffAt,
      Set<UUID> itemIds,
      int refundedCount,
      int deliverySeconds,
      int refundSeconds,
      String batchStatus) {}

  record Item(
      UUID id, UUID orderId, UUID sellerId, String status, Instant readyAt, UUID payoutId) {}

  record Batch(UUID id, long netAmount, String status, UUID payoutId, String payoutStatus) {}
}
