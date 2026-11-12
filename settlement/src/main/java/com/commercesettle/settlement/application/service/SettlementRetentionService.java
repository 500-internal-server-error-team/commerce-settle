package com.commercesettle.settlement.application.service;

import com.commercesettle.settlement.domain.repository.SettlementBatchRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementRetentionService {
  private final SettlementBatchRepository batches;

  public SettlementRetentionService(SettlementBatchRepository batches) {
    this.batches = batches;
  }

  @Transactional(readOnly = true, timeout = 5)
  public List<Closure> lookup(Set<UUID> ids) {
    if (ids == null
        || ids.isEmpty()
        || ids.size() > 1000
        || ids.stream().anyMatch(java.util.Objects::isNull))
      throw new IllegalArgumentException("Invalid settlement batch IDs");
    return batches.findAllById(ids).stream()
        .map(batch -> new Closure(batch.getId(), batch.getSellerId(), batch.getClosedAt()))
        .toList();
  }

  public record Closure(UUID batchId, UUID sellerId, Instant closedAt) {}
}
