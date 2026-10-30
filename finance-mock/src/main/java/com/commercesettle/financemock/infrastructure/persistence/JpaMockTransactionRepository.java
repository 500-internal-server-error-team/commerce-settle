package com.commercesettle.financemock.infrastructure.persistence;

import com.commercesettle.financemock.domain.model.MockOperation;
import com.commercesettle.financemock.domain.model.MockTransaction;
import com.commercesettle.financemock.domain.repository.MockTransactionRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
class JpaMockTransactionRepository implements MockTransactionRepository {
  private final SpringDataMockTransactionRepository repository;
  private final JdbcOperations jdbc;

  JpaMockTransactionRepository(
      SpringDataMockTransactionRepository repository, JdbcOperations jdbc) {
    this.repository = repository;
    this.jdbc = jdbc;
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
  public Optional<MockTransaction> findByIdForUpdate(UUID id) {
    TransactionLimits.apply(jdbc);
    return repository.findByIdForUpdate(id);
  }

  @Override
  public Optional<MockTransaction> findByKey(
      String providerId, MockOperation operation, UUID businessKey) {
    return repository.findByProviderIdAndOperationAndBusinessKey(
        providerId, operation, businessKey);
  }
}
