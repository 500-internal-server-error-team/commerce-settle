package com.commercesettle.settlement.infrastructure.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "settlement.reconciliation.enabled", havingValue = "true")
public class ReconciliationMetrics {
  private final JdbcTemplate jdbc;
  private volatile double defects = Double.NaN;
  private volatile double unavailable = Double.NaN;
  private volatile double waitingAge = Double.NaN;
  private volatile double observed;
  private volatile double lastSuccess = Double.NaN;

  public ReconciliationMetrics(DataSource source, MeterRegistry meters) {
    jdbc = new JdbcTemplate(source);
    jdbc.setQueryTimeout(5);
    Gauge.builder(
            "commerce_settle_reconciliation_last_success_timestamp_seconds",
            this,
            x -> x.lastSuccess)
        .register(meters);
    Gauge.builder("commerce_settle_reconciliation_defects", this, x -> x.defects).register(meters);
    Gauge.builder("commerce_settle_reconciliation_unverified", this, x -> x.unavailable)
        .register(meters);
    Gauge.builder("commerce_settle_reconciliation_wait_age_seconds", this, x -> x.waitingAge)
        .register(meters);
    Gauge.builder(
            "commerce_settle_reconciliation_observed_timestamp_seconds", this, x -> x.observed)
        .register(meters);
  }

  @Scheduled(fixedDelay = 15000)
  public void refresh() {
    try {
      defects =
          jdbc.queryForObject(
              "SELECT (SELECT count(*) FROM settlement.reconciliation_events WHERE status='DEFECT')+(SELECT coalesce(sum(count),0) FROM settlement.reconciliation_checks WHERE status='DEFECT')",
              Double.class);
      unavailable =
          jdbc.queryForObject(
              "SELECT count(*) FROM settlement.reconciliation_runs WHERE last_error IS NOT NULL",
              Double.class);
      waitingAge =
          jdbc.queryForObject(
              "SELECT greatest(coalesce((SELECT max(extract(epoch FROM clock_timestamp()-first_seen_at)) FROM settlement.reconciliation_events WHERE status IN ('WAIT_PUBLICATION','WAIT_DELIVERY','WAIT_APPLICATION','QUARANTINED','IN_PROGRESS')),0),coalesce((SELECT max(extract(epoch FROM clock_timestamp()-nullif(evidence->>'oldestCreatedAt','')::timestamptz)) FROM settlement.reconciliation_checks WHERE status='IN_PROGRESS'),0))",
              Double.class);
      lastSuccess =
          jdbc.queryForObject(
              "SELECT coalesce(extract(epoch FROM max(completed_at)),0) FROM settlement.reconciliation_runs",
              Double.class);
      observed = System.currentTimeMillis() / 1000.0;
    } catch (RuntimeException e) {
      defects = Double.NaN;
      unavailable = Double.NaN;
      waitingAge = Double.NaN;
      observed = 0;
      lastSuccess = Double.NaN;
    }
  }
}
