package com.commercesettle.financemock.infrastructure.persistence;

import com.commercesettle.financemock.application.port.ReconciliationReader;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReconciliationReader implements ReconciliationReader {
  private final JdbcTemplate jdbc;

  public JdbcReconciliationReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Instant now() {
    return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
  }

  @Override
  public long count(Instant cutoff, Instant snapshot) {
    requireClosed(cutoff);
    return jdbc.queryForObject(
        "SELECT count(*) FROM finance_mock.mock_transactions WHERE operation='PAYOUT' AND created_at<=? AND created_at<=?",
        Long.class,
        cutoff.atOffset(ZoneOffset.UTC),
        snapshot.atOffset(ZoneOffset.UTC));
  }

  @Override
  public List<Map<String, Object>> payouts(Instant cutoff, Instant snapshot, UUID after, int size) {
    var rows =
        jdbc.queryForList(
            """
      SELECT t.id,t.business_key AS "businessKey",t.amount,t.status,(SELECT a.account_ref FROM finance_mock.mock_accounts a WHERE a.id=t.funding_account_id) AS "fundingAccountRef",t.created_at AS "createdAt",t.succeeded_at AS "succeededAt",
       (SELECT coalesce(sum(c.amount),0)::bigint FROM finance_mock.mock_cash_entries c WHERE c.transaction_id=t.id AND c.kind='PAYOUT_DEBIT' AND c.created_at<=?) AS "debitAmount",
       (SELECT count(*) FROM finance_mock.mock_cash_entries c WHERE c.transaction_id=t.id AND c.kind='PAYOUT_DEBIT' AND c.created_at<=?) AS "debitCount"
      FROM finance_mock.mock_transactions t WHERE t.operation='PAYOUT' AND t.created_at<=? AND t.created_at<=?
       AND (?::uuid IS NULL OR t.id>?::uuid) ORDER BY t.id LIMIT ?
      """,
            cutoff.atOffset(ZoneOffset.UTC),
            cutoff.atOffset(ZoneOffset.UTC),
            cutoff.atOffset(ZoneOffset.UTC),
            snapshot.atOffset(ZoneOffset.UTC),
            after,
            after,
            size);
    for (var row : rows) {
      for (String key : List.of("createdAt", "succeededAt")) {
        Object value = row.get(key);
        if (value instanceof java.sql.Timestamp t)
          row.put(key, t.toInstant().atZone(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime());
      }
    }
    return rows;
  }

  @Override
  public long paymentCount(Instant cutoff, Instant snapshot) {
    requireClosed(cutoff);
    return jdbc.queryForObject(
        "SELECT count(*) FROM finance_mock.mock_transactions WHERE operation<>'PAYOUT' AND created_at<=? AND created_at<=?",
        Long.class,
        cutoff.atOffset(ZoneOffset.UTC),
        snapshot.atOffset(ZoneOffset.UTC));
  }

  @Override
  public List<Map<String, Object>> payments(
      Instant cutoff, Instant snapshot, UUID after, int size) {
    return rows(
        jdbc.queryForList(
            """
      SELECT id,provider_id AS "providerId",operation,business_key AS "businessKey",original_id AS "originalId",
      amount,status,created_at AS "createdAt",succeeded_at AS "succeededAt" FROM finance_mock.mock_transactions
      WHERE operation<>'PAYOUT' AND created_at<=? AND created_at<=? AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT ?
      """,
            cutoff.atOffset(ZoneOffset.UTC),
            snapshot.atOffset(ZoneOffset.UTC),
            after,
            after,
            size));
  }

  @Override
  public List<Map<String, Object>> lookup(List<UUID> keys, Instant snapshot) {
    if (keys.isEmpty()) return List.of();
    var params = new java.util.ArrayList<Object>(keys);
    params.add(snapshot.atOffset(ZoneOffset.UTC));
    return rows(
        jdbc.queryForList(
            "SELECT id,provider_id AS \"providerId\",operation,business_key AS \"businessKey\",original_id AS \"originalId\",amount,status,created_at AS \"createdAt\",succeeded_at AS \"succeededAt\" FROM finance_mock.mock_transactions WHERE operation<>'PAYOUT' AND business_key IN ("
                + String.join(",", java.util.Collections.nCopies(keys.size(), "?"))
                + ") AND created_at<=?",
            params.toArray()));
  }

  private List<Map<String, Object>> rows(List<Map<String, Object>> rows) {
    for (var row : rows)
      for (String key : List.of("createdAt", "succeededAt")) {
        Object v = row.get(key);
        if (v instanceof java.sql.Timestamp t)
          row.put(key, t.toInstant().atZone(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime());
      }
    return rows;
  }

  @Override
  public Map<String, Long> integrity() {
    return Map.of(
        "fundingBalance",
        count(
            """
      SELECT count(*) FROM finance_mock.mock_accounts a WHERE a.purpose='PAYOUT'
      AND a.balance<>a.opening_balance+coalesce((SELECT sum(e.amount) FROM finance_mock.mock_cash_entries e WHERE e.account_id=a.id),0)
      """),
        "walletBalance",
        count(
            """
      SELECT count(*) FROM finance_mock.mock_accounts a WHERE a.purpose='WALLET'
      AND a.balance<>a.opening_balance-coalesce((SELECT sum(CASE WHEN t.operation='APPROVE' THEN t.amount ELSE -t.amount END)
       FROM finance_mock.mock_transactions t WHERE t.account_id=a.id AND t.status='SUCCEEDED'),0)
      """),
        "reservationBalance",
        count(
            """
      SELECT count(*) FROM finance_mock.mock_accounts a WHERE a.reserved_amount<>coalesce((SELECT sum(r.amount)
       FROM finance_mock.mock_funds_reservations r WHERE r.account_id=a.id AND r.status='RESERVED'),0)
      """),
        "successfulCash",
        count(
            """
      SELECT count(*) FROM finance_mock.mock_transactions t WHERE t.status='SUCCEEDED' AND
       ((t.operation='PAYOUT' AND (SELECT count(*) FROM finance_mock.mock_cash_entries c WHERE c.transaction_id=t.id AND c.kind='PAYOUT_DEBIT' AND c.amount=-t.amount AND c.account_id=t.funding_account_id)<>1)
       OR (t.operation='APPROVE' AND t.pg_received_at IS NOT NULL AND t.pg_received_amount>0 AND
        (SELECT count(*) FROM finance_mock.mock_cash_entries c WHERE c.transaction_id=t.id AND c.kind='PG_RECEIPT' AND c.amount=t.pg_received_amount AND c.account_id=t.funding_account_id)<>1)
       OR (t.operation='REFUND' AND EXISTS(SELECT 1 FROM finance_mock.mock_transactions o WHERE o.id=t.original_id AND o.pg_received_amount>0)
        AND (SELECT count(*) FROM finance_mock.mock_cash_entries c WHERE c.transaction_id=t.id AND c.kind='REFUND_DEBIT' AND c.amount=-t.amount AND c.account_id=t.funding_account_id)<>1))
      """),
        "cashReference",
        count(
            """
      SELECT count(*) FROM finance_mock.mock_cash_entries c LEFT JOIN finance_mock.mock_transactions t ON t.id=c.transaction_id
       LEFT JOIN finance_mock.mock_accounts a ON a.id=c.account_id
       WHERE t.id IS NULL OR a.id IS NULL OR a.purpose<>'PAYOUT' OR t.status<>'SUCCEEDED'
       OR c.account_id IS DISTINCT FROM t.funding_account_id
       OR (c.kind='PAYOUT_DEBIT' AND (t.operation<>'PAYOUT' OR c.amount<>-t.amount))
       OR (c.kind='PG_RECEIPT' AND (t.operation<>'APPROVE' OR t.pg_received_at IS NULL OR c.amount IS DISTINCT FROM t.pg_received_amount))
       OR (c.kind='REFUND_DEBIT' AND (t.operation<>'REFUND' OR c.amount<>-t.amount OR NOT EXISTS(
        SELECT 1 FROM finance_mock.mock_transactions o WHERE o.id=t.original_id AND o.operation='APPROVE'
         AND o.status='SUCCEEDED' AND o.amount=t.amount AND o.pg_received_amount>0
         AND o.funding_account_id=t.funding_account_id AND o.provider_id=t.provider_id)))
      """));
  }

  private void requireClosed(Instant cutoff) {
    Boolean open =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND pid<>pg_backend_pid() AND xact_start<=? + interval '1 microsecond' AND state<>'idle')",
            Boolean.class,
            cutoff.atOffset(ZoneOffset.UTC));
    if (!Boolean.FALSE.equals(open))
      throw new org.springframework.dao.TransientDataAccessResourceException(
          "Source cutoff has open transactions");
  }

  private long count(String sql) {
    return jdbc.queryForObject(sql, Long.class);
  }
}
