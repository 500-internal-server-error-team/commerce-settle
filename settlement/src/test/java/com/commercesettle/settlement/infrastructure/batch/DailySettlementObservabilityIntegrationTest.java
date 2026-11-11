package com.commercesettle.settlement.infrastructure.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercesettle.settlement.application.exception.ProductSettlementContextUnavailableException;
import com.commercesettle.settlement.application.port.PayoutGateway;
import com.commercesettle.settlement.application.port.ProductSettlementContextReader;
import com.commercesettle.settlement.application.service.PayoutWorker;
import com.commercesettle.settlement.domain.model.ProductSettlementContext;
import com.commercesettle.settlement.domain.model.SettlementAmounts;
import com.commercesettle.settlement.domain.model.SettlementBatch;
import com.commercesettle.settlement.domain.model.SettlementItem;
import com.commercesettle.settlement.domain.repository.SettlementItemRepository;
import com.commercesettle.settlement.infrastructure.observability.DailySettlementProgress;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.server.address=127.0.0.1",
      "server.address=127.0.0.1",
      "management.tracing.sampling.probability=1.0",
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123",
      "app.internal-auth.senders[service\\:commerce].current=test-commerce-retention-credential-0123456789abcdef",
      "settlement.payout.mock-credential=test-settlement-credential-0123456789abcdef0123",
      "spring.kafka.admin.auto-create=false",
      "settlement.daily-batch.schedule-enabled=false",
      "settlement.payout-worker.enabled=false",
      "settlement.inbox-processor.enabled=false"
    })
@Import(DailySettlementObservabilityIntegrationTest.Tracing.class)
@AutoConfigureObservability
@Testcontainers
@TestMethodOrder(OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DailySettlementObservabilityIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  @Autowired DailySettlementLauncher launcher;
  @Autowired SettlementItemRepository items;
  @Autowired JdbcTemplate jdbc;
  @Autowired MeterRegistry meters;
  @Autowired RecordedSpans spans;
  @LocalManagementPort int managementPort;

  @Autowired Contexts contexts;
  @Autowired PayoutGateway gateway;
  @Autowired PayoutWorker payoutWorker;

  @AfterEach
  void cleanOwnedDatabase() {
    for (String table :
        List.of(
            "settlement_items",
            "settlement_batches",
            "payouts",
            "payout_jobs",
            "settlement_reservations",
            "outbox")) {
      jdbc.update("DELETE FROM settlement." + table);
    }
    contexts.read = () -> {};
    contexts.allowed = true;
  }

  @Test
  @Order(1)
  void scrapesRecentStateAndProgressWithoutTreatingBatchCompletionAsPayoutCompletion()
      throws Exception {
    assertThat(value("settlement.batch.observer.ready")).isEqualTo(1);
    assertThat(value("settlement.batch.last.state")).isZero();
    assertThat(value("settlement.batch.last.started")).isZero();
    assertThat(value("settlement.batch.last.duration")).isNaN();
    LocalDate date = LocalDate.of(2026, 9, 21);
    UUID seller = UUID.randomUUID();
    payable(date, seller);
    spans.completed.clear();

    assertThat(launcher.run(date)).isEqualTo(BatchStatus.COMPLETED);
    assertThat(value("settlement.batch.last.state")).isEqualTo(2);
    assertThat(value("settlement.batch.last.duration")).isGreaterThanOrEqualTo(0);
    assertThat(value("settlement.batch.last.ended")).isGreaterThan(0);
    assertThat(items("read")).isEqualTo(1);
    assertThat(items("written")).isEqualTo(1);
    assertThat(items("skipped")).isZero();
    // COMPLETED means the seller transaction committed a pending payout, not a bank transfer.
    assertThat(jdbc.queryForObject("SELECT status FROM settlement.payouts", String.class))
        .isEqualTo("PENDING");
    String parent =
        jdbc.queryForObject("SELECT trace_parent FROM settlement.payout_jobs", String.class);
    var batch = span("daily-settlement-job");
    var step = span("settle-sellers");
    var sellerSpan = span("settlement.batch.seller");
    assertThat(step.getParentSpanId()).isEqualTo(batch.getSpanId());
    assertThat(sellerSpan.getTraceId()).isEqualTo(batch.getTraceId());
    assertThat(isDescendantOf(sellerSpan, step)).isTrue();
    assertThat(parent)
        .isEqualTo("00-" + sellerSpan.getTraceId() + "-" + sellerSpan.getSpanId() + "-01");
    when(gateway.execute(any()))
        .thenReturn(new PayoutGateway.Result.Succeeded(UUID.randomUUID(), Instant.now()));
    try (var executor = Executors.newSingleThreadExecutor()) {
      assertThat(executor.submit(payoutWorker::runOnce).get(10, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(jdbc.queryForObject("SELECT status FROM settlement.payouts", String.class))
        .isEqualTo("SUCCEEDED");
    assertThat(jdbc.queryForObject("SELECT status FROM settlement.payout_jobs", String.class))
        .isEqualTo("DONE");
    var process = span("settlement.payout.process");
    assertThat(process.getParentSpanId()).isEqualTo(sellerSpan.getSpanId());
    assertThat(process.getTraceId()).isEqualTo(batch.getTraceId());
    for (String name :
        List.of(
            "settlement.payout.prepare", "settlement.payout.gateway", "settlement.payout.record")) {
      assertThat(span(name).getParentSpanId()).isEqualTo(process.getSpanId());
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement.payouts", Long.class))
        .isEqualTo(1);
    String scrape = scrape();
    assertThat(scrape)
        .contains(
            "spring_batch_job_seconds_count",
            "spring_batch_step_seconds_count",
            "COMPLETED",
            "settlement_batch_last_duration_seconds",
            "settlement_batch_step_items");
    assertThat(meters.get("jdbc.query").tag("stage", "settleSellers").timers())
        .anySatisfy(timer -> assertThat(timer.count()).isPositive());
    assertThat(meters.get("jdbc.query").tag("stage", "settlement.batch.seller").timers())
        .anySatisfy(timer -> assertThat(timer.count()).isPositive());
    assertMetricLabelsBounded(date, seller);
    // CI 보고서에 실제 HTTP scrape를 남겨 dashboard 질의를 같은 표본으로 재현할 수 있다.
    System.out.println("BATCH_SCRAPE_BEGIN");
    scrape
        .lines()
        .filter(line -> line.startsWith("spring_batch_") || line.startsWith("settlement_batch_"))
        .forEach(System.out::println);
    System.out.println("BATCH_SCRAPE_END");
  }

  @Test
  @Order(2)
  void exposesSkipsFailureAndActiveExecutionInsteadOfSuccessfulGreen() throws Exception {
    LocalDate skipped = LocalDate.of(2026, 9, 22);
    payable(skipped, UUID.randomUUID());
    contexts.allowed = false;
    assertThat(launcher.run(skipped)).isEqualTo(BatchStatus.COMPLETED);
    assertThat(value("settlement.batch.last.state")).isEqualTo(3);
    assertThat(items("skipped")).isEqualTo(1);
    assertThat(items("written")).isZero();
    assertThat(scrape()).contains("COMPLETED_WITH_SKIPS");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement.payouts", Long.class)).isZero();

    contexts.allowed = true;
    contexts.read =
        () -> {
          throw new ProductSettlementContextUnavailableException("test dependency unavailable");
        };
    LocalDate failed = skipped.plusDays(1);
    assertThat(launcher.run(failed)).isEqualTo(BatchStatus.FAILED);
    assertThat(value("settlement.batch.last.state")).isEqualTo(4);
    assertThat(scrape()).contains("FAILED");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement.payouts", Long.class)).isZero();

    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    contexts.read =
        () -> {
          entered.countDown();
          try {
            if (!release.await(10, TimeUnit.SECONDS))
              throw new IllegalStateException("test latch timeout");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
        };
    try (var executor = Executors.newSingleThreadExecutor()) {
      var result = executor.submit(() -> launcher.run(failed.plusDays(1)));
      try {
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(value("settlement.batch.last.state")).isEqualTo(1);
        assertThat(value("settlement.batch.last.ended")).isZero();
        assertThat(value("settlement.batch.last.duration")).isNaN();
        for (String name : List.of("spring.batch.job.active", "spring.batch.step.active")) {
          LongTaskTimer active = meters.get(name).longTaskTimer();
          assertThat(active.activeTasks()).isEqualTo(1);
          assertThat(active.max(TimeUnit.SECONDS)).isGreaterThan(0);
        }
        assertThat(scrape())
            .contains(
                "spring_batch_job_active_seconds_count", "spring_batch_step_active_seconds_max");
      } finally {
        release.countDown();
      }
      assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(BatchStatus.COMPLETED);
    }
    assertThat(value("settlement.batch.last.state")).isEqualTo(2);
    assertThat(meters.get("spring.batch.job.active").longTaskTimer().activeTasks()).isZero();
    assertThat(meters.get("spring.batch.step.active").longTaskTimer().activeTasks()).isZero();
  }

  @Test
  @Order(3)
  void olderJobsLateStepDoesNotReplaceTheNewestJobsProgress() {
    var registry = new SimpleMeterRegistry();
    try {
      var progress = new DailySettlementProgress(registry, "dailySettlementJob", "settleSellers");
      var olderJob = new JobExecution(1L);
      var newerJob = new JobExecution(2L);
      progress.beforeJob(olderJob);
      progress.beforeJob(newerJob);
      var current = new StepExecution("settleSellers", newerJob, 10L);
      current.setReadCount(3);
      current.setWriteCount(2);
      progress.beforeStep(current);
      var lateOlder = new StepExecution("settleSellers", olderJob, 11L);
      lateOlder.setReadCount(90);
      progress.beforeStep(lateOlder);
      progress.afterJob(olderJob);
      current.setReadCount(4);
      current.setWriteCount(3);
      progress.afterStep(current);
      assertThat(registry.get("settlement.batch.step.items").tag("kind", "read").gauge().value())
          .isEqualTo(4);
      assertThat(registry.get("settlement.batch.step.items").tag("kind", "written").gauge().value())
          .isEqualTo(3);
      assertThat(registry.get("settlement.batch.last.state").gauge().value()).isEqualTo(1);
    } finally {
      registry.close();
    }
  }

  private void payable(LocalDate date, UUID seller) {
    var ready = SettlementBatch.cutoffOf(date).minusSeconds(60);
    var item =
        SettlementItem.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            seller,
            new SettlementAmounts(10000, 0, 10000, 10000, 0, 1500, 8500),
            0,
            ready.minusSeconds(600));
    item.markPayable(ready, ready, ready);
    items.save(item);
  }

  private double value(String name) {
    return meters.get(name).gauge().value();
  }

  private double items(String kind) {
    return meters.get("settlement.batch.step.items").tag("kind", kind).gauge().value();
  }

  private String scrape() {
    return RestClient.create("http://127.0.0.1:" + managementPort)
        .get()
        .uri("/actuator/prometheus")
        .retrieve()
        .body(String.class);
  }

  private SpanData span(String name) {
    return spans.completed.stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow();
  }

  private boolean isDescendantOf(SpanData child, SpanData ancestor) {
    for (int i = 0; i < spans.completed.size(); i++) {
      if (child.getParentSpanId().equals(ancestor.getSpanId())) return true;
      String parent = child.getParentSpanId();
      var next = spans.completed.stream().filter(s -> s.getSpanId().equals(parent)).findFirst();
      if (next.isEmpty()) return false;
      child = next.get();
    }
    return false;
  }

  private void assertMetricLabelsBounded(LocalDate date, UUID seller) {
    meters.getMeters().stream()
        .filter(
            m ->
                m.getId().getName().startsWith("spring.batch.")
                    || m.getId().getName().startsWith("settlement.batch."))
        .forEach(
            m ->
                m.getId()
                    .getTags()
                    .forEach(
                        tag -> {
                          assertThat(tag.getKey()).doesNotContain("execution.id", "seller", "date");
                          assertThat(tag.getValue())
                              .doesNotContain(date.toString(), seller.toString());
                        }));
  }

  static class Contexts implements ProductSettlementContextReader {
    volatile boolean allowed = true;
    volatile Runnable read = () -> {};

    public List<ProductSettlementContext> read(UUID seller, Set<UUID> productIds) {
      read.run();
      return productIds.stream()
          .map(id -> new ProductSettlementContext(id, seller, allowed, "test-account-" + seller, 1))
          .toList();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Tracing {
    @Bean
    @Primary
    Contexts contexts() {
      return new Contexts();
    }

    @Bean
    @Primary
    PayoutGateway testPayoutGateway() {
      return mock(PayoutGateway.class);
    }

    @Bean
    RecordedSpans recordedSpans() {
      return new RecordedSpans();
    }

    @Bean(destroyMethod = "close")
    SdkTracerProvider testSdkProvider(RecordedSpans spans) {
      return SdkTracerProvider.builder()
          .addSpanProcessor(SimpleSpanProcessor.create(spans))
          .build();
    }

    @Bean
    @Primary
    Tracer testTracer(SdkTracerProvider provider) {
      return new OtelTracer(
          provider.get("daily-batch-test"), new OtelCurrentTraceContext(), event -> {});
    }

    @Bean
    @Primary
    Propagator testPropagator(SdkTracerProvider provider) {
      return new OtelPropagator(
          ContextPropagators.create(W3CTraceContextPropagator.getInstance()),
          provider.get("daily-batch-test"));
    }
  }

  static class RecordedSpans implements SpanExporter {
    final List<SpanData> completed = new CopyOnWriteArrayList<>();

    public CompletableResultCode export(Collection<SpanData> spans) {
      completed.addAll(spans);
      return CompletableResultCode.ofSuccess();
    }

    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }
  }
}
