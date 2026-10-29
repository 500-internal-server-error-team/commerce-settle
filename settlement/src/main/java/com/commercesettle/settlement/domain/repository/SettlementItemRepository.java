package com.commercesettle.settlement.domain.repository;

import com.commercesettle.settlement.domain.model.SettlementItem;
import java.util.Optional;
import java.util.UUID;

public interface SettlementItemRepository {
  SettlementItem save(SettlementItem item);

  Optional<SettlementItem> findById(UUID id);

  Optional<SettlementItem> findByPaymentId(UUID paymentId);
}
