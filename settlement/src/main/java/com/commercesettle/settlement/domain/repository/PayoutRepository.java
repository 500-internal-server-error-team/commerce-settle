package com.commercesettle.settlement.domain.repository;

import com.commercesettle.settlement.domain.model.Payout;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PayoutRepository {
  Payout save(Payout payout);

  Optional<Payout> findById(UUID id);

  Optional<Payout> findByBatchId(UUID batchId);

  /** 판매자 본인의 지급만 찾는다. 다른 판매자의 지급은 없는 것과 같다. */
  Optional<Payout> findByIdAndSellerId(UUID id, UUID sellerId);

  /** 판매자의 지급을 생성 시각·ID 내림차순으로 한 페이지 읽는다. */
  List<Payout> findBySeller(UUID sellerId, int page, int size);

  long countBySeller(UUID sellerId);
}
