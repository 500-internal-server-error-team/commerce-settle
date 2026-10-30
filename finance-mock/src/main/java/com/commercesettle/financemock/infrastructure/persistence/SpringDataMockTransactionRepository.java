package com.commercesettle.financemock.infrastructure.persistence;

import com.commercesettle.financemock.domain.model.MockOperation;
import com.commercesettle.financemock.domain.model.MockTransaction;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface SpringDataMockTransactionRepository extends JpaRepository<MockTransaction, UUID> {
  Optional<MockTransaction> findByProviderIdAndOperationAndBusinessKey(
      String providerId, MockOperation operation, UUID businessKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT t FROM MockTransaction t WHERE t.id = :id")
  Optional<MockTransaction> findByIdForUpdate(UUID id);
}
