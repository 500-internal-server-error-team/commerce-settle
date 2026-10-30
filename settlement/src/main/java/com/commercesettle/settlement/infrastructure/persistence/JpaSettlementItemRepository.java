package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.domain.model.SettlementItem;
import com.commercesettle.settlement.domain.repository.SettlementItemRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
class JpaSettlementItemRepository implements SettlementItemRepository {
  /** 지급 대상 조건. 상태와 미배정을 함께 검사해 환불 예약·지급 배정 항목을 고르지 않는다. 마감 정각 항목은 다음 날 대상이다. */
  private static final String PAYABLE =
      "seller_id = :sellerId AND status = 'PAYABLE' AND payout_id IS NULL AND ready_at < :cutoffAt";

  private static final String LOCK_NOT_AVAILABLE = "55P03";

  private final SpringDataSettlementItemRepository repository;
  private final NamedParameterJdbcOperations jdbc;

  JpaSettlementItemRepository(
      SpringDataSettlementItemRepository repository, NamedParameterJdbcOperations jdbc) {
    this.repository = repository;
    this.jdbc = jdbc;
  }

  @Override
  public SettlementItem save(SettlementItem item) {
    return repository.save(item);
  }

  @Override
  public Optional<SettlementItem> findById(UUID id) {
    return repository.findById(id);
  }

  @Override
  public Optional<SettlementItem> findByPaymentId(UUID paymentId) {
    return repository.findByPaymentId(paymentId);
  }

  @Override
  public Set<UUID> findPayableProductIds(UUID sellerId, Instant cutoffAt) {
    return new HashSet<>(
        jdbc.queryForList(
            "SELECT DISTINCT product_id FROM settlement.settlement_items WHERE " + PAYABLE,
            new MapSqlParameterSource()
                .addValue("sellerId", sellerId)
                .addValue("cutoffAt", time(cutoffAt)),
            UUID.class));
  }

  @Override
  public List<ReservedItem> reserveForPayout(
      UUID sellerId, Instant cutoffAt, UUID payoutId, Instant now) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("sellerId", sellerId)
            .addValue("cutoffAt", time(cutoffAt))
            .addValue("payoutId", payoutId)
            .addValue("now", time(now));
    // 다른 트랜잭션이 잡은 항목 잠금은 상한까지만 기다린다. 넘으면 이 판매자의 처리만 롤백한다.
    jdbc.queryForList("SELECT set_config('lock_timeout', '1000', true)", params);
    List<ReservedItem> reserved;
    try {
      reserved =
          jdbc.query(
              "UPDATE settlement.settlement_items"
                  + " SET status = 'PAYOUT_RESERVED', payout_id = :payoutId,"
                  + " updated_at = :now, version = version + 1"
                  + " WHERE "
                  + PAYABLE
                  + " RETURNING id, product_id, net_amount",
              params,
              (rs, row) ->
                  new ReservedItem(
                      rs.getObject("id", UUID.class),
                      rs.getObject("product_id", UUID.class),
                      rs.getLong("net_amount")));
    } catch (UncategorizedSQLException e) {
      // JdbcTemplate 기본 변환기는 55P03을 분류하지 않는다. 잠금 실패로 바꿔야 일배치가 이 판매자만 건너뛴다.
      if (LOCK_NOT_AVAILABLE.equals(e.getSQLException().getSQLState())) {
        throw new CannotAcquireLockException("settlement items are locked", e);
      }
      throw e;
    }
    jdbc.update(
        "INSERT INTO settlement.settlement_reservations"
            + " (id, item_id, previous_item_status, kind, business_ref, amount, status, payout_id,"
            + " created_at, updated_at, version)"
            + " SELECT gen_random_uuid(), id, 'PAYABLE', 'PAYOUT', :payoutId, net_amount, 'ACTIVE',"
            + " :payoutId, :now, :now, 0"
            + " FROM settlement.settlement_items WHERE payout_id = :payoutId",
        params);
    return reserved;
  }

  @Override
  public int completePayout(UUID payoutId, Instant now) {
    MapSqlParameterSource params =
        new MapSqlParameterSource().addValue("payoutId", payoutId).addValue("now", time(now));
    int paid =
        jdbc.update(
            "UPDATE settlement.settlement_items"
                + " SET status = 'PAID', updated_at = :now, version = version + 1"
                + " WHERE payout_id = :payoutId AND status = 'PAYOUT_RESERVED'",
            params);
    jdbc.update(
        "UPDATE settlement.settlement_reservations"
            + " SET status = 'CONSUMED', updated_at = :now, version = version + 1"
            + " WHERE payout_id = :payoutId AND kind = 'PAYOUT' AND status = 'ACTIVE'",
        params);
    return paid;
  }

  private static OffsetDateTime time(Instant instant) {
    return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
  }
}
