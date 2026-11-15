package com.commercesettle.payment.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.observability.AsyncObservation;
import com.commercesettle.observability.infrastructure.AsyncObservationAutoConfiguration;
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
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.listener.MethodExecutionContext;
import net.ttddyy.dsproxy.listener.MethodExecutionListener;
import net.ttddyy.observation.boot.autoconfigure.DataSourceObservationAutoConfiguration;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.jdbc.DataSourcePoolMetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;
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
                  DataSourceObservationAutoConfiguration.class,
                  AsyncObservationAutoConfiguration.class))
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
                            io.micrometer.observation.Observation.createNotStarted(
                                    "http.server.requests", registry)
                                .observe(
                                    () ->
                                        io.micrometer.observation.Observation.createNotStarted(
                                                "spring.kafka.listener", registry)
                                            .observe(
                                                () ->
                                                    observations.observe(
                                                        Stage.INBOX_APPLY,
                                                        () ->
                                                            transaction.executeWithoutResult(
                                                                status ->
                                                                    jdbc.update(
                                                                        "INSERT INTO observation_test VALUES (?, ?)",
                                                                        3,
                                                                        "private-failing-row-containing-account"))))))
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
                assertThat(exporter.spans)
                    .extracting(SpanData::getName)
                    .contains("http.server.requests", "spring.kafka.listener");
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
                    Instant.now().minusSeconds(2),
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

  @ParameterizedTest
  @CsvSource({"commerce,app", "payment,worker"})
  @DisplayName("실제 풀 포화·SQL·후속 대기를 Prometheus와 Grafana에서 구분하고 관측 실패를 거절한다")
  void distinguishesPoolContentionInLiveDashboards(String application, String role) {
    poolContext(application, role)
        .run(
            main ->
                poolContext("payment", "api")
                    .run(
                        peer -> {
                          assertThat(main).hasNotFailed();
                          assertThat(peer).hasNotFailed();
                          try (PoolProbe probe = new PoolProbe(main, peer)) {
                            var root = Path.of(System.getProperty("user.dir")).getParent();
                            var process =
                                new ProcessBuilder(
                                        "python3",
                                        "-B",
                                        "scripts/test_observability.py",
                                        "--jdbc-probe",
                                        Integer.toString(probe.port()),
                                        application,
                                        role)
                                    .directory(root.toFile())
                                    .inheritIO()
                                    .start();
                            try {
                              assertThat(process.waitFor(240, TimeUnit.SECONDS)).isTrue();
                              assertThat(process.exitValue()).isZero();
                            } finally {
                              if (process.isAlive()) {
                                process.destroy();
                                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                                  process.descendants().forEach(ProcessHandle::destroyForcibly);
                                  process.destroyForcibly();
                                  assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
                                }
                              }
                            }
                          }
                        }));
  }

  private ApplicationContextRunner poolContext(String application, String role) {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                MetricsAutoConfiguration.class,
                DataSourcePoolMetricsAutoConfiguration.class,
                ObservationAutoConfiguration.class,
                DataSourceObservationAutoConfiguration.class,
                AsyncObservationAutoConfiguration.class))
        .withBean(Tracer.class, () -> Tracer.NOOP)
        .withBean(
            PrometheusMeterRegistry.class,
            () -> new PrometheusMeterRegistry(PrometheusConfig.DEFAULT))
        .withPropertyValues(
            "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "spring.datasource.username=" + POSTGRES.getUsername(),
            "spring.datasource.password=" + POSTGRES.getPassword(),
            "spring.datasource.hikari.pool-name=" + application + "-" + role,
            "spring.datasource.hikari.maximum-pool-size=2",
            "spring.datasource.hikari.minimum-idle=2",
            "management.metrics.tags.application=" + application,
            "management.metrics.tags.role=" + role,
            "management.metrics.distribution.percentiles-histogram.jdbc.query=true",
            "management.metrics.distribution.percentiles-histogram.async.queue.wait=true",
            "jdbc.includes=CONNECTION,QUERY",
            "jdbc.resultset-operations.enabled=false",
            "jdbc.datasource-proxy.include-parameter-values=false");
  }

  static final class PoolProbe implements AutoCloseable {
    private final Tomcat server = new Tomcat();
    private final Path directory;
    private final DataSource source;
    private final PrometheusMeterRegistry meters;
    private final PrometheusMeterRegistry peerMeters;
    private final AsyncObservation async;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final List<Connection> held = new ArrayList<>();
    private final List<Future<?>> work = new ArrayList<>();
    private volatile String fault = "";

    PoolProbe(AssertableApplicationContext main, AssertableApplicationContext peer)
        throws Exception {
      source = main.getBean(DataSource.class);
      meters = main.getBean(PrometheusMeterRegistry.class);
      peerMeters = peer.getBean(PrometheusMeterRegistry.class);
      async = main.getBean(AsyncObservation.class);
      Instant registered = Instant.now();
      async.run(
          "payment.inbox.process",
          null,
          Map.of(),
          new AsyncObservation.Timing(registered, registered, registered, "PENDING", 0),
          () -> new JdbcTemplate(source).queryForObject("SELECT 1", Integer.class));
      new JdbcTemplate(source).queryForObject("SELECT 1", Integer.class);
      new JdbcTemplate(peer.getBean(DataSource.class)).queryForObject("SELECT 1", Integer.class);
      directory = Files.createTempDirectory("cs-jdbc-probe-");
      server.setBaseDir(directory.toString());
      server.setPort(0);
      server.getConnector();
      var servletContext = server.addContext("", directory.toString());
      Tomcat.addServlet(
          servletContext,
          "jdbc-probe",
          new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
                throws IOException {
              String path = request.getRequestURI();
              try {
                String body = "{}";
                int status = 200;
                switch (path) {
                  case "/metrics", "/peer", "/stale-metrics" -> {
                    boolean mainMetric = path.equals("/metrics");
                    body = (path.equals("/peer") ? peerMeters : meters).scrape();
                    if (mainMetric && fault.equals("down")) status = 503;
                    if (mainMetric && fault.startsWith("missing:"))
                      body =
                          body.lines()
                                  .filter(
                                      line ->
                                          !line.startsWith(
                                              fault.substring("missing:".length()) + "{"))
                                  .collect(Collectors.joining("\n"))
                              + "\n";
                    if (path.equals("/stale-metrics")) {
                      long timestamp = System.currentTimeMillis() - 60000;
                      body =
                          body.lines()
                                  .map(
                                      line ->
                                          line.startsWith("#") || line.isBlank()
                                              ? line
                                              : line + " " + timestamp)
                                  .collect(Collectors.joining("\n"))
                              + "\n";
                    }
                    response.setContentType("text/plain; version=0.0.4");
                  }
                  case "/sql" -> {
                    for (int i = 0; i < 3; i++)
                      new JdbcTemplate(source).execute("SELECT pg_sleep(0.3)");
                  }
                  case "/pool" -> {
                    held.add(source.getConnection());
                    held.add(source.getConnection());
                    for (int i = 0; i < 2; i++) {
                      Instant queued = Instant.now();
                      work.add(
                          executor.submit(
                              () -> {
                                try (var connection = source.getConnection()) {
                                  async.run(
                                      "payment.inbox.process",
                                      null,
                                      Map.of(),
                                      new AsyncObservation.Timing(
                                          queued, queued, queued, "PENDING", 0),
                                      () ->
                                          new JdbcTemplate(
                                                  new SingleConnectionDataSource(connection, true))
                                              .queryForObject("SELECT 1", Integer.class));
                                } catch (SQLException error) {
                                  throw new IllegalStateException(error);
                                }
                              }));
                    }
                  }
                  case "/release" -> release();
                  case "/missing" ->
                      fault = "missing:" + request.getQueryString().substring("metric=".length());
                  case "/down", "/healthy" -> fault = path.substring(1);
                  default -> status = 404;
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                response.setStatus(status);
                response.getOutputStream().write(bytes);
              } catch (Exception error) {
                response.setStatus(500);
              }
            }
          });
      servletContext.addServletMappingDecoded("/*", "jdbc-probe");
      try {
        server.start();
      } catch (Exception failure) {
        server.destroy();
        FileSystemUtils.deleteRecursively(directory);
        executor.shutdownNow();
        throw failure;
      }
    }

    int port() {
      return server.getConnector().getLocalPort();
    }

    private void release() throws Exception {
      for (var connection : held) connection.close();
      held.clear();
      try {
        for (var future : work) future.get(10, TimeUnit.SECONDS);
      } finally {
        work.clear();
      }
    }

    @Override
    public void close() throws Exception {
      try {
        release();
      } finally {
        try {
          server.stop();
        } finally {
          server.destroy();
          FileSystemUtils.deleteRecursively(directory);
          executor.shutdownNow();
          assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
      }
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
