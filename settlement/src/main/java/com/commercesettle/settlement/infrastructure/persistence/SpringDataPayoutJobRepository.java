package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.domain.model.PayoutJob;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPayoutJobRepository extends JpaRepository<PayoutJob, UUID> {
  Optional<PayoutJob> findByPayoutId(UUID payoutId);
}
