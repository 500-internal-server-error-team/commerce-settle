package com.commercesettle.payment.infrastructure.persistence.outbox;

import com.commercesettle.payment.application.port.ReconciliationEventReader;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Outbox 행을 대조 원천으로 읽는다. 환불의 E/M은 최초 결제 확정과 대조하고, 나머지 금액도 같은 불변 확정 본문에서 읽는다. 기준선 집합은 원천
 * 시각(occurred_at)이 마감 이하이고 저장 시각(created_at)이 기준선 시각 이하인 행이다.
 */
@Repository
class JdbcReconciliationEventReader implements ReconciliationEventReader {
  private static final List<String> EVENT_TYPES =
      List.of("PaymentConfirmed", "RefundFinancialCompleted");

  private static final String BASELINE =
      """
      FROM payment.outbox event
      LEFT JOIN LATERAL (
        SELECT confirmed.payload, count(*) OVER () AS matches
        FROM payment.outbox confirmed
        WHERE event.event_type = 'RefundFinancialCompleted'
          AND confirmed.event_type = 'PaymentConfirmed'
          AND confirmed.aggregate_id = event.aggregate_id
          AND confirmed.payload->'payload'->>'paymentId' = event.payload->'payload'->>'paymentId'
          AND confirmed.created_at <= :snapshot
        ORDER BY confirmed.created_at, confirmed.id
        LIMIT 1
      ) original ON true
      CROSS JOIN LATERAL (
        SELECT CASE WHEN event.event_type = 'PaymentConfirmed' THEN event.payload->'payload'
                    ELSE original.payload->'payload' END AS amounts
      ) money
      WHERE event.event_type IN (:types)
        AND event.occurred_at <= :cutoff
        AND event.created_at <= :snapshot
      """;

  private final NamedParameterJdbcTemplate jdbc;

  JdbcReconciliationEventReader(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Instant now() {
    return jdbc.getJdbcTemplate()
        .queryForObject("SELECT clock_timestamp()", OffsetDateTime.class)
        .toInstant();
  }

  @Override
  public long count(Instant cutoffAt, Instant snapshotAt) {
    return jdbc.queryForObject(
        """
        SELECT count(*) AS total, count(*) FILTER (
          WHERE event.event_type = 'RefundFinancialCompleted' AND (
            original.matches IS DISTINCT FROM 1
            OR event.payload->'payload'->'externalAmount'
               IS DISTINCT FROM money.amounts->'externalAmount'
            OR event.payload->'payload'->'mileageAmount'
               IS DISTINCT FROM money.amounts->'mileageAmount')) AS invalid
        """
            + BASELINE,
        params(cutoffAt, snapshotAt),
        (rs, row) -> {
          if (rs.getLong("invalid") != 0) {
            throw new TransientDataAccessResourceException("Reconciliation source is unavailable");
          }
          return rs.getLong("total");
        });
  }

  @Override
  public List<ReconciliationEvent> read(
      Instant cutoffAt, Instant snapshotAt, Position after, int limit) {
    MapSqlParameterSource params = params(cutoffAt, snapshotAt).addValue("limit", limit);
    String keyset = "";
    if (after != null) {
      keyset = " AND (event.created_at, event.id) < (:afterCreated, :afterId)";
      params.addValue("afterCreated", utc(after.createdAt())).addValue("afterId", after.eventId());
    }
    try {
      return jdbc.query(
          """
        SELECT event.id, event.event_type, event.occurred_at, event.created_at, event.topic, event.status,
               event.published_partition, event.published_offset, event.published_at,
               event.payload->'payload'->>'paymentId' AS payment_id,
               event.payload->'payload'->>'refundId' AS refund_id,
               (money.amounts->>'externalAmount')::bigint AS external_amount,
               (money.amounts->>'mileageAmount')::bigint AS mileage_amount,
               (money.amounts->>'platformCouponAmount')::bigint AS platform_coupon_amount,
               (money.amounts->>'settlementBaseAmount')::bigint AS settlement_base_amount,
               (money.amounts->>'feeAmount')::bigint AS fee_amount,
               (money.amounts->>'sellerNetAmount')::bigint AS seller_net_amount
        """
              + BASELINE
              + keyset
              + " ORDER BY event.created_at DESC, event.id DESC LIMIT :limit",
          params,
          JdbcReconciliationEventReader::event);
    } catch (DataAccessException e) {
      throw new TransientDataAccessResourceException("Reconciliation source is unavailable", e);
    }
  }

  private static MapSqlParameterSource params(Instant cutoffAt, Instant snapshotAt) {
    return new MapSqlParameterSource()
        .addValue("types", EVENT_TYPES)
        .addValue("cutoff", utc(cutoffAt))
        .addValue("snapshot", utc(snapshotAt));
  }

  private static OffsetDateTime utc(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }

  private static ReconciliationEvent event(ResultSet rs, int row) throws SQLException {
    String refund = rs.getString("refund_id");
    boolean published = "PUBLISHED".equals(rs.getString("status"));
    return new ReconciliationEvent(
        rs.getObject("id", UUID.class),
        rs.getString("event_type"),
        instant(rs, "occurred_at"),
        instant(rs, "created_at"),
        UUID.fromString(rs.getString("payment_id")),
        refund == null ? null : UUID.fromString(refund),
        amount(rs, "external_amount"),
        amount(rs, "mileage_amount"),
        amount(rs, "platform_coupon_amount"),
        amount(rs, "settlement_base_amount"),
        amount(rs, "fee_amount"),
        amount(rs, "seller_net_amount"),
        published,
        rs.getString("topic"),
        published ? rs.getInt("published_partition") : null,
        published ? rs.getLong("published_offset") : null,
        published ? instant(rs, "published_at") : null);
  }

  private static long amount(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    if (rs.wasNull() || value < 0) {
      throw new TransientDataAccessResourceException("Reconciliation source is unavailable");
    }
    return value;
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
