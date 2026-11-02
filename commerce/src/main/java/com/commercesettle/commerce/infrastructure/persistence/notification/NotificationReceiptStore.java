package com.commercesettle.commerce.infrastructure.persistence.notification;

import com.commercesettle.commerce.application.port.NotificationGateway.Failure;
import com.commercesettle.commerce.application.port.NotificationGateway.Receipt;
import com.commercesettle.commerce.infrastructure.persistence.TransactionLimits;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class NotificationReceiptStore {
  private final JdbcTemplate jdbc;

  public NotificationReceiptStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Receipt save(UUID delivery, String hash, String result, Instant now) {
    TransactionLimits.apply(jdbc);
    if (jdbc.queryForList(
            "SELECT id FROM commerce.notification_deliveries WHERE id=? FOR KEY SHARE", delivery)
        .isEmpty()) throw new Failure("NOTIFICATION_RETENTION_EXPIRED", true, false);
    jdbc.update(
        "INSERT INTO commerce.notification_mock_receipts(id,delivery_id,request_hash,result,processed_at) VALUES (?,?,?,?,?) ON CONFLICT(delivery_id) DO NOTHING",
        UUID.randomUUID(),
        delivery,
        hash,
        result,
        OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC));
    var receipt = find(delivery);
    if (!receipt.requestHash().equals(hash))
      throw new Failure("NOTIFICATION_BODY_CONFLICT", true, false);
    return receipt;
  }

  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public Receipt find(UUID delivery) {
    TransactionLimits.apply(jdbc);
    return jdbc
        .query(
            "SELECT delivery_id,request_hash,result,processed_at FROM commerce.notification_mock_receipts WHERE delivery_id=?",
            (row, index) -> receipt(row),
            delivery)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private static Receipt receipt(ResultSet row) throws SQLException {
    return new Receipt(
        row.getObject("delivery_id", UUID.class),
        row.getString("request_hash"),
        row.getString("result"),
        row.getObject("processed_at", OffsetDateTime.class).toInstant());
  }
}
