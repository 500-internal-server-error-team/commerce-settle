package com.commercesettle.financemock.domain.repository;

import com.commercesettle.financemock.domain.model.AccountPurpose;
import com.commercesettle.financemock.domain.model.MockAccount;
import java.util.Optional;
import java.util.UUID;

public interface MockAccountRepository {
  MockAccount save(MockAccount account);

  Optional<MockAccount> findById(UUID id);

  Optional<MockAccount> find(String providerId, String accountRef, AccountPurpose purpose);

  /** 잔액을 바꾸기 전에 계정 행을 잠근다. 잠금 대기는 상한을 넘으면 현재 트랜잭션을 롤백한다. */
  Optional<MockAccount> findForUpdate(String providerId, String accountRef, AccountPurpose purpose);
}
