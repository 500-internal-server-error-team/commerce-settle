package com.commercesettle.commerce.infrastructure.persistence.order;

import com.commercesettle.commerce.application.port.RefundClosureEvidence.Subject;
import com.commercesettle.commerce.application.port.RefundClosureStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcRefundClosureStore implements RefundClosureStore {
  private final JdbcTemplate jdbc;

  public JdbcRefundClosureStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void confirm(Confirmation c) {
    var s = c.subject();
    jdbc.update(
        "INSERT INTO commerce.refund_closure_confirmations"
            + " (id, request_id, refund_id, payment_id, order_id, buyer_id, request_version, order_version, confirmed_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?)",
        c.id(),
        s.requestId(),
        s.refundId(),
        s.paymentId(),
        s.orderId(),
        s.buyerId(),
        s.requestVersion(),
        s.orderVersion(),
        time(c.confirmedAt()));
  }

  @Override
  public Optional<Confirmation> find(UUID id) {
    return jdbc
        .query(
            "SELECT * FROM commerce.refund_closure_confirmations WHERE id=?",
            (rs, row) ->
                new Confirmation(
                    rs.getObject("id", UUID.class),
                    new Subject(
                        rs.getObject("request_id", UUID.class),
                        rs.getObject("refund_id", UUID.class),
                        rs.getObject("payment_id", UUID.class),
                        rs.getObject("order_id", UUID.class),
                        rs.getObject("buyer_id", UUID.class),
                        rs.getLong("request_version"),
                        rs.getLong("order_version")),
                    rs.getObject("confirmed_at", OffsetDateTime.class).toInstant()),
            id)
        .stream()
        .findFirst();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void preserve(UUID id, UUID requestId, String operator, String snapshot, Instant now) {
    jdbc.update(
        "INSERT INTO commerce.refund_closure_evidence"
            + " (id, request_id, operator_ref, snapshot, closed_at) VALUES (?,?,?,?::jsonb,?)",
        id,
        requestId,
        operator,
        snapshot,
        time(now));
  }

  private static OffsetDateTime time(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
