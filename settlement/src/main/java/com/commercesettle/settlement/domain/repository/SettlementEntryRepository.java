package com.commercesettle.settlement.domain.repository;

import com.commercesettle.settlement.domain.model.SettlementEntry;
import java.util.List;
import java.util.UUID;

public interface SettlementEntryRepository {
  /** 새 원장 줄을 추가한다. 원장은 고치지 않으므로 기존 줄을 갱신하지 않는다. */
  void append(List<SettlementEntry> entries);

  List<SettlementEntry> findByPaymentId(UUID paymentId);
}
