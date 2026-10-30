package com.commercesettle.financemock.domain.repository;

import com.commercesettle.financemock.domain.model.MockOperation;
import com.commercesettle.financemock.domain.model.MockTransaction;
import java.util.Optional;
import java.util.UUID;

public interface MockTransactionRepository {
  MockTransaction save(MockTransaction transaction);

  Optional<MockTransaction> findById(UUID id);

  /** 확정 전에 거래 행을 잠근다. 원요청과 재개가 같은 거래를 동시에 확정하지 않게 한다. */
  Optional<MockTransaction> findByIdForUpdate(UUID id);

  Optional<MockTransaction> findByKey(String providerId, MockOperation operation, UUID businessKey);
}
