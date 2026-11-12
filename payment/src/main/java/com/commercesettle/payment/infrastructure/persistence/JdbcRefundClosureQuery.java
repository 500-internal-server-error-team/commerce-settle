package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.application.port.RefundClosureQuery;
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
      // #131 소유 스키마. 미설치 상태도 503으로 닫고 금융 환불 행을 대신 생성하지 않는다.
      return jdbc
          .query(
              "SELECT r.id, r.order_refund_request_id, r.payment_id, p.order_id, p.buyer_id,"
                  + " r.status, r.external_status, r.version, r.updated_at,"
                  + " (r.mileage_restored_at IS NULL AND NOT EXISTS (SELECT 1 FROM payment.mileage_entries m"
                  + " WHERE m.kind='RESTORE' AND (m.payment_id=r.payment_id OR m.refund_id=r.id))) AS not_restored"
                  + " FROM payment.refunds r JOIN payment.payments p ON p.id=r.payment_id WHERE r.id=?",
              (rs, row) ->
                  new Evidence(
                      rs.getObject("id", UUID.class),
                      rs.getObject("order_refund_request_id", UUID.class),
                      rs.getObject("payment_id", UUID.class),
                      rs.getObject("order_id", UUID.class),
                      rs.getObject("buyer_id", UUID.class),
                      rs.getString("status"),
                      rs.getString("external_status"),
                      rs.getBoolean("not_restored"),
                      rs.getLong("version"),
                      rs.getObject("updated_at", OffsetDateTime.class)
                          .atZoneSameInstant(ZoneId.of("Asia/Seoul"))
                          .toOffsetDateTime()),
              refundId)
          .stream()
          .findFirst();
    } catch (DataAccessException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
  }
}
