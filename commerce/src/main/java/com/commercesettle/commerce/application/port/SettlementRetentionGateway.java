package com.commercesettle.commerce.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface SettlementRetentionGateway {
  List<Closure> lookup(Set<UUID> batchIds);

  record Closure(UUID batchId, UUID sellerId, Instant closedAt) {}

  class UnavailableException extends RuntimeException {
    public UnavailableException() {
      super("Settlement retention could not be verified");
    }
  }
}
