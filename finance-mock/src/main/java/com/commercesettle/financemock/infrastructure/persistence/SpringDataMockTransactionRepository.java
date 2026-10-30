package com.commercesettle.financemock.infrastructure.persistence;

import com.commercesettle.financemock.domain.model.MockOperation;
import com.commercesettle.financemock.domain.model.MockTransaction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataMockTransactionRepository extends JpaRepository<MockTransaction, UUID> {
  Optional<MockTransaction> findByProviderIdAndOperationAndBusinessKey(
      String providerId, MockOperation operation, UUID businessKey);
}
