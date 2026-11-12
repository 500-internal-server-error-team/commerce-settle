package com.commercesettle.financemock.domain.repository;

import com.commercesettle.financemock.domain.model.MockOperation;
import com.commercesettle.financemock.domain.model.MockTransaction;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MockTransactionRepository {
  MockTransaction save(MockTransaction transaction);

  Optional<MockTransaction> findById(UUID id);

  Optional<MockTransaction> findByKey(String providerId, MockOperation operation, UUID businessKey);

  Optional<MockTransaction> findForUpdate(UUID id);

  /** 자동 입금은 환불 등 다른 실행이 잠근 원승인을 기다리지 않는다. */
  Optional<MockTransaction> findForReceiptUpdate(UUID id);

  boolean hasActiveRefund(UUID originalId);

  List<UUID> findDueReceipts(Instant now, int limit);
}
