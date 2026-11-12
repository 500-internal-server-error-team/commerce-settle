package com.commercesettle.commerce.domain.repository;

import com.commercesettle.commerce.domain.model.CouponTemplate;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface CouponTemplateRepository {
  /** 즉시 저장해 버전을 갱신한다. 바뀐 컬럼만 쓰므로 원자 증감한 집계를 덮어쓰지 않는다. */
  CouponTemplate save(CouponTemplate template);

  Optional<CouponTemplate> findById(UUID templateId);

  /** 판매자의 발급 종료가 같은 캠페인의 발급과 직렬화되도록 쓰기 잠금으로 읽는다. */
  Optional<CouponTemplate> findByIdForUpdate(UUID templateId);

  /** 판매자 본인의 SELLER 캠페인. createdAt DESC, id DESC 순서다. */
  PageResult<CouponTemplate> findSellerCampaigns(UUID sellerId, int page, int size);

  /**
   * 판매자 쿠폰 한 장의 발급 수와 할인 약속액을 조건부로 더한다. 활성 캠페인이고 발급 수·명목 예산 안일 때만 반영하며 반영하지 못하면 false다. 버전은 바꾸지
   * 않는다.
   */
  boolean takeSellerIssue(UUID templateId, long discountAmount);

  Optional<CouponTemplate> findActiveWelcome();

  /** 혜택 월·등급의 GRADE 캠페인이 없을 때만 저장한다. */
  void saveGradeIfAbsent(CouponTemplate template);

  Optional<CouponTemplate> findGrade(String grade, LocalDate targetMonth);

  /** 활성 웰컴 캠페인이 없을 때만 저장한다. 동시에 만들면 하나만 남고 나머지는 아무것도 하지 않는다. */
  void saveWelcomeIfAbsent(CouponTemplate template);

  Map<UUID, String> findNames(Collection<UUID> templateIds);

  /**
   * 발급 수·약속액·사용액을 원자적으로 더한다. 버전·updatedAt은 바꾸지 않으며 판매자 상한은 DB 제약이 보호한다. 쿠폰 행을 잠근 뒤 호출한다(쿠폰 → 템플릿
   * 순서).
   */
  void addTotals(UUID templateId, int issuedDelta, long committedDelta, long consumedDelta);
}
