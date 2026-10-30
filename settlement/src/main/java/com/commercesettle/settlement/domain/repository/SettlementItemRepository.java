package com.commercesettle.settlement.domain.repository;

import com.commercesettle.settlement.domain.model.SettlementItem;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface SettlementItemRepository {
  SettlementItem save(SettlementItem item);

  Optional<SettlementItem> findById(UUID id);

  Optional<SettlementItem> findByPaymentId(UUID paymentId);

  /** 판매자의 마감 전 지급 대상(PAYABLE·미배정·readyAt &lt; cutoffAt) 항목이 속한 상품. 지급 전 검증에 쓴다. */
  Set<UUID> findPayableProductIds(UUID sellerId, Instant cutoffAt);

  /**
   * 판매자의 마감 전 지급 대상 항목 전체를 한 문장으로 PAYOUT_RESERVED로 바꾸고 payoutId를 연결한 뒤, 항목마다 ACTIVE 지급 예약을 만든다. 상태와
   * 미배정 조건을 함께 검사하므로 이미 환불 예약·지급 배정된 항목은 고르지 않는다. 호출자의 트랜잭션에서 실행한다.
   */
  List<ReservedItem> reserveForPayout(UUID sellerId, Instant cutoffAt, UUID payoutId, Instant now);

  /**
   * 지급 성공을 항목에 반영한다. 지급에 배정된 PAYOUT_RESERVED 항목을 PAID로, 그 ACTIVE 지급 예약을 CONSUMED로 바꾸고 바꾼 항목 수를
   * 돌려준다. 호출자의 트랜잭션에서 실행한다.
   */
  int completePayout(UUID payoutId, Instant now);

  record ReservedItem(UUID id, UUID productId, long netAmount) {}
}
