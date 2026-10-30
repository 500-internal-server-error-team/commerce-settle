package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.MileageWallet;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataMileageWalletRepository extends JpaRepository<MileageWallet, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select w from MileageWallet w where w.buyerId = :buyerId")
  Optional<MileageWallet> findByBuyerIdForUpdate(@Param("buyerId") UUID buyerId);
}
