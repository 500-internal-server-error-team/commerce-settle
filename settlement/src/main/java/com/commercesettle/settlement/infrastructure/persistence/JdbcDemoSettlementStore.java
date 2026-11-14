package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.application.port.DemoSettlementStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDemoSettlementStore implements DemoSettlementStore {
  private final NamedParameterJdbcOperations jdbc;
  private final ObjectMapper mapper;

  public JdbcDemoSettlementStore(NamedParameterJdbcOperations jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  public void requireEnvironment(boolean enabled) {
    if (enabled)
      jdbc.update(
          "INSERT INTO settlement.demo_environment(singleton) VALUES(true) ON CONFLICT DO NOTHING",
          new MapSqlParameterSource());
    else if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM settlement.demo_environment)",
            new MapSqlParameterSource(),
            Boolean.class)))
      throw new IllegalStateException("A demo database cannot start in normal mode");
  }

  @Override
  public Optional<Run> find(UUID id) {
    return jdbc
        .query(
            "SELECT * FROM settlement.demo_runs WHERE id=:id",
            new MapSqlParameterSource("id", id),
            (rs, row) -> {
              try {
                return new Run(
                    rs.getObject("id", UUID.class),
                    rs.getObject("seller_id", UUID.class),
                    rs.getObject("cutoff_at", OffsetDateTime.class).toInstant(),
                    mapper.readValue(rs.getString("item_ids"), new TypeReference<Set<UUID>>() {}),
                    rs.getInt("refunded_count"),
                    rs.getInt("delivery_seconds"),
                    rs.getInt("refund_seconds"),
                    rs.getString("batch_status"));
              } catch (java.io.IOException e) {
                throw new IllegalStateException("Invalid stored demo run", e);
              }
            })
        .stream()
        .findFirst();
  }

  @Override
  public Run create(Run run) {
    try {
      jdbc.update(
          "INSERT INTO settlement.demo_runs(id,seller_id,cutoff_at,item_ids,refunded_count,delivery_seconds,refund_seconds,batch_status) VALUES(:id,:seller,:cutoff,:items::jsonb,:refunded,:delivery,:refund,'READY') ON CONFLICT(id) DO NOTHING",
          new MapSqlParameterSource("id", run.id())
              .addValue("seller", run.sellerId())
              .addValue("cutoff", run.cutoffAt().atOffset(ZoneOffset.UTC))
              .addValue("items", mapper.writeValueAsString(run.itemIds()))
              .addValue("refunded", run.refundedCount())
              .addValue("delivery", run.deliverySeconds())
              .addValue("refund", run.refundSeconds()));
      return find(run.id()).orElseThrow();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public List<Item> items(Set<UUID> orderIds) {
    if (orderIds.isEmpty()) return List.of();
    return jdbc.query(
        "SELECT id,order_id,seller_id,status,ready_at,payout_id FROM settlement.settlement_items WHERE order_id IN (:orders)",
        new MapSqlParameterSource("orders", orderIds),
        (rs, row) -> {
          var ready = rs.getObject("ready_at", OffsetDateTime.class);
          return new Item(
              rs.getObject("id", UUID.class),
              rs.getObject("order_id", UUID.class),
              rs.getObject("seller_id", UUID.class),
              rs.getString("status"),
              ready == null ? null : ready.toInstant(),
              rs.getObject("payout_id", UUID.class));
        });
  }

  @Override
  public void status(UUID id, String status) {
    jdbc.update(
        "UPDATE settlement.demo_runs SET batch_status=:status WHERE id=:id AND (batch_status<>'COMPLETED' OR :status='COMPLETED') AND (:status<>'STARTED' OR batch_status IN ('READY','STARTED'))",
        new MapSqlParameterSource("id", id).addValue("status", status));
  }

  @Override
  public List<Batch> batches(UUID id) {
    return jdbc.query(
        "SELECT b.id,b.net_amount,b.status,p.id payout_id,p.status payout_status FROM settlement.settlement_batches b LEFT JOIN settlement.payouts p ON p.batch_id=b.id WHERE b.demo_run_id=:id ORDER BY b.id",
        new MapSqlParameterSource("id", id),
        (rs, row) ->
            new Batch(
                rs.getObject("id", UUID.class),
                rs.getLong("net_amount"),
                rs.getString("status"),
                rs.getObject("payout_id", UUID.class),
                rs.getString("payout_status")));
  }
}
