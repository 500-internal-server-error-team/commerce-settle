package com.commercesettle.observability.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercesettle.observability.AsyncObservation.Timing;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
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
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class AsyncObservationTest {
  private static final Instant NOW = Instant.now();
  private final RecordingExporter exporter = new RecordingExporter();
  private final SdkTracerProvider provider =
      SdkTracerProvider.builder()
          .setSampler(Sampler.alwaysOn())
          .addSpanProcessor(SimpleSpanProcessor.create(exporter))
          .build();
  private final io.opentelemetry.api.trace.Tracer otel = provider.get("durable-work-test");
  private final Tracer tracer = new OtelTracer(otel, new OtelCurrentTraceContext(), event -> {});
  private final Propagator propagator =
      new OtelPropagator(ContextPropagators.create(W3CTraceContextPropagator.getInstance()), otel);
  private final ObservationRegistry registry = ObservationRegistry.create();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final MicrometerAsyncObservation observations =
      new MicrometerAsyncObservation(
          registry, tracer, propagator, meters, Clock.fixed(NOW, ZoneOffset.UTC));

  AsyncObservationTest() {
    registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler.FirstMatchingCompositeObservationHandler(
                new PropagatingReceiverTracingObservationHandler<>(tracer, propagator),
                new DefaultTracingObservationHandler(tracer)));
  }

  @AfterEach
  void close() {
    provider.close();
    meters.close();
  }

  @Test
  @DisplayName("저장한 부모를 다른 스레드에서 복원하고 스레드를 재사용해도 거래를 섞지 않는다")
  void restoresPersistedParentAcrossThreadsAndTransactions() throws Exception {
    Span producer = tracer.nextSpan().name("http-order").start();
    String parent;
    try (var ignored = tracer.withSpan(producer)) {
      parent = observations.capture();
    }
    producer.end();
    String eventId = UUID.randomUUID().toString();
    try (var executor = Executors.newSingleThreadExecutor()) {
      executor
          .submit(
              () -> {
                Span scheduler = tracer.nextSpan().name("unrelated-scheduler").start();
                try (var ignored = tracer.withSpan(scheduler)) {
                  observations.run(
                      "payment.approval.process",
                      parent,
                      Map.of("event.id", eventId),
                      new Timing(
                          NOW.minusSeconds(10),
                          NOW.minusSeconds(10),
                          NOW.minusSeconds(10),
                          "PENDING",
                          1),
                      () -> {
                        assertThat(tracer.currentSpan().context().traceId())
                            .isEqualTo(producer.context().traceId());
                        observations.step(
                            "payment.approval.record",
                            () -> assertThat(registry.getCurrentObservation()).isNotNull());
                      });
                  assertThat(tracer.currentSpan().context().spanId())
                      .isEqualTo(scheduler.context().spanId());
                } finally {
                  scheduler.end();
                }
                assertThat(tracer.currentSpan()).isNull();
                assertThat(registry.getCurrentObservation()).isNull();
              })
          .get(5, TimeUnit.SECONDS);
      executor
          .submit(
              () ->
                  observations.run(
                      "other-work",
                      null,
                      Map.of(),
                      null,
                      () ->
                          assertThat(tracer.currentSpan().context().traceId())
                              .isNotEqualTo(producer.context().traceId())))
          .get(5, TimeUnit.SECONDS);
    }
    SpanData work = span("payment.approval.process");
    assertThat(work.getParentSpanId()).isEqualTo(producer.context().spanId());
    assertThat(work.getAttributes().asMap()).containsValue(eventId);
    assertThat(span("payment.approval.process.wait").getParentSpanId())
        .isEqualTo(producer.context().spanId());
    assertThat(span("payment.approval.record").getParentSpanId()).isEqualTo(work.getSpanId());
    assertThat(meters.getMeters())
        .allSatisfy(
            meter ->
                assertThat(meter.getId().getTags())
                    .noneSatisfy(tag -> assertThat(tag.getKey()).endsWith(".id")));
  }

  @Test
  @DisplayName("구기록·손상된 문맥은 scheduler 부모를 상속하지 않는 새 추적이다")
  void invalidParentIsAnIndependentRoot() {
    for (String parent :
        new String[] {
          null, "bad-carrier", "00-00000000000000000000000000000000-1111111111111111-01"
        }) {
      Span unrelated = tracer.nextSpan().start();
      try (var ignored = tracer.withSpan(unrelated)) {
        observations.run(
            "legacy",
            parent,
            Map.of(),
            null,
            () ->
                assertThat(tracer.currentSpan().context().traceId())
                    .isNotEqualTo(unrelated.context().traceId()));
        assertThat(tracer.currentSpan().context().spanId()).isEqualTo(unrelated.context().spanId());
      } finally {
        unrelated.end();
      }
    }
    assertThat(exporter.spans.stream().filter(s -> s.getName().equals("legacy")))
        .allSatisfy(s -> assertThat(s.getParentSpanId()).isEqualTo("0000000000000000"));
  }

  @Test
  @DisplayName("업무 실패는 한 번만 실행하고 exporter에는 예외 원문을 남기지 않는다")
  void preservesOriginalFailureWithoutReexecutionOrSecretExport() {
    AtomicInteger calls = new AtomicInteger();
    RuntimeException failure = new IllegalStateException("password=private-test-value");
    assertThatThrownBy(
            () ->
                observations.run(
                    "failed-work",
                    null,
                    Map.of(),
                    null,
                    () -> {
                      calls.incrementAndGet();
                      throw failure;
                    }))
        .isSameAs(failure);
    assertThat(calls.get()).isEqualTo(1);
    assertThat(span("failed-work").toString()).doesNotContain("private-test-value");
    assertThat(tracer.currentSpan()).isNull();
    assertThat(registry.getCurrentObservation()).isNull();

    Tracer broken = mock(Tracer.class);
    when(broken.withSpan(null)).thenThrow(new IllegalStateException("trace unavailable"));
    var unavailable =
        new MicrometerAsyncObservation(registry, broken, propagator, meters, Clock.systemUTC());
    unavailable.run("setup-failure", null, Map.of(), null, () -> calls.incrementAndGet());
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("retry는 backoff와 도래 지연을 분리하고 lease 회수에 이전 실행 시간을 섞지 않는다")
  void separatesRetryAndLeaseRecoveryTiming() {
    observations.run(
        "retry",
        null,
        Map.of(),
        new Timing(NOW.minusSeconds(60), NOW.minusSeconds(20), NOW.minusSeconds(5), "RETRY", 2),
        () -> {});
    assertThat(seconds("async.queue.wait", "retry", "retry")).isEqualTo(20);
    assertThat(seconds("async.queue.backoff", "retry", "retry")).isEqualTo(15);
    assertThat(seconds("async.queue.ready.delay", "retry", "retry")).isEqualTo(5);

    observations.run(
        "recovery",
        null,
        Map.of(),
        new Timing(NOW.minusSeconds(60), NOW.minusSeconds(20), NOW.minusSeconds(5), "CLAIMED", 2),
        () -> {});
    assertThat(seconds("async.queue.wait", "recovery", "lease-recovery")).isEqualTo(5);
    assertThat(meters.find("async.queue.backoff").tag("queue", "recovery").timer()).isNull();
    observations.run(
        "legacy-retry",
        null,
        Map.of(),
        new Timing(NOW.minusSeconds(60), NOW.minusSeconds(20), NOW.minusSeconds(5), null, 2),
        () -> {});
    assertThat(seconds("async.queue.wait", "legacy-retry", "unknown")).isEqualTo(5);
    observations.run(
        "replay",
        null,
        Map.of(),
        new Timing(NOW.minusSeconds(3600), NOW.minusSeconds(5), NOW.minusSeconds(1), "PENDING", 1),
        () -> {});
    assertThat(seconds("async.queue.wait", "replay", "initial")).isEqualTo(5);
  }

  @Test
  @DisplayName("같은 트랜잭션의 여러 문맥을 commit·rollback 뒤 역순으로 닫는다")
  void keepsContextUntilTransactionCompletionWithoutLeaking() {
    AtomicInteger completions = new AtomicInteger();
    var manager =
        new AbstractPlatformTransactionManager() {
          @Override
          protected Object doGetTransaction() {
            return new Object();
          }

          @Override
          protected void doBegin(Object transaction, TransactionDefinition definition) {}

          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            assertThat(tracer.currentSpan()).isNotNull();
            completions.incrementAndGet();
          }

          @Override
          protected void doRollback(DefaultTransactionStatus status) {
            assertThat(tracer.currentSpan()).isNotNull();
            completions.incrementAndGet();
          }
        };
    TransactionTemplate transaction = new TransactionTemplate(manager);
    for (boolean rollback : List.of(false, true)) {
      transaction.executeWithoutResult(
          status -> {
            var first = observations.start("first", null, Map.of(), null);
            observations.closeAfterTransaction(first);
            var second = observations.start("second", null, Map.of(), null);
            observations.closeAfterTransaction(second);
            assertThat(TransactionSynchronizationManager.hasResource(observations)).isTrue();
            if (rollback) status.setRollbackOnly();
          });
      assertThat(TransactionSynchronizationManager.hasResource(observations)).isFalse();
      assertThat(tracer.currentSpan()).isNull();
      assertThat(registry.getCurrentObservation()).isNull();
    }
    assertThat(completions.get()).isEqualTo(2);
    var first = exporter.spans.stream().filter(span -> span.getName().equals("first")).toList();
    assertThat(first.get(0).getStatus().getStatusCode())
        .isEqualTo(io.opentelemetry.api.trace.StatusCode.UNSET);
    assertThat(first.get(1).getStatus().getStatusCode())
        .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
    assertThat(first.get(1).toString()).contains("TransactionNotCommitted");
  }

  private double seconds(String metric, String queue, String kind) {
    return meters
        .get(metric)
        .tags("queue", queue, "kind", kind)
        .timer()
        .totalTime(TimeUnit.SECONDS);
  }

  private SpanData span(String name) {
    return exporter.spans.stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow();
  }

  private static final class RecordingExporter implements SpanExporter {
    final List<SpanData> spans = new CopyOnWriteArrayList<>();

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
      this.spans.addAll(spans);
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
