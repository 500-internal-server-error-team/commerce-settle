package com.commercesettle.observability.infrastructure;

import com.commercesettle.observability.AsyncObservation;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@AutoConfiguration(
    afterName = {
      "org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration",
      "org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration"
    })
public class AsyncObservationAutoConfiguration {
  @Bean
  static ObservationFilter sqlStage() {
    return MicrometerAsyncObservation.sqlStage();
  }

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  static ObservationHandler<Observation.Context> observationErrors() {
    return ObservationErrors.handler();
  }

  @Bean
  static BeanPostProcessor tracingErrors() {
    return ObservationErrors.tracingHandlers();
  }

  @Bean
  @ConditionalOnMissingBean
  AsyncObservation asyncObservation(
      ObjectProvider<ObservationRegistry> observations,
      ObjectProvider<Tracer> tracer,
      ObjectProvider<Propagator> propagator,
      ObjectProvider<MeterRegistry> meters) {
    if (observations.getIfAvailable() == null || meters.getIfAvailable() == null) {
      return AsyncObservation.NOOP;
    }
    return new MicrometerAsyncObservation(
        observations.getObject(),
        tracer.getIfAvailable(() -> Tracer.NOOP),
        propagator.getIfAvailable(() -> Propagator.NOOP),
        meters.getObject(),
        Clock.systemUTC());
  }
}
