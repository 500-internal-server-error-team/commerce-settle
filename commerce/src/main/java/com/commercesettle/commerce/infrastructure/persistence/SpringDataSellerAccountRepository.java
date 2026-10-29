package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.SellerAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataSellerAccountRepository extends JpaRepository<SellerAccount, UUID> {
  Optional<SellerAccount> findByEmailNormalizedAndDeletedAtIsNull(String emailNormalized);

  Optional<SellerAccount> findByIdAndDeletedAtIsNull(UUID id);

  boolean existsByEmailNormalized(String emailNormalized);
}
