package com.commercesettle.observability.infrastructure;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;
import org.springframework.beans.factory.config.BeanPostProcessor;

/** 업무 예외는 호출자에게 보존하고, SQL 실패 행·바인딩·예외 원문을 추적으로 보내지 않는다. */
public final class ObservationErrors {
  private ObservationErrors() {}

  private static boolean supported(String name) {
    return "payment.processing".equals(name)
        || "async.work".equals(name)
        || "async.stage".equals(name)
        || name != null && name.startsWith("jdbc.");
  }

  /** 오류 handler는 tracing handler보다 먼저 실행한다. 원본 예외는 호출자에게 그대로 전파한다. */
  public static ObservationHandler<Observation.Context> handler() {
    return new ObservationHandler<>() {
      @Override
      public boolean supportsContext(Observation.Context context) {
        return supported(context.getName());
      }

      @Override
      public void onError(Observation.Context context) {
        sanitize(context);
      }
    };
  }

  /** Boot은 tracing 그룹을 일반 handler보다 먼저 호출하므로 실제 tracing handler도 감싼다. */
  public static BeanPostProcessor tracingHandlers() {
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
    if (!supported(context.getName())) return;
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

  public static final class ObservationFailure extends RuntimeException {
    public ObservationFailure(String type) {
      super(type, null, false, false);
    }

    private ObservationFailure(Throwable original) {
      this(original.getClass().getSimpleName());
    }
  }
}
