package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.domain.model.SettlementItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataSettlementItemRepository extends JpaRepository<SettlementItem, UUID> {
  Optional<SettlementItem> findByPaymentId(UUID paymentId);

  List<SettlementItem> findBySellerId(UUID sellerId, Pageable pageable);

  long countBySellerId(UUID sellerId);
}
