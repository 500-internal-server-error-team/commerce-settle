package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.application.port.RetentionStore;
import com.commercesettle.commerce.infrastructure.persistence.notification.NotificationRetentionGuard;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcRetentionStore implements RetentionStore {
  private final JdbcTemplate jdbc;

  public JdbcRetentionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public List<UUID> due(Target target, Instant now, int limit, Set<UUID> skipped) {
    if (limit < 1 || limit > 1000)
      throw new IllegalArgumentException("Invalid retention batch size");
    TransactionLimits.apply(jdbc);
    var excluded = new SqlArrayValue("uuid", skipped.toArray());
    if (target == Target.ORDER) {
      return jdbc.query(
          """
          SELECT o.id FROM commerce.orders o
          JOIN commerce.order_addresses a ON a.order_id=o.id
          LEFT JOIN commerce.retention_checks c ON c.target_kind='ORDER' AND c.target_id=o.id
          WHERE o.closed_at<=? AND a.masked_at IS NULL
            AND (c.next_run_at IS NULL OR c.next_run_at<=?)
            AND NOT (o.id=ANY(?))
          ORDER BY coalesce(c.next_run_at,o.closed_at),o.id FOR UPDATE OF o SKIP LOCKED LIMIT ?
          """,
          (row, index) -> row.getObject(1, UUID.class),
          time(now.minus(PERIOD)),
          time(now),
          excluded,
          limit);
    }
    return jdbc.query(
        """
        SELECT d.id FROM commerce.notification_deliveries d
        LEFT JOIN commerce.retention_checks c ON c.target_kind='DELIVERY' AND c.target_id=d.id
        WHERE d.status IN ('SENT','REVIEW_REQUIRED')
          AND (c.next_run_at IS NULL OR c.next_run_at<=?)
          AND EXISTS (SELECT 1 FROM commerce.notification_items i JOIN commerce.orders o
            ON o.id=CAST(i.payload->>'orderId' AS uuid) WHERE i.delivery_id=d.id AND o.closed_at<=?)
          AND NOT (d.id=ANY(?))
        ORDER BY coalesce(c.next_run_at,d.created_at),d.id FOR UPDATE OF d SKIP LOCKED LIMIT ?
        """,
        (row, index) -> row.getObject(1, UUID.class),
        time(now),
        time(now.minus(PERIOD)),
        excluded,
        limit);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public String process(Target target, UUID id, Instant now) {
    TransactionLimits.apply(jdbc);
    return target == Target.ORDER ? mask(id, now) : purge(id, now);
  }

  private String mask(UUID id, Instant now) {
    var locked =
        jdbc.queryForList("SELECT id FROM commerce.orders WHERE id=? FOR UPDATE SKIP LOCKED", id);
    if (locked.isEmpty()) return null;
    var addresses =
        jdbc.queryForList(
            "SELECT id,masked_at FROM commerce.order_addresses WHERE order_id=? FOR UPDATE", id);
    if (addresses.isEmpty() || addresses.getFirst().get("masked_at") != null) return null;
    if (!ready(Target.ORDER, id, now)) return null;
    String result = eligible(id, now) ? "MASKED" : "HELD";
    if (result.equals("MASKED")) {
      jdbc.update(
          """
          UPDATE commerce.order_addresses SET recipient_ciphertext=decode('00','hex'),
            phone_ciphertext=decode('00','hex'),address_ciphertext=decode('00','hex'),
            masked_at=?,updated_at=?,version=version+1 WHERE order_id=? AND masked_at IS NULL
          """,
          time(now),
          time(now),
          id);
    }
    record(Target.ORDER, id, result, now, now.plus(Duration.ofDays(1)));
    return result;
  }

  private boolean eligible(UUID id, Instant now) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT o.status IN ('CANCELLED','REFUNDED','PURCHASE_CONFIRMED')
          AND o.closed_at IS NOT NULL AND o.closed_at<=?
          AND a.hold_reason IS NULL
          AND NOT EXISTS (SELECT 1 FROM commerce.order_refund_requests r WHERE r.order_id=o.id
            AND r.status IN ('PENDING_SELLER','APPROVED','REFUNDING','FAILED_REVIEW'))
        FROM commerce.orders o JOIN commerce.order_addresses a ON a.order_id=o.id WHERE o.id=?
        """,
            Boolean.class,
            time(now.minus(PERIOD)),
            id));
  }

  private String purge(UUID id, Instant now) {
    // 발송도 delivery 행을 선점한다. 주문→주소→delivery 잠금 순서를 유지한다.
    var orderIds =
        jdbc.query(
            """
        SELECT DISTINCT o.id FROM commerce.notification_items i JOIN commerce.orders o
          ON o.id=CAST(i.payload->>'orderId' AS uuid) WHERE i.delivery_id=? ORDER BY o.id
        """,
            (row, index) -> row.getObject(1, UUID.class),
            id);
    if (orderIds.isEmpty()) return null;
    var lockedOrders =
        jdbc.query(
            """
        SELECT o.id FROM commerce.orders o WHERE EXISTS (SELECT 1 FROM commerce.notification_items i
          WHERE i.delivery_id=? AND o.id=CAST(i.payload->>'orderId' AS uuid))
        ORDER BY o.id FOR UPDATE SKIP LOCKED
        """,
            (row, index) -> row.getObject(1, UUID.class),
            id);
    if (!lockedOrders.equals(orderIds)) return null;
    jdbc.queryForList(
        """
        SELECT a.id FROM commerce.order_addresses a WHERE EXISTS (SELECT 1 FROM commerce.notification_items i
          WHERE i.delivery_id=? AND a.order_id=CAST(i.payload->>'orderId' AS uuid))
        ORDER BY a.order_id FOR UPDATE
        """,
        id);
    var deliveries =
        jdbc.queryForList(
            "SELECT status,lease_token FROM commerce.notification_deliveries WHERE id=? FOR UPDATE SKIP LOCKED",
            id);
    if (deliveries.isEmpty() || !ready(Target.DELIVERY, id, now)) return null;
    var delivery = deliveries.getFirst();
    boolean terminal =
        List.of("SENT", "REVIEW_REQUIRED").contains(delivery.get("status"))
            && delivery.get("lease_token") == null;
    boolean protectedItem =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                """
        SELECT EXISTS (SELECT 1 FROM commerce.notification_items i
          LEFT JOIN commerce.orders o ON o.id=CAST(i.payload->>'orderId' AS uuid)
          LEFT JOIN commerce.order_addresses a ON a.order_id=o.id
          WHERE i.delivery_id=? AND (o.id IS NULL OR a.id IS NULL OR o.closed_at IS NULL OR o.closed_at>?
            OR o.status NOT IN ('CANCELLED','REFUNDED','PURCHASE_CONFIRMED') OR a.hold_reason IS NOT NULL
            OR EXISTS (SELECT 1 FROM commerce.order_refund_requests r WHERE r.order_id=o.id
              AND r.status IN ('PENDING_SELLER','APPROVED','REFUNDING','FAILED_REVIEW'))))
        OR EXISTS (SELECT 1 FROM commerce.notification_attempts WHERE delivery_id=? AND status='RUNNING')
        """,
                Boolean.class,
                id,
                time(now.minus(PERIOD)),
                id));
    if (!terminal || protectedItem) {
      record(Target.DELIVERY, id, "HELD", now, now.plus(Duration.ofDays(1)));
      return "HELD";
    }
    // 본문 삭제 후에도 업무 멱등키를 남겨 늦은 요청의 재생성을 막는다.
    NotificationRetentionGuard.lockItems(jdbc, id);
    jdbc.update(
        """
        INSERT INTO commerce.notification_retired_keys(source_service,kind,business_ref,recipient_kind,recipient_id,payload_hash,retired_at)
        SELECT source_service,kind,business_ref,recipient_kind,recipient_id,sha256(convert_to(payload::text,'UTF8')),?
        FROM commerce.notification_items WHERE delivery_id=?
        ON CONFLICT(source_service,kind,business_ref,recipient_kind,recipient_id) DO NOTHING
        """,
        time(now),
        id);
    jdbc.update("DELETE FROM commerce.notification_attempts WHERE delivery_id=?", id);
    jdbc.update("DELETE FROM commerce.notification_mock_receipts WHERE delivery_id=?", id);
    jdbc.update("DELETE FROM commerce.notification_items WHERE delivery_id=?", id);
    jdbc.update("DELETE FROM commerce.notification_deliveries WHERE id=?", id);
    record(Target.DELIVERY, id, "PURGED", now, now.plus(Duration.ofDays(1)));
    return "PURGED";
  }

  private boolean ready(Target target, UUID id, Instant now) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT NOT EXISTS(SELECT 1 FROM commerce.retention_checks
          WHERE target_kind=? AND target_id=? AND next_run_at>?)
        """,
            Boolean.class,
            target.name(),
            id,
            time(now)));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void failed(Target target, UUID id, Instant now) {
    TransactionLimits.apply(jdbc);
    record(target, id, "FAILED", now, now.plusSeconds(60));
  }

  private void record(Target target, UUID id, String result, Instant now, Instant next) {
    jdbc.update(
        """
        INSERT INTO commerce.retention_checks(target_kind,target_id,result,checked_at,next_run_at) VALUES (?,?,?,?,?)
        ON CONFLICT(target_kind,target_id) DO UPDATE SET result=EXCLUDED.result,checked_at=EXCLUDED.checked_at,next_run_at=EXCLUDED.next_run_at
        """,
        target.name(),
        id,
        result,
        time(now),
        time(next));
    jdbc.update(
        "INSERT INTO commerce.retention_history(id,target_kind,target_id,result,checked_at) VALUES (?,?,?,?,?)",
        UUID.randomUUID(),
        target.name(),
        id,
        result,
        time(now));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void hold(UUID id, String reason, Instant now) {
    TransactionLimits.apply(jdbc);
    if (jdbc.queryForList("SELECT id FROM commerce.orders WHERE id=? FOR UPDATE", id).isEmpty())
      throw new IllegalArgumentException("Order not found");
    var addresses =
        jdbc.queryForList(
            "SELECT masked_at FROM commerce.order_addresses WHERE order_id=? FOR UPDATE", id);
    if (addresses.isEmpty()) throw new IllegalStateException("Order address unavailable");
    jdbc.update(
        "UPDATE commerce.order_addresses SET hold_reason=?,updated_at=?,version=version+1 WHERE order_id=?",
        reason,
        time(now),
        id);
    record(Target.ORDER, id, reason == null ? "HOLD_RELEASED" : "HOLD_PLACED", now, now);
    // 보류 해제 뒤 연결 발송도 다음 실행에서 다시 검사한다.
    jdbc.update(
        """
        UPDATE commerce.retention_checks SET next_run_at=? WHERE target_kind='DELIVERY' AND target_id IN
          (SELECT delivery_id FROM commerce.notification_items WHERE payload->>'orderId'=?)
        """,
        time(now),
        id.toString());
  }

  private static OffsetDateTime time(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }
}
