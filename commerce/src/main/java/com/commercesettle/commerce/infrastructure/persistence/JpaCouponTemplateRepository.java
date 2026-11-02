package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.CouponKind;
import com.commercesettle.commerce.domain.model.CouponTemplate;
import com.commercesettle.commerce.domain.model.CouponTemplateStatus;
import com.commercesettle.commerce.domain.repository.CouponTemplateRepository;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
class JpaCouponTemplateRepository implements CouponTemplateRepository {
  private final SpringDataCouponTemplateRepository templates;
  private final JdbcOperations jdbc;

  JpaCouponTemplateRepository(SpringDataCouponTemplateRepository templates, JdbcOperations jdbc) {
    this.templates = templates;
    this.jdbc = jdbc;
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
