package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.domain.model.Payout;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPayoutRepository extends JpaRepository<Payout, UUID> {
  Optional<Payout> findByBatchId(UUID batchId);

  Optional<Payout> findByIdAndSellerId(UUID id, UUID sellerId);

  List<Payout> findBySellerId(UUID sellerId, Pageable pageable);

  long countBySellerId(UUID sellerId);
}
