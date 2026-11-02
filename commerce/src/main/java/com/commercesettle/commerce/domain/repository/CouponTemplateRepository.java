package com.commercesettle.commerce.domain.repository;

import com.commercesettle.commerce.domain.model.CouponTemplate;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface CouponTemplateRepository {
  Optional<CouponTemplate> findActiveWelcome();

  /** 활성 웰컴 캠페인이 없을 때만 저장한다. 동시에 만들면 하나만 남고 나머지는 아무것도 하지 않는다. */
  void saveWelcomeIfAbsent(CouponTemplate template);

  Map<UUID, String> findNames(Collection<UUID> templateIds);

  /**
   * 발급 수·약속액·사용액을 원자적으로 더한다. 버전·updatedAt은 바꾸지 않으며 판매자 상한은 DB 제약이 보호한다. 쿠폰 행을 잠근 뒤 호출한다(쿠폰 → 템플릿
   * 순서).
   */
  void addTotals(UUID templateId, int issuedDelta, long committedDelta, long consumedDelta);
}
