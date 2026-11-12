package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.BuyerAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface SpringDataBuyerAccountRepository extends JpaRepository<BuyerAccount, UUID> {
  Optional<BuyerAccount> findByEmailNormalizedAndDeletedAtIsNull(String emailNormalized);

  Optional<BuyerAccount> findByIdAndDeletedAtIsNull(UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from BuyerAccount a where a.id = :id and a.deletedAt is null")
  Optional<BuyerAccount> findLockedById(UUID id);

  boolean existsByEmailNormalized(String emailNormalized);
}
