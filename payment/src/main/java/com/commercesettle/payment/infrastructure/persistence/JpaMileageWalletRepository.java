package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.MileageWallet;
import com.commercesettle.payment.domain.repository.MileageWalletRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaMileageWalletRepository implements MileageWalletRepository {
  private final SpringDataMileageWalletRepository repository;

  JpaMileageWalletRepository(SpringDataMileageWalletRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<MileageWallet> findByBuyerIdForUpdate(UUID buyerId) {
    return repository.findByBuyerIdForUpdate(buyerId);
  }

  @Override
  public MileageWallet save(MileageWallet wallet) {
    return repository.save(wallet);
  }
}
