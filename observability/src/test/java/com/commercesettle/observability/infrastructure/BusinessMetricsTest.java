package com.commercesettle.observability.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class BusinessMetricsTest {
  @Test
  void deliverySourcesOnlyAcceptOwnerSchemas() {
    assertThat(BusinessMetrics.deliverySources("commerce"))
        .containsOnlyKeys("commerce.outbox.published", "commerce.inbox.applied");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> BusinessMetrics.deliverySources("public; DROP TABLE"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void failedCohortCollectionKeepsTimestampAndScrapesDoNotQuery() throws Exception {
    var source = mock(DataSource.class);
    var connection = mock(Connection.class);
    var statement = mock(PreparedStatement.class);
    var rows = mock(ResultSet.class);
    when(source.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next()).thenReturn(true, false);
    when(rows.getString("reason")).thenReturn("purchase_pending");
    when(rows.getDouble("observed")).thenReturn(1234d);
    when(rows.getDouble("records")).thenReturn(3d);
    try (var metrics =
        new BusinessMetrics(source, null, Map.of(), Map.of("payout.total", "SELECT 1"))) {
      metrics.collect();
      verifyNoInteractions(source);
      metrics.refresh();
      assertThat(value(metrics, "cohort_records")).isEqualTo(3);
      when(statement.executeQuery()).thenThrow(new SQLException("timeout"));
      metrics.refresh();
      assertThat(value(metrics, "cohort_observed_timestamp_seconds")).isEqualTo(1234);
      metrics.collect();
      verify(source, times(2)).getConnection();
    }
  }

  @Test
  void failedOrPartialRefreshKeepsLastCompleteSnapshotAndScrapeNeverQueriesDb() throws Exception {
    var dataSource = mock(DataSource.class);
    var connection = mock(Connection.class);
    var statement = mock(PreparedStatement.class);
    var rows = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next()).thenReturn(true, true, true, false);
    when(rows.getInt("window_seconds")).thenReturn(60, 300, 900);
    when(rows.getDouble("observed")).thenReturn(1234d);
    when(rows.getDouble("completed")).thenReturn(2d);
    when(rows.getDouble("valid")).thenReturn(1d);
    var registry = new PrometheusRegistry();
    try (var metrics =
        new BusinessMetrics(dataSource, registry, Map.of("test.completed", "SELECT 1"))) {
      assertThat(value(metrics, "completions")).isNaN();
      verifyNoInteractions(dataSource);
      metrics.refresh();
      assertThat(value(metrics, "completions")).isEqualTo(2);
      assertThat(value(metrics, "duration_missing")).isEqualTo(1);
      verify(connection).setReadOnly(true);
      verify(statement).setQueryTimeout(2);
      verify(connection).rollback();
      when(statement.executeQuery()).thenThrow(new SQLException("timeout"));
      metrics.refresh();
      assertThat(value(metrics, "observed_timestamp_seconds")).isEqualTo(1234);
      assertThat(value(metrics, "completions")).isEqualTo(2);
      doReturn(rows).when(statement).executeQuery();
      when(rows.next()).thenReturn(true, false);
      when(rows.getInt("window_seconds")).thenReturn(60);
      when(rows.getDouble("observed")).thenReturn(9999d);
      metrics.refresh();
      assertThat(value(metrics, "observed_timestamp_seconds")).isEqualTo(1234);
      verify(dataSource, times(3)).getConnection();
      assertThat(registry.scrape().size()).isEqualTo(6);
    }
    assertThat(registry.scrape().size()).isZero();
  }

  private static double value(BusinessMetrics metrics, String name) {
    for (var metric : metrics.collect()) {
      if (metric.getMetadata().getName().equals("commerce_settle_business_" + name))
        return ((GaugeSnapshot) metric).getDataPoints().getFirst().getValue();
    }
    throw new AssertionError(name);
  }
}
