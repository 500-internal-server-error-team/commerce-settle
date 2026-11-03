package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.CouponGrade;
import com.commercesettle.commerce.domain.model.CouponIssueTarget;
import com.commercesettle.commerce.domain.repository.CouponIssueTargetRepository;
import com.commercesettle.commerce.domain.repository.PageResult;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
class JpaCouponIssueTargetRepository implements CouponIssueTargetRepository {
  private final SpringDataCouponIssueTargetRepository targets;
  private final JdbcOperations jdbc;

  JpaCouponIssueTargetRepository(
      SpringDataCouponIssueTargetRepository targets, JdbcOperations jdbc) {
    this.targets = targets;
    this.jdbc = jdbc;
  }

  /** 구매 확정 뒤에는 환불 기한이 끝나 환불되지 않으므로, 기간 안에 구매 확정된 주문이 환불되지 않은 거래다. */
  @Override
  public List<RankedBuyer> rankSpenders(Instant from, Instant until) {
    return jdbc.query(
        """
        SELECT buyer_id, spend, rank_no FROM (
            SELECT buyer_id, SUM(external_amount) AS spend,
                   RANK() OVER (ORDER BY SUM(external_amount) DESC) AS rank_no
            FROM commerce.orders
            WHERE status = 'PURCHASE_CONFIRMED'
              AND purchase_confirmed_at >= ? AND purchase_confirmed_at < ?
            GROUP BY buyer_id
            HAVING SUM(external_amount) > 0) ranked
        WHERE rank_no <= 100
        ORDER BY rank_no, buyer_id
        """,
        (rs, row) ->
            new RankedBuyer(
                rs.getObject("buyer_id", UUID.class), rs.getLong("spend"), rs.getInt("rank_no")),
        Timestamp.from(from),
        Timestamp.from(until));
  }

  @Override
  public long countSpendersAtLeast(Instant from, Instant until, long minSpend) {
    return jdbc.queryForObject(
        """
        SELECT count(*) FROM (
            SELECT buyer_id FROM commerce.orders
            WHERE status = 'PURCHASE_CONFIRMED'
              AND purchase_confirmed_at >= ? AND purchase_confirmed_at < ?
            GROUP BY buyer_id
            HAVING SUM(external_amount) >= ?) spenders
        """,
        Long.class,
        Timestamp.from(from),
        Timestamp.from(until),
        minSpend);
  }

  @Override
  public void saveAll(
      UUID runId, List<RankedBuyer> buyers, Map<CouponGrade, UUID> templates, Instant now) {
    Timestamp at = Timestamp.from(now);
    jdbc.batchUpdate(
        """
        INSERT INTO commerce.coupon_issue_targets
            (id, run_id, template_id, buyer_id, external_spend, rank_no, status,
             created_at, updated_at, version)
        VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, 0)
        ON CONFLICT (run_id, buyer_id) DO NOTHING
        """,
        buyers,
        500,
        (ps, buyer) -> {
          ps.setObject(1, UUID.randomUUID());
          ps.setObject(2, runId);
          ps.setObject(3, templates.get(CouponGrade.of(buyer.externalSpend())));
          ps.setObject(4, buyer.buyerId());
          ps.setLong(5, buyer.externalSpend());
          ps.setInt(6, buyer.rankNo());
          ps.setTimestamp(7, at);
          ps.setTimestamp(8, at);
        });
  }

  @Override
  public Summary summarize(UUID runId) {
    return jdbc.queryForObject(
        """
        SELECT count(*) AS target_count, coalesce(sum(t.discount_amount), 0) AS discount_total
        FROM commerce.coupon_issue_targets r
        JOIN commerce.coupon_templates t ON t.id = r.template_id
        WHERE r.run_id = ?
        """,
        (rs, row) -> new Summary(rs.getLong("target_count"), rs.getLong("discount_total")),
        runId);
  }

  @Override
  public List<UUID> findPendingIds(UUID runId, int limit) {
    return jdbc.queryForList(
        """
        SELECT id FROM commerce.coupon_issue_targets
        WHERE run_id = ? AND status = 'PENDING'
        ORDER BY rank_no, buyer_id
        LIMIT ?
        """,
        UUID.class,
        runId,
        limit);
  }

  @Override
  public Issuance issuance(UUID runId) {
    return jdbc.queryForObject(
        """
        SELECT
            (SELECT count(*) FROM commerce.coupon_issue_targets
             WHERE run_id = ? AND status = 'ISSUED') AS issued,
            (SELECT count(*) FROM commerce.coupon_issue_targets
             WHERE run_id = ? AND buyer_coupon_id IS NOT NULL) AS linked,
            (SELECT count(*) FROM commerce.coupon_history h
             JOIN commerce.coupon_issue_targets t ON t.id = h.business_ref
             WHERE t.run_id = ? AND h.operation = 'ISSUE') AS histories
        """,
        (rs, row) ->
            new Issuance(rs.getLong("issued"), rs.getLong("linked"), rs.getLong("histories")),
        runId,
        runId,
        runId);
  }

  @Override
  public Optional<CouponIssueTarget> findByIdForUpdate(UUID targetId) {
    TransactionLimits.apply(jdbc);
    return targets.findLockedById(targetId);
  }

  @Override
  public CouponIssueTarget save(CouponIssueTarget target) {
    return targets.saveAndFlush(target);
  }

  @Override
  public PageResult<TargetView> findByRun(UUID runId, int page, int size) {
    long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM commerce.coupon_issue_targets WHERE run_id = ?",
            Long.class,
            runId);
    List<TargetView> rows =
        jdbc.query(
            """
        SELECT t.id, t.buyer_id, t.external_spend, t.rank_no, c.grade,
               c.discount_amount, t.buyer_coupon_id, t.status
        FROM commerce.coupon_issue_targets t
        JOIN commerce.coupon_templates c ON c.id = t.template_id
        WHERE t.run_id = ? ORDER BY t.rank_no, t.buyer_id LIMIT ? OFFSET ?
        """,
            (rs, row) ->
                new TargetView(
                    rs.getObject("id", UUID.class),
                    rs.getObject("buyer_id", UUID.class),
                    rs.getLong("external_spend"),
                    rs.getInt("rank_no"),
                    rs.getString("grade"),
                    rs.getLong("discount_amount"),
                    rs.getObject("buyer_coupon_id", UUID.class),
                    rs.getString("status")),
            runId,
            size,
            (long) page * size);
    return new PageResult<>(rows, page, size, count);
  }
}
