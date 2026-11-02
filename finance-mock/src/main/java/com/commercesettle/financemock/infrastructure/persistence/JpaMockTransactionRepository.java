package com.commercesettle.financemock.infrastructure.persistence;

import com.commercesettle.financemock.domain.model.MockOperation;
import com.commercesettle.financemock.domain.model.MockTransaction;
import com.commercesettle.financemock.domain.repository.MockTransactionRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
  public Optional<MockTransaction> findByKey(
      String providerId, MockOperation operation, UUID businessKey) {
    return repository.findByProviderIdAndOperationAndBusinessKey(
        providerId, operation, businessKey);
  }

  @Override
  public Optional<MockTransaction> findForUpdate(UUID id) {
    TransactionLimits.apply(jdbc);
    return repository.findForUpdate(id);
  }

  @Override
  public Optional<MockTransaction> findForReceiptUpdate(UUID id) {
    TransactionLimits.apply(jdbc);
    return jdbc
        .queryForList(
            "SELECT id FROM finance_mock.mock_transactions WHERE id=? FOR UPDATE SKIP LOCKED",
            UUID.class,
            id)
        .stream()
        .findFirst()
        .flatMap(repository::findById);
  }

  @Override
  public boolean hasActiveRefund(UUID originalId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS (SELECT 1 FROM finance_mock.mock_transactions
        WHERE original_id = ? AND operation = 'REFUND' AND status IN ('PROCESSING','SUCCEEDED'))
        """,
            Boolean.class,
            originalId));
  }

  @Override
  public List<UUID> findDueReceipts(Instant now, int limit) {
    TransactionLimits.apply(jdbc);
    return jdbc.queryForList(
        """
        SELECT id FROM finance_mock.mock_transactions
        WHERE operation = 'APPROVE' AND status = 'SUCCEEDED'
        AND pg_received_at IS NULL AND pg_receipt_due_at <= ?
        ORDER BY pg_receipt_due_at, id LIMIT ? FOR UPDATE SKIP LOCKED
        """,
        UUID.class,
        Timestamp.from(now),
        limit);
  }
}
