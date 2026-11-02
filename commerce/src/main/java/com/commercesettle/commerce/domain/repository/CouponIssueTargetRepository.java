package com.commercesettle.commerce.domain.repository;

import com.commercesettle.commerce.domain.model.CouponGrade;
import com.commercesettle.commerce.domain.model.CouponIssueTarget;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface CouponIssueTargetRepository {
  /** 대상 기간에 구매 확정된 주문의 외부 결제액 합계 순위. RANK가 100 이하인 구매자 전원이며 동점자는 같은 순위다. */
  List<RankedBuyer> rankSpenders(Instant from, Instant until);

  /** 대상 기간의 외부 결제액 합계가 minSpend 이상인 구매자 수. 경계 동점자가 빠지지 않았는지 검증한다. */
  long countSpendersAtLeast(Instant from, Instant until, long minSpend);

  /** 같은 회차·구매자는 한 번만 저장한다. */
  void saveAll(UUID runId, List<RankedBuyer> buyers, Map<CouponGrade, UUID> templates, Instant now);

  /** 회차 대상 수와 대상별 등급 할인액 합계. */
  Summary summarize(UUID runId);

  List<UUID> findPendingIds(UUID runId, int limit);

  Optional<CouponIssueTarget> findByIdForUpdate(UUID targetId);

  CouponIssueTarget save(CouponIssueTarget target);

  record RankedBuyer(UUID buyerId, long externalSpend, int rankNo) {}

  record Summary(long count, long discountTotal) {}
}
