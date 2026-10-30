package com.commercesettle.financemock.infrastructure.persistence;

import com.commercesettle.financemock.domain.model.AccountPurpose;
import com.commercesettle.financemock.domain.model.MockAccount;
import com.commercesettle.financemock.domain.repository.MockAccountRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
class JpaMockAccountRepository implements MockAccountRepository {
  private final SpringDataMockAccountRepository repository;
  private final JdbcOperations jdbc;

  JpaMockAccountRepository(SpringDataMockAccountRepository repository, JdbcOperations jdbc) {
    this.repository = repository;
    this.jdbc = jdbc;
  }

  @Override
  public MockAccount save(MockAccount account) {
    return repository.save(account);
  }

  @Override
  public Optional<MockAccount> findById(UUID id) {
    return repository.findById(id);
  }

  @Override
  public Optional<MockAccount> find(String providerId, String accountRef, AccountPurpose purpose) {
    return repository.findByProviderIdAndAccountRefAndPurpose(providerId, accountRef, purpose);
  }

  @Override
  public Optional<MockAccount> findForUpdate(
      String providerId, String accountRef, AccountPurpose purpose) {
    TransactionLimits.apply(jdbc);
    return repository.findForUpdate(providerId, accountRef, purpose);
  }

  @Override
  public Optional<MockAccount> findByIdForUpdate(UUID id) {
    TransactionLimits.apply(jdbc);
    return repository.findByIdForUpdate(id);
  }
}
