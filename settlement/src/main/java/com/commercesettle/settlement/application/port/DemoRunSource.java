package com.commercesettle.settlement.application.port;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface DemoRunSource {
  Source read(UUID runId);

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  record Source(
      UUID id,
      UUID sellerId,
      UUID productId,
      UUID eventId,
      int deliverySeconds,
      int refundSeconds,
      OffsetDateTime frozenAt,
      List<UUID> orderIds,
      List<UUID> payableOrderIds,
      List<UUID> refundedOrderIds,
      int pendingCount,
      int excludedCount) {}
}
