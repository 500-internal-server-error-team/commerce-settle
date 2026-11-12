package com.commercesettle.payment.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class QuarantineMetricsTest {
  @Test
  @DisplayName("scrape는 DB를 조회하지 않고 수집 실패에도 이전 건수와 관측 시각을 보존한다")
  void retainsSnapshotAndReportsFailureWithoutDatabaseWorkOnScrape() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), eq("payment.v1"), eq("payment.v1")))
        .thenReturn(
            List.of(row("ORIGINAL", "", 2, 2, 0, 1000), row("DLT", "RETRY", 2, 0, 310, 1000)))
        .thenThrow(new DataAccessResourceFailureException("test-only database failure"));
    var registry = new SimpleMeterRegistry();
    try (var metrics = new QuarantineMetrics(jdbc, registry, "payment.v1", 15000)) {
      metrics.refresh();
      for (int i = 0; i < 3; i++) {
        assertThat(registry.get("payment.quarantine.originals").gauge().value()).isEqualTo(2);
        assertThat(
                registry
                    .get("payment.quarantine.delivery.records")
                    .tags("kind", "DLT", "status", "RETRY")
                    .gauge()
                    .value())
            .isEqualTo(2);
      }
      verify(jdbc, times(1)).queryForList(anyString(), eq("payment.v1"), eq("payment.v1"));
      metrics.refresh();
      assertThat(registry.get("payment.quarantine.collection.success").gauge().value()).isZero();
      assertThat(registry.get("payment.quarantine.originals").gauge().value()).isEqualTo(2);
      assertThat(registry.get("payment.quarantine.observed.timestamp.seconds").gauge().value())
          .isEqualTo(1000);
      metrics.refresh();
      assertThat(
              registry
                  .get("payment.quarantine.delivery.overdue.seconds")
                  .tag("kind", "DLT")
                  .gauge()
                  .value())
          .isEqualTo(310);
    } finally {
      registry.close();
    }
  }

  private static Map<String, Object> row(
      String kind, String status, long records, long recent, double age, double observed) {
    return Map.of(
        "kind",
        kind,
        "status",
        status,
        "records",
        records,
        "recent",
        recent,
        "age",
        age,
        "observed",
        observed);
  }
}
