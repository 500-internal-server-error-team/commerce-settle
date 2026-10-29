package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.BuyerAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataBuyerAccountRepository extends JpaRepository<BuyerAccount, UUID> {
  Optional<BuyerAccount> findByEmailNormalizedAndDeletedAtIsNull(String emailNormalized);

  Optional<BuyerAccount> findByIdAndDeletedAtIsNull(UUID id);

  boolean existsByEmailNormalized(String emailNormalized);
}
