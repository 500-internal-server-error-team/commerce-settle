package com.commercesettle.commerce.infrastructure.persistence.notification;

import com.commercesettle.commerce.application.port.NotificationStore.Draft;
import com.commercesettle.commerce.application.port.RetentionStore;
import com.commercesettle.commerce.domain.model.Notification;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** 본문 삭제와 같은 업무 알림의 재수신을 직렬화한다. */
public final class NotificationRetentionGuard {
  private NotificationRetentionGuard() {}

  public static Optional<Boolean> retired(
      JdbcTemplate jdbc,
      String source,
      String kind,
      UUID business,
      String recipientKind,
      UUID recipient,
      String payload) {
    return jdbc
        .query(
            """
        SELECT payload_hash=sha256(convert_to(CAST(? AS jsonb)::text,'UTF8'))
        FROM commerce.notification_retired_keys
        WHERE source_service=? AND kind=? AND business_ref=? AND recipient_kind=? AND recipient_id=?
        """,
            (row, index) -> row.getBoolean(1),
            payload,
            source,
            kind,
            business,
            recipientKind,
            recipient)
        .stream()
        .findFirst();
  }

  public static boolean replayExpired(JdbcTemplate jdbc, UUID delivery, Instant now) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS (SELECT 1 FROM commerce.notification_items WHERE delivery_id=?)
          AND NOT EXISTS (SELECT 1 FROM commerce.notification_items i
            LEFT JOIN commerce.orders o ON o.id=CAST(i.payload->>'orderId' AS uuid)
            LEFT JOIN commerce.notification_retention_batches b
              ON b.batch_id=CAST(i.payload->>'batchId' AS uuid) AND i.source_service='settlement'
            WHERE i.delivery_id=? AND (
              (i.source_service='settlement' AND (b.closed_at IS NULL OR b.closed_at>?))
              OR (i.source_service<>'settlement' AND (o.closed_at IS NULL OR o.closed_at>?))))
        """,
            Boolean.class,
            delivery,
            delivery,
            now.minus(RetentionStore.PERIOD).atOffset(ZoneOffset.UTC),
            now.minus(RetentionStore.PERIOD).atOffset(ZoneOffset.UTC)));
  }

  public static void registerBatch(JdbcTemplate jdbc, Draft draft) {
    if (!draft.source().equals("settlement")) return;
    UUID batch;
    try {
      batch = UUID.fromString(draft.payload().required("batchId").textValue());
    } catch (RuntimeException error) {
      throw new Notification.ContentConflictException("Invalid settlement batch");
    }
    jdbc.update(
        "INSERT INTO commerce.notification_retention_batches(batch_id,seller_id) VALUES (?,?) ON CONFLICT(batch_id) DO NOTHING",
        batch,
        draft.recipientId());
    var seller =
        jdbc.queryForObject(
            "SELECT seller_id FROM commerce.notification_retention_batches WHERE batch_id=? FOR UPDATE",
            UUID.class,
            batch);
    if (!draft.recipientId().equals(seller))
      throw new Notification.ContentConflictException("Settlement batch recipient conflict");
  }

  public static void lockBatches(JdbcTemplate jdbc, UUID delivery) {
    jdbc.queryForList(
        """
        SELECT b.batch_id FROM commerce.notification_retention_batches b
        WHERE EXISTS (SELECT 1 FROM commerce.notification_items i WHERE i.delivery_id=?
          AND i.source_service='settlement' AND b.batch_id=CAST(i.payload->>'batchId' AS uuid))
        ORDER BY b.batch_id FOR UPDATE
        """,
        delivery);
  }

  public static void lockItems(JdbcTemplate jdbc, UUID delivery) {
    jdbc.query(
        """
        SELECT pg_advisory_xact_lock(key_hash) FROM (
          SELECT hashtextextended(concat_ws(':',source_service,kind,business_ref,recipient_kind,recipient_id),0) AS key_hash
          FROM commerce.notification_items WHERE delivery_id=?
          ORDER BY source_service,kind,business_ref,recipient_kind,recipient_id
        ) AS keys
        """,
        (RowCallbackHandler) row -> {},
        delivery);
  }

  public static void lock(
      JdbcTemplate jdbc,
      String source,
      String kind,
      UUID business,
      String recipientKind,
      UUID recipient) {
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        source + ":" + kind + ":" + business + ":" + recipientKind + ":" + recipient);
  }
}
