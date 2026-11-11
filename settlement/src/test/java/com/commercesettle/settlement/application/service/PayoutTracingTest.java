package com.commercesettle.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercesettle.observability.AsyncObservation.Timing;
import com.commercesettle.observability.infrastructure.MicrometerAsyncObservation;
import com.commercesettle.settlement.application.dto.PayoutWorkerSettings;
import com.commercesettle.settlement.application.port.PayoutGateway;
import com.commercesettle.settlement.domain.repository.PayoutJobRepository;
import com.commercesettle.settlement.domain.repository.PayoutJobRepository.LeaseKind;
import com.commercesettle.settlement.domain.repository.PayoutJobRepository.LeasedJob;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PayoutTracingTest {
  @Test
  void restoresLeasedParentForActualWorkerOnAnotherExecutorAndDoesNotLeak() throws Exception {
    var exported = new CopyOnWriteArrayList<SpanData>();
    var exporter =
        new SpanExporter() {
          public CompletableResultCode export(Collection<SpanData> spans) {
            exported.addAll(spans);
            return CompletableResultCode.ofSuccess();
          }

          public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
          }

          public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
          }
        };
    try (var provider =
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        var executor = Executors.newSingleThreadExecutor()) {
      var otel = provider.get("payout-worker-test");
      Tracer tracer = new OtelTracer(otel, new OtelCurrentTraceContext(), event -> {});
      var propagator =
          new OtelPropagator(
              ContextPropagators.create(W3CTraceContextPropagator.getInstance()), otel);
      var registry = ObservationRegistry.create();
      registry
          .observationConfig()
          .observationHandler(
              new ObservationHandler.FirstMatchingCompositeObservationHandler(
                  new PropagatingReceiverTracingObservationHandler<>(tracer, propagator),
                  new DefaultTracingObservationHandler(tracer)));
      var meters = new SimpleMeterRegistry();
      try {
        var observations =
            new MicrometerAsyncObservation(registry, tracer, propagator, meters, Clock.systemUTC());
        var parent = tracer.nextSpan().name("batch-transaction").start();
        String carrier;
        try (var ignored = tracer.withSpan(parent)) {
          carrier = observations.capture();
        }
        parent.end();
        var now = Instant.now();
        var leased =
            new LeasedJob(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                2,
                carrier,
                new Timing(
                    now.minusSeconds(20), now.minusSeconds(10), now.minusSeconds(1), "RETRY", 2));
        var jobs = mock(PayoutJobRepository.class);
        var recorder = mock(PayoutResultRecorder.class);
        var gateway = mock(PayoutGateway.class);
        var settings =
            new PayoutWorkerSettings(
                Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(2), 3, 2, 1);
        var worker = new PayoutWorker(jobs, recorder, gateway, settings);
        worker.asyncObservation(observations);
        when(jobs.leaseNext(anyString(), eq(settings.leaseDuration()), eq(LeaseKind.RECOVERY)))
            .thenReturn(Optional.of(leased));
        var request =
            new PayoutGateway.PayoutRequest(
                leased.businessKey(), "fixture-funding", "fixture-recipient", 1000, "normal-v1");
        var result = new PayoutGateway.Result.Succeeded(UUID.randomUUID(), now);
        when(recorder.prepare(leased))
            .thenReturn(Optional.of(new PayoutResultRecorder.Prepared(request, false)));
        when(gateway.lookup(request))
            .thenAnswer(
                call -> {
                  assertThat(tracer.currentSpan().context().traceId())
                      .isEqualTo(parent.context().traceId());
                  return result;
                });
        // Scheduler leases on this thread; run happens after that scope ended on another reused
        // thread.
        var selected = worker.lease(LeaseKind.RECOVERY).orElseThrow();
        executor
            .submit(
                () -> {
                  var previous = tracer.nextSpan().name("executor-existing-context").start();
                  try (var ignored = tracer.withSpan(previous)) {
                    worker.run(selected);
                    assertThat(tracer.currentSpan().context().spanId())
                        .isEqualTo(previous.context().spanId());
                  } finally {
                    previous.end();
                  }
                  assertThat(tracer.currentSpan()).isNull();
                  assertThat(registry.getCurrentObservation()).isNull();
                })
            .get(10, TimeUnit.SECONDS);
        executor.submit(() -> assertThat(tracer.currentSpan()).isNull()).get(10, TimeUnit.SECONDS);
        verify(recorder).record(leased, result);
        var process =
            exported.stream()
                .filter(span -> span.getName().equals("settlement.payout.process"))
                .findFirst()
                .orElseThrow();
        assertThat(process.getParentSpanId()).isEqualTo(parent.context().spanId());
        for (String name :
            List.of(
                "settlement.payout.prepare",
                "settlement.payout.gateway",
                "settlement.payout.record")) {
          var stage =
              exported.stream()
                  .filter(span -> span.getName().equals(name))
                  .findFirst()
                  .orElseThrow();
          assertThat(stage.getTraceId()).isEqualTo(process.getTraceId());
          assertThat(stage.getParentSpanId()).isEqualTo(process.getSpanId());
        }
        assertThat(meters.get("async.queue.backoff").timer().count()).isEqualTo(1);
      } finally {
        meters.close();
      }
    }
  }
}
