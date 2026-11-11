package com.commercesettle.payment.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.payment.application.port.PaymentProcessingObservation.Stage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.listener.MethodExecutionContext;
import net.ttddyy.dsproxy.listener.MethodExecutionListener;
import net.ttddyy.observation.boot.autoconfigure.DataSourceObservationAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PaymentJdbcObservationIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.3");

  @Test
  @DisplayName("실제 SQL·커밋·롤백을 추적하되 DB 오류의 실패 행과 예외 원문은 export하지 않는다")
  void observesTransactionsWithoutExportingPrivateValues() {
    MockClock ticker = new MockClock();
    PrometheusMeterRegistry meters =
        new PrometheusMeterRegistry(PrometheusConfig.DEFAULT, new PrometheusRegistry(), ticker);
    RecordingExporter exporter = new RecordingExporter();
    try (SdkTracerProvider provider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()) {
      Tracer tracer =
          new OtelTracer(provider.get("payment-test"), new OtelCurrentTraceContext(), event -> {});
      MethodExecutionListener commits =
          new MethodExecutionListener() {
            @Override
            public void beforeMethod(MethodExecutionContext context) {
              if (context.getMethod().getName().equals("commit")) ticker.add(Duration.ofMillis(17));
              if (context.getMethod().getName().equals("rollback"))
                ticker.add(Duration.ofMillis(7));
            }

            @Override
            public void afterMethod(MethodExecutionContext context) {}
          };

      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  DataSourceAutoConfiguration.class,
                  MetricsAutoConfiguration.class,
                  ObservationAutoConfiguration.class,
                  MicrometerTracingAutoConfiguration.class,
                  DataSourceObservationAutoConfiguration.class))
          .withUserConfiguration(PaymentProcessingObservations.class)
          .withBean(MeterRegistry.class, () -> meters)
          .withBean(Clock.class, Clock::systemUTC)
          .withBean(Tracer.class, () -> tracer)
          .withBean(MethodExecutionListener.class, () -> commits)
          .withPropertyValues(
              "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
              "spring.datasource.username=" + POSTGRES.getUsername(),
              "spring.datasource.password=" + POSTGRES.getPassword(),
              "jdbc.includes=CONNECTION,QUERY,FETCH",
              "jdbc.resultset-operations.enabled=false",
              "jdbc.datasource-proxy.include-parameter-values=false",
              "management.metrics.tags.application=payment",
              "management.metrics.tags.role=worker",
              "management.metrics.distribution.percentiles-histogram.payment.processing=true",
              "management.metrics.distribution.percentiles-histogram.payment.queue.wait=true",
              "management.metrics.distribution.percentiles-histogram.jdbc.query=true")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                DataSource source = context.getBean(DataSource.class);
                JdbcTemplate jdbc = new JdbcTemplate(source);
                TransactionTemplate transaction =
                    new TransactionTemplate(new DataSourceTransactionManager(source));
                PaymentProcessingObservations observations =
                    context.getBean(PaymentProcessingObservations.class);
                ObservationRegistry registry = context.getBean(ObservationRegistry.class);
                jdbc.execute(
                    "CREATE TABLE observation_test (id integer PRIMARY KEY, payload text CHECK(length(payload)<24))");
                exporter.spans.clear();

                observations.observe(
                    Stage.INBOX_APPLY,
                    () ->
                        transaction.executeWithoutResult(
                            status -> {
                              jdbc.update(
                                  "INSERT INTO observation_test VALUES (?, ?)",
                                  1,
                                  "private-bind-value");
                              ticker.add(Duration.ofMillis(5));
                            }));
                assertThat(
                        meters
                            .get("payment.processing")
                            .tag("stage", "inbox.apply")
                            .tag("error", "none")
                            .timer()
                            .totalTime(TimeUnit.MILLISECONDS))
                    .isEqualTo(22);

                assertThatThrownBy(
                        () ->
                            observations.observe(
                                Stage.INBOX_APPLY,
                                () ->
                                    transaction.executeWithoutResult(
                                        status -> {
                                          jdbc.update(
                                              "INSERT INTO observation_test VALUES (?, ?)",
                                              2,
                                              "private-rollback-value");
                                          throw new IllegalStateException("private-error-message");
                                        })))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("private-error-message");
                assertThat(
                        meters
                            .get("payment.processing")
                            .tag("stage", "inbox.apply")
                            .tag("error", "ObservationFailure")
                            .timer()
                            .totalTime(TimeUnit.MILLISECONDS))
                    .isEqualTo(7);

                assertThatThrownBy(
                        () ->
                            observations.observe(
                                Stage.INBOX_APPLY,
                                () ->
                                    transaction.executeWithoutResult(
                                        status ->
                                            jdbc.update(
                                                "INSERT INTO observation_test VALUES (?, ?)",
                                                3,
                                                "private-failing-row-containing-account"))))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("private-failing-row-containing-account");
                assertThat(
                        jdbc.queryForObject("SELECT count(*) FROM observation_test", Integer.class))
                    .isEqualTo(1);
                assertThat(registry.getCurrentObservation()).isNull();
                assertThat(tracer.currentSpan()).isNull();
                assertThat(
                        meters.get("jdbc.query").tag("stage", "inbox.apply").timers().stream()
                            .mapToLong(io.micrometer.core.instrument.Timer::count)
                            .sum())
                    .isEqualTo(3);
                assertThat(exporter.spans).isNotEmpty();
                assertThat(exporter.spans)
                    .anySatisfy(
                        span ->
                            assertThat(span.getAttributes().toString())
                                .contains("INSERT INTO observation_test"));
                assertThat(exporter.spans)
                    .allSatisfy(
                        span ->
                            assertThat(
                                    List.of(
                                            span.getName(),
                                            span.getAttributes(),
                                            span.getStatus(),
                                            span.getEvents(),
                                            span.getLinks())
                                        .toString())
                                .doesNotContain(
                                    "private-bind-value",
                                    "private-rollback-value",
                                    "private-error-message",
                                    "private-failing-row-containing-account"));
                observations.queueWait(
                    com.commercesettle.payment.application.port.PaymentProcessingObservation.Queue
                        .INBOX,
                    java.time.Instant.now().minusSeconds(2),
                    1);
                assertThat(meters.scrape())
                    .contains(
                        "payment_processing_seconds_bucket{",
                        "payment_queue_wait_seconds_bucket{",
                        "jdbc_query_seconds_bucket{",
                        "stage=\"inbox.apply\"",
                        "role=\"worker\"")
                    .doesNotContain("private-bind-value", "event_id=", "payment_id=", "job_id=");
              });
    }
  }

  static final class RecordingExporter implements SpanExporter {
    private final List<SpanData> spans = new ArrayList<>();

    @Override
    public CompletableResultCode export(Collection<SpanData> batch) {
      spans.addAll(batch);
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }
  }
}
