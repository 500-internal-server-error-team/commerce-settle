package com.commercesettle.payment.domain.repository;

import com.commercesettle.payment.domain.model.MileageWallet;
import java.util.Optional;
import java.util.UUID;

public interface MileageWalletRepository {
  /** 구매자의 지갑을 현재 트랜잭션이 끝날 때까지 잠그고 읽는다. 같은 구매자의 다른 처리는 기다린다. */
  Optional<MileageWallet> findByBuyerIdForUpdate(UUID buyerId);

  MileageWallet save(MileageWallet wallet);
}
