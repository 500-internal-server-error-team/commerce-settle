package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.SellerAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface SpringDataSellerAccountRepository extends JpaRepository<SellerAccount, UUID> {
  Optional<SellerAccount> findByEmailNormalizedAndDeletedAtIsNull(String emailNormalized);

  Optional<SellerAccount> findByIdAndDeletedAtIsNull(UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from SellerAccount a where a.id = :id and a.deletedAt is null")
  Optional<SellerAccount> findLockedById(UUID id);

  boolean existsByEmailNormalized(String emailNormalized);
}
