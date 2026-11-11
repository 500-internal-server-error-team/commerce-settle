package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.application.port.PaymentIntegrityReader;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true, timeout = 10)
public class JdbcPaymentIntegrityReader implements PaymentIntegrityReader {
  private static final String SOURCE =
      """
    SELECT a.id,a.business_key,a.provider_id,'APPROVE' AS operation,a.amount,a.status,a.external_ref,
      NULL::uuid AS original_id,a.created_at FROM payment.payment_attempts a
    UNION ALL
    SELECT r.id,r.business_key,a.provider_id,'REFUND',r.external_amount,CASE WHEN r.status='DECLINED' AND r.external_status NOT IN ('SUCCEEDED','DECLINED') THEN 'CANCELLED' ELSE r.external_status END,r.external_ref,
      a.external_ref AS original_id,r.created_at FROM payment.refunds r
      LEFT JOIN LATERAL (SELECT provider_id,external_ref FROM payment.payment_attempts
       WHERE payment_id=r.payment_id AND status='APPROVED' ORDER BY attempt_no DESC LIMIT 1) a ON true
      WHERE r.external_amount>0
    """;
  private final JdbcTemplate jdbc;

  public JdbcPaymentIntegrityReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Instant now() {
    return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
  }

  private org.springframework.jdbc.core.RowMapper<Row> mapping() {
    return (rs, n) ->
        new Row(
            rs.getObject("id", UUID.class),
            rs.getObject("business_key", UUID.class),
            rs.getString("provider_id"),
            rs.getString("operation"),
            rs.getLong("amount"),
            rs.getString("status"),
            rs.getObject("external_ref", UUID.class),
            rs.getObject("original_id", UUID.class));
  }

  @Override
  public long count(Instant cutoff, Instant snapshot) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM (" + SOURCE + ") s WHERE created_at<=? AND created_at<=?",
        Long.class,
        cutoff.atOffset(ZoneOffset.UTC),
        snapshot.atOffset(ZoneOffset.UTC));
  }

  @Override
  public List<Row> page(Instant cutoff, Instant snapshot, UUID after, int size) {
    return jdbc.query(
        "SELECT * FROM ("
            + SOURCE
            + ") s WHERE created_at<=? AND created_at<=? AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT ?",
        mapping(),
        cutoff.atOffset(ZoneOffset.UTC),
        snapshot.atOffset(ZoneOffset.UTC),
        after,
        after,
        size);
  }

  @Override
  public List<Row> byKeys(List<UUID> keys, Instant cutoff, Instant snapshot) {
    if (keys.isEmpty()) return List.of();
    var args = new java.util.ArrayList<Object>(keys);
    args.add(cutoff.atOffset(ZoneOffset.UTC));
    args.add(snapshot.atOffset(ZoneOffset.UTC));
    return jdbc.query(
        "SELECT * FROM ("
            + SOURCE
            + ") s WHERE business_key IN ("
            + String.join(",", java.util.Collections.nCopies(keys.size(), "?"))
            + ") AND created_at<=? AND created_at<=?",
        mapping(),
        args.toArray());
  }

  @Override
  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,
      timeout = 10)
  public Map<String, Long> checks() {
    return Map.ofEntries(
        Map.entry(
            "paymentInProgress",
            count(
                "SELECT count(*) FROM payment.payments WHERE status IN ('PENDING','PROCESSING','UNKNOWN')")),
        Map.entry(
            "refundInProgress",
            count(
                "SELECT count(*) FROM payment.refunds WHERE status NOT IN ('COMPLETED','DECLINED')")),
        Map.entry(
            "financialInboxPending",
            count(
                "SELECT count(*) FROM payment.inbox WHERE status<>'APPLIED' AND event_type IN ('OrderPaymentRequested','RefundApproved','RefundReservationAccepted','RefundFinancialCompleted','MileageAccrualScheduled','MileageAccrualConfirmed')")),
        Map.entry(
            "financialOutboxPending",
            count(
                "SELECT count(*) FROM payment.outbox WHERE event_type IN ('PaymentConfirmed','RefundFinancialCompleted') AND status<>'PUBLISHED'")),
        Map.entry(
            "composition",
            count(
                "SELECT count(*) FROM payment.payments WHERE total_amount<>external_amount+mileage_amount OR settlement_base_amount<>total_amount+platform_coupon_amount OR settlement_base_amount<>fee_amount+seller_net_amount")),
        Map.entry(
            "mileageBalance",
            count(
                """
      SELECT count(*) FROM payment.mileage_wallets w WHERE w.balance<>coalesce((SELECT sum(CASE e.kind WHEN 'SEED' THEN e.amount WHEN 'DEBIT' THEN -e.amount WHEN 'RESTORE' THEN e.amount WHEN 'ACCRUAL' THEN e.amount ELSE 0 END)
       FROM payment.mileage_entries e WHERE e.wallet_id=w.id),0)
      """)),
        Map.entry(
            "mileageReservations",
            count(
                """
      SELECT count(*) FROM payment.mileage_wallets w WHERE w.reserved_amount<>coalesce((SELECT sum(r.amount)
       FROM payment.mileage_reservations r WHERE r.wallet_id=w.id AND r.status='RESERVED'),0)
      """)),
        Map.entry(
            "mileageReferences",
            count(
                """
      SELECT (SELECT count(*) FROM payment.mileage_entries e LEFT JOIN payment.mileage_wallets w ON w.id=e.wallet_id
       LEFT JOIN payment.payments p ON p.id=e.payment_id LEFT JOIN payment.refunds r ON r.id=e.refund_id
       WHERE w.id IS NULL OR (e.payment_id IS NOT NULL AND (p.id IS NULL OR p.buyer_id<>w.buyer_id))
       OR (e.kind IN ('DEBIT','RESTORE') AND (p.id IS NULL OR e.business_ref IS DISTINCT FROM e.payment_id OR e.amount IS DISTINCT FROM p.mileage_amount))
       OR (e.kind='SEED' AND (e.payment_id IS NOT NULL OR e.business_ref IS DISTINCT FROM e.wallet_id))
       OR (e.kind<>'RESTORE' AND e.refund_id IS NOT NULL)
       OR (e.kind='ACCRUAL' AND (p.id IS NULL OR e.business_ref IS DISTINCT FROM e.payment_id OR NOT EXISTS (
        SELECT 1 FROM payment.mileage_accruals a WHERE a.payment_id=e.payment_id AND a.status='AVAILABLE'
         AND a.accrual_amount=e.amount AND a.buyer_id=w.buyer_id)))
       OR (e.kind='RESTORE' AND (r.id IS NULL OR r.payment_id IS DISTINCT FROM e.payment_id
        OR r.mileage_amount<>e.amount OR r.mileage_restored_at IS NULL)) ) + (SELECT count(*) FROM payment.mileage_reservations r
       LEFT JOIN payment.mileage_wallets w ON w.id=r.wallet_id LEFT JOIN payment.payments p ON p.id=r.payment_id
       WHERE w.id IS NULL OR p.id IS NULL OR p.buyer_id<>w.buyer_id)
      """)),
        Map.entry(
            "mileageAccruals",
            count(
                """
      SELECT count(*) FROM payment.mileage_accruals a LEFT JOIN payment.payments p ON p.id=a.payment_id
      WHERE p.id IS NULL OR p.status<>'CONFIRMED' OR p.order_id<>a.order_id OR p.buyer_id<>a.buyer_id
       OR p.coupon_amount<>0 OR p.external_amount<>a.base_amount
       OR (a.status='AVAILABLE' AND ((SELECT count(*) FROM payment.mileage_entries e
         WHERE e.payment_id=a.payment_id AND e.kind='ACCRUAL' AND e.amount=a.accrual_amount)<>1
        OR EXISTS (SELECT 1 FROM payment.refunds r WHERE r.payment_id=a.payment_id AND r.status<>'DECLINED')))
       OR (a.status<>'AVAILABLE' AND EXISTS (SELECT 1 FROM payment.mileage_entries e
         WHERE e.payment_id=a.payment_id AND e.kind='ACCRUAL'))
       OR (a.status='CANCELLED' AND NOT EXISTS (SELECT 1 FROM payment.refunds r
         WHERE r.payment_id=a.payment_id AND r.status='COMPLETED'))
      """)),
        Map.entry(
            "confirmedAttempt",
            count(
                """
      SELECT count(*) FROM payment.payments p WHERE p.status='CONFIRMED' AND p.external_amount>0
       AND (SELECT count(*) FROM payment.payment_attempts a WHERE a.payment_id=p.id AND a.status='APPROVED' AND a.amount=p.external_amount)<>1
      """)),
        Map.entry(
            "refundAmounts",
            count(
                """
      SELECT count(*) FROM payment.refunds r LEFT JOIN payment.payments p ON p.id=r.payment_id
      WHERE p.id IS NULL OR r.external_amount<>p.external_amount OR r.mileage_amount<>p.mileage_amount
       OR (r.status IN ('INTERNAL_PENDING','COMPLETED') AND r.mileage_amount>0 AND
        (SELECT count(*) FROM payment.mileage_entries e WHERE e.refund_id=r.id AND e.payment_id=r.payment_id AND e.kind='RESTORE' AND e.amount=p.mileage_amount)<>1)
      """)),
        Map.entry(
            "confirmedMileage",
            count(
                """
      SELECT count(*) FROM payment.payments p WHERE p.status='CONFIRMED' AND p.mileage_amount>0
       AND (SELECT count(*) FROM payment.mileage_entries e WHERE e.payment_id=p.id AND e.kind='DEBIT' AND e.amount=p.mileage_amount)<>1
      """)));
  }

  private long count(String sql) {
    return jdbc.queryForObject(sql, Long.class);
  }
}
