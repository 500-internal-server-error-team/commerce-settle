package com.commercesettle.financemock.infrastructure.persistence;

import com.commercesettle.financemock.domain.model.MockOperation;
import com.commercesettle.financemock.domain.model.MockTransaction;
import com.commercesettle.financemock.domain.repository.MockTransactionRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaMockTransactionRepository implements MockTransactionRepository {
  private final SpringDataMockTransactionRepository repository;

  JpaMockTransactionRepository(SpringDataMockTransactionRepository repository) {
    this.repository = repository;
  }

  @Override
  public MockTransaction save(MockTransaction transaction) {
    return repository.save(transaction);
  }

  @Override
  public Optional<MockTransaction> findById(UUID id) {
    return repository.findById(id);
  }

  @Override
  public Optional<MockTransaction> findByKey(
      String providerId, MockOperation operation, UUID businessKey) {
    return repository.findByProviderIdAndOperationAndBusinessKey(
        providerId, operation, businessKey);
  }
}
