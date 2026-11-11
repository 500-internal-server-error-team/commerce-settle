package com.commercesettle.payment.infrastructure.observability;

import com.commercesettle.payment.application.port.PaymentProcessingObservation;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationView;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 실행 슬롯 안에서 추적을 열고 닫는다. SQL 지표에는 실행 단계만 붙이며 원문·업무 ID는 label로 쓰지 않는다. */
@Component
public final class PaymentProcessingObservations implements PaymentProcessingObservation {
  private final ObservationRegistry observations;
  private final MeterRegistry meters;
  private final Clock clock;

  public PaymentProcessingObservations(
      ObservationRegistry observations, MeterRegistry meters, Clock clock) {
    this.observations = observations;
    this.meters = meters;
    this.clock = clock;
  }

  @Override
  public <T> T observe(Stage stage, Supplier<T> action) {
    String name = stage.name().toLowerCase(Locale.ROOT).replace('_', '.');
    return Observation.createNotStarted("payment.processing", observations)
        .contextualName("payment." + name)
        .lowCardinalityKeyValue("stage", name)
        .observe(action);
  }

  @Override
  public void identify(String name, UUID id) {
    Observation current = observations.getCurrentObservation();
    if (current != null) current.highCardinalityKeyValue(name, id.toString());
  }

  @Override
  public void queueWait(Queue queue, Instant registeredAt, int attempts) {
    if (attempts != 1) return;
    Duration wait = Duration.between(registeredAt, clock.instant());
    if (wait.isNegative()) return;
    Timer.builder("payment.queue.wait")
        .description("Registration to first successful claim completion; excludes later retries")
        .tag("queue", queue.name().toLowerCase(Locale.ROOT))
        .register(meters)
        .record(wait);
  }

  @Bean
  static ObservationFilter paymentSqlStage() {
    return context -> {
      if (!"jdbc.query".equals(context.getName())) return context;
      ObservationView parent = context.getParentObservation();
      while (parent != null) {
        KeyValue stage = parent.getContextView().getLowCardinalityKeyValue("stage");
        if (stage != null) return context.addLowCardinalityKeyValue(stage);
        parent = parent.getContextView().getParentObservation();
      }
      return context.addLowCardinalityKeyValue(KeyValue.of("stage", "unscoped"));
    };
  }

  /** 오류 handler는 tracing handler보다 먼저 실행한다. 원본 예외는 호출자에게 그대로 전파한다. */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  static ObservationHandler<Observation.Context> paymentObservationErrors() {
    return new ObservationHandler<>() {
      @Override
      public boolean supportsContext(Observation.Context context) {
        String name = context.getName();
        return "payment.processing".equals(name) || name != null && name.startsWith("jdbc.");
      }

      @Override
      public void onError(Observation.Context context) {
        sanitize(context);
      }
    };
  }

  /** Boot은 tracing 그룹을 일반 handler보다 먼저 호출하므로 실제 tracing handler도 감싼다. */
  @Bean
  static BeanPostProcessor paymentTracingErrors() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        return bean instanceof TracingObservationHandler<?> tracing
            ? new SanitizedTracingHandler(tracing)
            : bean;
      }
    };
  }

  private static void sanitize(Observation.Context context) {
    String name = context.getName();
    if (!"payment.processing".equals(name) && (name == null || !name.startsWith("jdbc."))) return;
    Throwable error = context.getError();
    if (error != null && !(error instanceof ObservationFailure)) {
      context.setError(new ObservationFailure(error));
    }
  }

  static final class SanitizedTracingHandler
      implements TracingObservationHandler<Observation.Context> {
    private final TracingObservationHandler<Observation.Context> delegate;

    @SuppressWarnings("unchecked")
    SanitizedTracingHandler(TracingObservationHandler<?> delegate) {
      this.delegate = (TracingObservationHandler<Observation.Context>) delegate;
    }

    @Override
    public Tracer getTracer() {
      return delegate.getTracer();
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
      return delegate.supportsContext(context);
    }

    @Override
    public void onStart(Observation.Context context) {
      delegate.onStart(context);
    }

    @Override
    public void onError(Observation.Context context) {
      sanitize(context);
      delegate.onError(context);
    }

    @Override
    public void onEvent(Observation.Event event, Observation.Context context) {
      delegate.onEvent(event, context);
    }

    @Override
    public void onScopeOpened(Observation.Context context) {
      delegate.onScopeOpened(context);
    }

    @Override
    public void onScopeClosed(Observation.Context context) {
      delegate.onScopeClosed(context);
    }

    @Override
    public void onScopeReset(Observation.Context context) {
      delegate.onScopeReset(context);
    }

    @Override
    public void onStop(Observation.Context context) {
      delegate.onStop(context);
    }
  }

  static final class ObservationFailure extends RuntimeException {
    private ObservationFailure(Throwable original) {
      super(original.getClass().getSimpleName(), null, false, false);
    }
  }
}
