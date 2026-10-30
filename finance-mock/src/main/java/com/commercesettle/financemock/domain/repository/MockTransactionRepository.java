package com.commercesettle.financemock.domain.repository;

import com.commercesettle.financemock.domain.model.MockOperation;
import com.commercesettle.financemock.domain.model.MockTransaction;
import java.util.Optional;
import java.util.UUID;

public interface MockTransactionRepository {
  MockTransaction save(MockTransaction transaction);

  Optional<MockTransaction> findById(UUID id);

  Optional<MockTransaction> findByKey(String providerId, MockOperation operation, UUID businessKey);
}
