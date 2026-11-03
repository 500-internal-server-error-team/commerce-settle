package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.application.port.RefundClosureQuery;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class JdbcRefundClosureQuery implements RefundClosureQuery {
  private final JdbcTemplate jdbc;

  public JdbcRefundClosureQuery(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Evidence> find(UUID refundId) {
    try {
      var rows =
          jdbc.query(
              "SELECT r.business_ref, r.id, i.payment_id, i.order_id, r.status,"
                  + " i.status AS item_status, i.payout_id, r.version, i.version AS item_version, r.updated_at,"
                  + " EXISTS (SELECT 1 FROM settlement.settlement_reservations a WHERE a.item_id=i.id"
                  + " AND a.status='ACTIVE') AS active FROM settlement.settlement_reservations r"
                  + " JOIN settlement.settlement_items i ON i.id=r.item_id"
                  + " WHERE r.kind='REFUND' AND r.business_ref=?",
              (rs, row) ->
                  new Evidence(
                      rs.getObject("business_ref", UUID.class),
                      rs.getObject("id", UUID.class),
                      rs.getObject("payment_id", UUID.class),
                      rs.getObject("order_id", UUID.class),
                      rs.getString("status"),
                      rs.getString("item_status"),
                      rs.getObject("payout_id", UUID.class),
                      rs.getBoolean("active"),
                      rs.getLong("version"),
                      rs.getLong("item_version"),
                      rs.getObject("updated_at", OffsetDateTime.class)
                          .atZoneSameInstant(ZoneId.of("Asia/Seoul"))
                          .toOffsetDateTime()),
              refundId);
      if (rows.size() > 1) throw new ResponseStatusException(HttpStatus.CONFLICT);
      return rows.stream().findFirst();
    } catch (DataAccessException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
  }
}
