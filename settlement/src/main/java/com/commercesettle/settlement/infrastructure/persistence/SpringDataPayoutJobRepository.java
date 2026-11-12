package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.domain.model.PayoutJob;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataPayoutJobRepository extends JpaRepository<PayoutJob, UUID> {
  Optional<PayoutJob> findByPayoutId(UUID payoutId);

  List<PayoutJob> findByPayoutIdIn(Collection<UUID> payoutIds);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT j FROM PayoutJob j WHERE j.id = :id")
  Optional<PayoutJob> findByIdForUpdate(@Param("id") UUID id);
}
