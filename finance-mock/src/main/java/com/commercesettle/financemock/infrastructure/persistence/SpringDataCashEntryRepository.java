package com.commercesettle.financemock.infrastructure.persistence;

import com.commercesettle.financemock.domain.model.CashEntry;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataCashEntryRepository extends JpaRepository<CashEntry, UUID> {}
