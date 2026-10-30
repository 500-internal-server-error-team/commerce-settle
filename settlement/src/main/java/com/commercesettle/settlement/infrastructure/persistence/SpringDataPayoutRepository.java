package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.domain.model.Payout;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPayoutRepository extends JpaRepository<Payout, UUID> {
  Optional<Payout> findByBatchId(UUID batchId);
}
