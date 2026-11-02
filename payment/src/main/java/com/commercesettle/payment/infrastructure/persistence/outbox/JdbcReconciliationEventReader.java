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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Outbox 행을 대조 원천으로 읽는다. Outbox 본문은 저장 뒤 바뀌지 않고 자동 삭제하지 않으며, 발행 위치 열만 진행에 따라 채워진다. 기준선 집합은 원천
 * 시각(occurred_at)이 마감 이하이고 저장 시각(created_at)이 기준선 시각 이하인 행이다.
 */
@Repository
class JdbcReconciliationEventReader implements ReconciliationEventReader {
  /** 환불 금융 완료(RefundFinancialCompleted)는 환불 SAGA에서 발행을 추가할 때 함께 넣는다. */
  private static final List<String> EVENT_TYPES = List.of("PaymentConfirmed");

  private static final String BASELINE =
      """
      FROM payment.outbox
      WHERE event_type IN (:types)
        AND occurred_at <= :cutoff
        AND created_at <= :snapshot
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
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) " + BASELINE, params(cutoffAt, snapshotAt), Long.class);
    return count == null ? 0 : count;
  }

  @Override
  public List<ReconciliationEvent> read(
      Instant cutoffAt, Instant snapshotAt, Position after, int limit) {
    MapSqlParameterSource params = params(cutoffAt, snapshotAt).addValue("limit", limit);
    String keyset = "";
    if (after != null) {
      keyset = " AND (created_at, id) < (:afterCreated, :afterId)";
      params.addValue("afterCreated", utc(after.createdAt())).addValue("afterId", after.eventId());
    }
    return jdbc.query(
        """
        SELECT id, event_type, occurred_at, created_at, topic, status,
               published_partition, published_offset, published_at,
               payload->'payload'->>'paymentId' AS payment_id,
               payload->'payload'->>'refundId' AS refund_id,
               (payload->'payload'->>'externalAmount')::bigint AS external_amount,
               (payload->'payload'->>'mileageAmount')::bigint AS mileage_amount,
               (payload->'payload'->>'platformCouponAmount')::bigint AS platform_coupon_amount,
               (payload->'payload'->>'settlementBaseAmount')::bigint AS settlement_base_amount,
               (payload->'payload'->>'feeAmount')::bigint AS fee_amount,
               (payload->'payload'->>'sellerNetAmount')::bigint AS seller_net_amount
        """
            + BASELINE
            + keyset
            + " ORDER BY created_at DESC, id DESC LIMIT :limit",
        params,
        JdbcReconciliationEventReader::event);
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
        rs.getLong("external_amount"),
        rs.getLong("mileage_amount"),
        rs.getLong("platform_coupon_amount"),
        rs.getLong("settlement_base_amount"),
        rs.getLong("fee_amount"),
        rs.getLong("seller_net_amount"),
        published,
        rs.getString("topic"),
        published ? rs.getInt("published_partition") : null,
        published ? rs.getLong("published_offset") : null,
        published ? instant(rs, "published_at") : null);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
