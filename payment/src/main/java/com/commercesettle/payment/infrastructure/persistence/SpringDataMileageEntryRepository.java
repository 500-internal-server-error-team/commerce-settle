package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.MileageEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataMileageEntryRepository extends JpaRepository<MileageEntry, UUID> {
  List<MileageEntry> findByWalletId(UUID walletId, Pageable pageable);

  long countByWalletId(UUID walletId);
}
