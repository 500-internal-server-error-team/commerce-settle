package com.commercesettle.financemock.infrastructure.persistence;

import com.commercesettle.financemock.domain.model.AccountPurpose;
import com.commercesettle.financemock.domain.model.MockAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface SpringDataMockAccountRepository extends JpaRepository<MockAccount, UUID> {
  Optional<MockAccount> findByProviderIdAndAccountRefAndPurpose(
      String providerId, String accountRef, AccountPurpose purpose);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT a FROM MockAccount a WHERE a.providerId = :providerId"
          + " AND a.accountRef = :accountRef AND a.purpose = :purpose")
  Optional<MockAccount> findForUpdate(String providerId, String accountRef, AccountPurpose purpose);
}
