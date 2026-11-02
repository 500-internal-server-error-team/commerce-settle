package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.CouponKind;
import com.commercesettle.commerce.domain.model.CouponTemplate;
import com.commercesettle.commerce.domain.model.CouponTemplateStatus;
import com.commercesettle.commerce.domain.repository.CouponTemplateRepository;
import com.commercesettle.commerce.domain.repository.PageResult;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
class JpaCouponTemplateRepository implements CouponTemplateRepository {
  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

  private final SpringDataCouponTemplateRepository templates;
  private final JdbcOperations jdbc;

  JpaCouponTemplateRepository(SpringDataCouponTemplateRepository templates, JdbcOperations jdbc) {
    this.templates = templates;
    this.jdbc = jdbc;
  }

  /** 발급의 조건부 증감이 같은 행을 잡고 있으면 잠금 대기 상한 안에서 끝낸다. */
  @Override
  public CouponTemplate save(CouponTemplate template) {
    TransactionLimits.apply(jdbc);
    return templates.saveAndFlush(template);
  }

  @Override
  public Optional<CouponTemplate> findById(UUID templateId) {
    return templates.findById(templateId);
  }

  @Override
  public Optional<CouponTemplate> findByIdForUpdate(UUID templateId) {
    TransactionLimits.apply(jdbc);
    return templates.findLockedById(templateId);
  }

  @Override
  public PageResult<CouponTemplate> findSellerCampaigns(UUID sellerId, int page, int size) {
    Page<CouponTemplate> result =
        templates.findByKindAndSellerId(
            CouponKind.SELLER, sellerId, PageRequest.of(page, size, NEWEST_FIRST));
    return new PageResult<>(
        result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements());
  }

  @Override
  public boolean takeSellerIssue(UUID templateId, long discountAmount) {
    TransactionLimits.apply(jdbc);
    return jdbc.update(
            """
            UPDATE commerce.coupon_templates
            SET issued_count = issued_count + 1, committed_budget = committed_budget + ?
            WHERE id = ? AND kind = 'SELLER' AND status = 'ACTIVE'
              AND issued_count < issuance_limit
              AND committed_budget + consumed_budget + ? <= total_budget
            """,
            discountAmount,
            templateId,
            discountAmount)
        == 1;
  }

  @Override
  public Optional<CouponTemplate> findActiveWelcome() {
    return templates.findByKindAndStatus(CouponKind.WELCOME, CouponTemplateStatus.ACTIVE);
  }

  /** 활성 웰컴 부분 유일 인덱스로 충돌을 무시해 동시 생성에도 트랜잭션을 실패시키지 않는다. */
  @Override
  public void saveWelcomeIfAbsent(CouponTemplate template) {
    TransactionLimits.apply(jdbc);
    jdbc.update(
        """
        INSERT INTO commerce.coupon_templates (
            id, kind, funder, name, discount_amount, min_order_amount, min_seller_net_amount,
            allowed_methods, pg_cost_ceiling_bps, other_cost_allowance, issued_count,
            committed_budget, consumed_budget, valid_from, status, created_at, updated_at, version)
        VALUES (?, ?, ?, ?, ?, ?, 0, CAST(? AS varchar(24)[]), ?, ?, 0, 0, 0, ?, ?, ?, ?, 0)
        ON CONFLICT (kind) WHERE kind = 'WELCOME' AND status = 'ACTIVE' DO NOTHING
        """,
        template.getId(),
        template.getKind().name(),
        template.getFunder().name(),
        template.getName(),
        template.getDiscountAmount(),
        template.getMinOrderAmount(),
        template.getAllowedMethods().stream()
            .map(Enum::name)
            .collect(Collectors.joining(",", "{", "}")),
        template.getPgCostCeilingBps(),
        template.getOtherCostAllowance(),
        Timestamp.from(template.getValidFrom()),
        template.getStatus().name(),
        Timestamp.from(template.getCreatedAt()),
        Timestamp.from(template.getUpdatedAt()));
  }

  @Override
  public Map<UUID, String> findNames(Collection<UUID> templateIds) {
    return templates.findByIdIn(templateIds).stream()
        .collect(Collectors.toMap(CouponTemplate::getId, CouponTemplate::getName));
  }

  @Override
  public void addTotals(UUID templateId, int issuedDelta, long committedDelta, long consumedDelta) {
    TransactionLimits.apply(jdbc);
    int updated =
        jdbc.update(
            """
            UPDATE commerce.coupon_templates
            SET issued_count = issued_count + ?, committed_budget = committed_budget + ?,
                consumed_budget = consumed_budget + ?
            WHERE id = ?
            """,
            issuedDelta,
            committedDelta,
            consumedDelta,
            templateId);
    if (updated != 1) {
      throw new IllegalStateException("coupon template is missing: " + templateId);
    }
  }
}
