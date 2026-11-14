package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.application.port.DemoRunStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcDemoRunStore implements DemoRunStore {
  private final JdbcOperations jdbc;
  private final ObjectMapper mapper;

  public JdbcDemoRunStore(JdbcOperations jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  @Transactional
  public void requireEnvironment(boolean enabled) {
    TransactionLimits.apply(jdbc);
    if (enabled)
      jdbc.update(
          "INSERT INTO commerce.demo_environment(singleton) VALUES(true) ON CONFLICT DO NOTHING");
    else if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM commerce.demo_environment)", Boolean.class)))
      throw new IllegalStateException("A demo database cannot start in normal mode");
  }

  @Override
  public Run create(Run run) {
    jdbc.update(
        "INSERT INTO commerce.demo_runs(id,seller_id,product_id,event_id,delivery_seconds,refund_seconds,created_at) VALUES(?,?,?,?,?,?,?)",
        run.id(),
        run.sellerId(),
        run.productId(),
        run.eventId(),
        run.deliverySeconds(),
        run.refundSeconds(),
        time(run.createdAt()));
    return run;
  }

  @Override
  public Optional<Run> find(UUID id, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM commerce.demo_runs WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> {
              try {
                var frozen = rs.getObject("frozen_at", OffsetDateTime.class);
                List<UUID> ids =
                    mapper.readValue(rs.getString("order_ids"), new TypeReference<List<UUID>>() {});
                return new Run(
                    rs.getObject("id", UUID.class),
                    rs.getObject("seller_id", UUID.class),
                    rs.getObject("product_id", UUID.class),
                    rs.getObject("event_id", UUID.class),
                    rs.getInt("delivery_seconds"),
                    rs.getInt("refund_seconds"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                    frozen == null ? null : frozen.toInstant(),
                    ids);
              } catch (java.io.IOException e) {
                throw new IllegalStateException("Invalid stored demo run", e);
              }
            },
            id)
        .stream()
        .findFirst();
  }

  @Override
  public void freeze(UUID id, List<UUID> orderIds, Instant now) {
    try {
      jdbc.update(
          "UPDATE commerce.demo_runs SET order_ids=?::jsonb,frozen_at=? WHERE id=? AND frozen_at IS NULL",
          mapper.writeValueAsString(orderIds),
          time(now),
          id);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public List<Member> members(UUID runId) {
    return jdbc.query(
        "SELECT id,status,fulfillment_status,version FROM commerce.orders WHERE demo_run_id=? ORDER BY id LIMIT 10001",
        (rs, row) ->
            new Member(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getString("fulfillment_status"),
                rs.getLong("version")),
        runId);
  }

  @Override
  public boolean atCapacity(UUID runId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT count(*)>=10000 FROM commerce.orders WHERE demo_run_id=?",
            Boolean.class,
            runId));
  }

  @Override
  public boolean eventMatches(UUID eventId, UUID productId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM commerce.sale_events WHERE id=? AND product_id=?)",
            Boolean.class,
            eventId,
            productId));
  }

  @Override
  public boolean eventSoldOut(UUID eventId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM commerce.sale_events WHERE id=? AND status='SOLD_OUT')",
            Boolean.class,
            eventId));
  }

  private static OffsetDateTime time(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }
}
