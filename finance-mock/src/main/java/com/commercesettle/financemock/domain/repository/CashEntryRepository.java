package com.commercesettle.financemock.domain.repository;

import com.commercesettle.financemock.domain.model.CashEntry;

public interface CashEntryRepository {
  CashEntry save(CashEntry entry);
}
