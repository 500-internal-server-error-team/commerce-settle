package com.commercesettle.settlement.infrastructure.config;

import com.commercesettle.observability.infrastructure.BusinessMetrics;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.business-metrics.enabled", matchIfMissing = true)
public class BusinessMetricsConfiguration {
  // The original committed PaymentConfirmed is the owner-local source, not the latest retry event.
  private static final String CONFIRMATION =
      """
      (SELECT (i.payload->'payload'->>'confirmedAt')::timestamptz
       FROM settlement.settlement_entries e JOIN settlement.inbox i ON i.event_id=e.source_event_id
       WHERE e.item_id=s.id AND e.reversal_of IS NULL AND e.leg='SELLER'
         AND i.result_ref=s.id AND i.event_type='PaymentConfirmed' AND i.status='APPLIED'
       ORDER BY i.created_at, e.created_at, e.id, i.id LIMIT 1)
      """;

  @Bean(initMethod = "start", destroyMethod = "close")
  BusinessMetrics businessMetrics(
      DataSource source, ObjectProvider<PrometheusMeterRegistry> registries) {
    var registry = registries.getIfAvailable();
    return new BusinessMetrics(
        source, registry == null ? null : registry.getPrometheusRegistry(), sources(), cohorts());
  }

  public static Map<String, String> sources() {
    String facts =
        """
        SELECT DISTINCT ON (s.id) s.created_at AS completed_at, i.order_accepted_at,
            (i.payload->'payload'->>'confirmedAt')::timestamptz AS confirmed_at
        FROM settlement.settlement_items s
        LEFT JOIN settlement.settlement_entries e ON e.item_id=s.id AND e.reversal_of IS NULL
          AND e.leg='SELLER'
        LEFT JOIN settlement.inbox i ON i.event_id=e.source_event_id
          AND i.result_ref=s.id AND i.event_type='PaymentConfirmed' AND i.status='APPLIED'
        WHERE s.created_at >= statement_timestamp() - interval '900 seconds'
          AND s.created_at < statement_timestamp()
        ORDER BY s.id, i.created_at NULLS LAST, e.created_at, e.id, i.id
        """;
    var result = new HashMap<>(BusinessMetrics.deliverySources("settlement"));
    result.put(
        "order.settlement.applied",
        "SELECT completed_at, order_accepted_at started_at FROM (" + facts + ") facts");
    result.put(
        "payment.settlement.applied",
        "SELECT completed_at, confirmed_at started_at FROM (" + facts + ") facts");
    String paid =
        " FROM settlement.payouts p JOIN settlement.settlement_items s ON s.payout_id=p.id"
            + " WHERE p.succeeded_at >= statement_timestamp()-interval '900 seconds'"
            + " AND p.succeeded_at < statement_timestamp()";
    result.put(
        "payment.payout.succeeded",
        "SELECT p.succeeded_at completed_at, " + CONFIRMATION + " started_at" + paid);
    result.put(
        "ready.payout.succeeded",
        "SELECT p.succeeded_at completed_at, s.ready_at started_at" + paid);
    return Map.copyOf(result);
  }

  public static Map<String, String> cohorts() {
    return Map.of(
        "payout.total",
        cohort(false),
        "payout.ready",
        cohort(true),
        "payout.unapplied",
        unapplied());
  }

  private static String unapplied() {
    return """
      WITH latest AS (SELECT * FROM settlement.reconciliation_runs ORDER BY cutoff_at DESC LIMIT 1),
      coverage AS (SELECT coalesce(extract(epoch FROM (SELECT cutoff_at FROM latest)),0) cutoff,
        coalesce(extract(epoch FROM (SELECT snapshot_at FROM latest)),0) snapshot,
        coalesce((SELECT (snapshot_at IS NOT NULL AND stage IN ('PAYOUT','CHECK')
          AND last_error IS NULL AND status <> 'UNVERIFIED'
          AND cutoff_at <= snapshot_at AND snapshot_at <= statement_timestamp())::int FROM latest),0) complete),
      reasons(reason) AS (VALUES ('wait_publication'),('wait_delivery'),('wait_application'),
        ('quarantined'),('defect'),('pending'),('unverified'),('unclassified')),
      facts AS (SELECT e.event_id,
        CASE WHEN e.status IN ('WAIT_PUBLICATION','WAIT_DELIVERY','WAIT_APPLICATION',
          'QUARANTINED','DEFECT','PENDING','UNVERIFIED') THEN lower(e.status) ELSE 'unclassified' END reason,
        (e.body->>'occurredAt')::timestamptz started_at
        FROM latest r JOIN settlement.reconciliation_events e ON e.run_id=r.id
        WHERE e.source='PAYMENT' AND e.status <> 'MATCH' AND e.body->>'eventType'='PaymentConfirmed'
          AND NOT EXISTS (SELECT 1 FROM settlement.settlement_items s WHERE s.payment_id=(e.body->>'paymentId')::uuid))
      SELECT r.reason,
        CASE WHEN c.complete=1 THEN count(f.event_id)::float8 ELSE 'NaN'::float8 END records,
        CASE WHEN c.complete=1 THEN coalesce(max(extract(epoch FROM statement_timestamp()-f.started_at))
          FILTER (WHERE f.started_at <= statement_timestamp()),0)::float8 ELSE 'NaN'::float8 END oldest,
        count(f.event_id) FILTER (WHERE f.started_at IS NULL OR f.started_at > statement_timestamp()) missing,
        extract(epoch FROM statement_timestamp()) observed, c.cutoff, c.snapshot, c.complete
      FROM reasons r LEFT JOIN facts f ON f.reason=r.reason CROSS JOIN coverage c
      GROUP BY r.reason,c.cutoff,c.snapshot,c.complete
      """;
  }

  private static String cohort(boolean ready) {
    String start = ready ? "s.ready_at" : CONFIRMATION;
    return """
        WITH reasons(reason) AS (VALUES ('purchase_pending'), ('refund_pending'), ('batch_wait'),
          ('payout_pending'), ('payout_unknown'), ('payout_declined'), ('refunded')),
        facts AS (SELECT s.id,
          CASE WHEN s.status='REFUNDED' THEN 'refunded'
            WHEN s.status='REFUND_RESERVED' THEN 'refund_pending'
            WHEN p.status='UNKNOWN' THEN 'payout_unknown'
            WHEN p.status='DECLINED' THEN 'payout_declined'
            WHEN s.ready_at IS NULL THEN 'purchase_pending'
            WHEN s.payout_id IS NULL THEN 'batch_wait' ELSE 'payout_pending' END reason,
        """
        + start
        + """
          started_at FROM settlement.settlement_items s
          LEFT JOIN settlement.payouts p ON p.id=s.payout_id
          WHERE s.status <> 'PAID' AND (p.succeeded_at IS NULL OR s.status='REFUNDED')
        """
        + (ready ? " AND s.ready_at IS NOT NULL" : "")
        + """
        ) SELECT r.reason, count(f.id) records,
          coalesce(max(extract(epoch FROM (statement_timestamp()-f.started_at))) FILTER
            (WHERE f.started_at <= statement_timestamp() AND r.reason <> 'refunded'), 0) oldest,
          count(f.id) FILTER (WHERE f.started_at IS NULL OR f.started_at > statement_timestamp()) missing,
          extract(epoch FROM statement_timestamp()) observed, 0 cutoff, 0 snapshot, 1 complete
          FROM reasons r LEFT JOIN facts f ON f.reason=r.reason GROUP BY r.reason
        """;
  }
}
