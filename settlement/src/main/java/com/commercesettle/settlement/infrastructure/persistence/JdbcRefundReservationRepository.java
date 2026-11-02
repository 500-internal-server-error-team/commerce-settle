package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.domain.model.SettlementItemStatus;
import com.commercesettle.settlement.domain.repository.RefundReservationRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;

/** 지급 예약({@code reserveForPayout})과 같은 조건부 UPDATE·잠금 대기 상한으로 환불 예약을 다룬다. */
@Repository
class JdbcRefundReservationRepository implements RefundReservationRepository {
  private static final String LOCK_NOT_AVAILABLE = "55P03";
  private static final String RESERVATION_COLUMNS =
      "r.id, r.item_id, r.business_ref, r.kind, r.amount, r.previous_item_status, r.status,"
          + " i.version AS item_version";

  private final NamedParameterJdbcOperations jdbc;

  JdbcRefundReservationRepository(NamedParameterJdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ItemState> findItem(UUID paymentId) {
    return jdbc
        .query(
            "SELECT id, status, external_amount, mileage_amount, net_amount, payout_id, version"
                + " FROM settlement.settlement_items"
                + " WHERE payment_id = :paymentId",
            new MapSqlParameterSource("paymentId", paymentId),
            (rs, row) ->
                new ItemState(
                    rs.getObject("id", UUID.class),
                    SettlementItemStatus.valueOf(rs.getString("status")),
                    rs.getLong("external_amount"),
                    rs.getLong("mileage_amount"),
                    rs.getLong("net_amount"),
                    rs.getObject("payout_id", UUID.class),
                    rs.getLong("version")))
        .stream()
        .findFirst();
  }

  @Override
  public Optional<Reservation> find(UUID itemId, UUID refundId) {
    return jdbc
        .query(
            "SELECT "
                + RESERVATION_COLUMNS
                + " FROM settlement.settlement_reservations r"
                + " JOIN settlement.settlement_items i ON i.id = r.item_id"
                + " WHERE r.item_id = :itemId AND r.kind = 'REFUND' AND r.business_ref = :refundId",
            new MapSqlParameterSource("itemId", itemId).addValue("refundId", refundId),
            JdbcRefundReservationRepository::reservation)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<Reservation> findActive(UUID itemId) {
    return jdbc
        .query(
            "SELECT "
                + RESERVATION_COLUMNS
                + " FROM settlement.settlement_reservations r"
                + " JOIN settlement.settlement_items i ON i.id = r.item_id"
                + " WHERE r.item_id = :itemId AND r.status = 'ACTIVE'",
            new MapSqlParameterSource("itemId", itemId),
            JdbcRefundReservationRepository::reservation)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<Reservation> reserve(UUID itemId, UUID refundId, Instant now) {
    MapSqlParameterSource params =
        new MapSqlParameterSource("itemId", itemId)
            .addValue("refundId", refundId)
            .addValue("reservationId", UUID.randomUUID())
            .addValue("now", time(now));
    // 지급 선점과 같은 항목 행을 다툰다. 잠금은 상한까지만 기다리고, 넘으면 이번 수신을 롤백해 재전달에 맡긴다.
    jdbc.queryForList("SELECT set_config('lock_timeout', '1000', true)", params);
    Optional<Reserved> reserved;
    try {
      // 직전 상태는 잠근 행에서 읽는다. RETURNING은 바뀐 뒤의 값만 돌려준다.
      reserved =
          jdbc
              .query(
                  "WITH target AS (SELECT id, status FROM settlement.settlement_items"
                      + "   WHERE id = :itemId FOR UPDATE)"
                      + " UPDATE settlement.settlement_items i"
                      + " SET status = 'REFUND_RESERVED', updated_at = :now, version = version + 1"
                      + " FROM target WHERE i.id = target.id"
                      + " AND target.status IN ('WAITING_CONFIRMATION', 'PAYABLE')"
                      + " AND i.payout_id IS NULL"
                      + " RETURNING target.status AS previous, i.net_amount, i.version",
                  params,
                  (rs, row) ->
                      new Reserved(
                          SettlementItemStatus.valueOf(rs.getString("previous")),
                          rs.getLong("net_amount"),
                          rs.getLong("version")))
              .stream()
              .findFirst();
    } catch (UncategorizedSQLException e) {
      // JdbcTemplate 기본 변환기는 55P03을 분류하지 않는다.
      if (LOCK_NOT_AVAILABLE.equals(e.getSQLException().getSQLState())) {
        throw new CannotAcquireLockException("settlement item is locked", e);
      }
      throw e;
    }
    if (reserved.isEmpty()) {
      return Optional.empty();
    }
    Reserved item = reserved.get();
    params.addValue("previous", item.previous().name()).addValue("amount", item.netAmount());
    jdbc.update(
        "INSERT INTO settlement.settlement_reservations"
            + " (id, item_id, previous_item_status, kind, business_ref, amount, status, payout_id,"
            + " created_at, updated_at, version)"
            + " VALUES (:reservationId, :itemId, :previous, 'REFUND', :refundId, :amount, 'ACTIVE',"
            + " NULL, :now, :now, 0)",
        params);
    return Optional.of(
        new Reservation(
            (UUID) params.getValue("reservationId"),
            itemId,
            refundId,
            "REFUND",
            item.netAmount(),
            item.previous(),
            "ACTIVE",
            item.version()));
  }

  @Override
  public Optional<Long> consume(UUID reservationId, Instant now) {
    MapSqlParameterSource params =
        new MapSqlParameterSource("reservationId", reservationId).addValue("now", time(now));
    int consumed =
        jdbc.update(
            "UPDATE settlement.settlement_reservations"
                + " SET status = 'CONSUMED', updated_at = :now, version = version + 1"
                + " WHERE id = :reservationId AND kind = 'REFUND' AND status = 'ACTIVE'",
            params);
    if (consumed == 0) {
      return Optional.empty();
    }
    return jdbc
        .queryForList(
            "UPDATE settlement.settlement_items"
                + " SET status = 'REFUNDED', updated_at = :now, version = version + 1"
                + " WHERE status = 'REFUND_RESERVED' AND id ="
                + " (SELECT item_id FROM settlement.settlement_reservations WHERE id = :reservationId)"
                + " RETURNING version",
            params,
            Long.class)
        .stream()
        .findFirst();
  }

  private static Reservation reservation(ResultSet rs, int row) throws SQLException {
    return new Reservation(
        rs.getObject("id", UUID.class),
        rs.getObject("item_id", UUID.class),
        rs.getObject("business_ref", UUID.class),
        rs.getString("kind"),
        rs.getLong("amount"),
        SettlementItemStatus.valueOf(rs.getString("previous_item_status")),
        rs.getString("status"),
        rs.getLong("item_version"));
  }

  private static OffsetDateTime time(Instant instant) {
    return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
  }

  private record Reserved(SettlementItemStatus previous, long netAmount, long version) {}
}
