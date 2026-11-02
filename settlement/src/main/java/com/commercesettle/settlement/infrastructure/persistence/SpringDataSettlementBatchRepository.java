package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.domain.model.SettlementBatch;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataSettlementBatchRepository extends JpaRepository<SettlementBatch, UUID> {
  Optional<SettlementBatch> findBySellerIdAndBatchDateAndPolicyVersion(
      UUID sellerId, LocalDate batchDate, String policyVersion);

  List<SettlementBatch> findBySellerId(UUID sellerId, Pageable pageable);

  long countBySellerId(UUID sellerId);
}
