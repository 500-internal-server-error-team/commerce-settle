package com.commercesettle.observability.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.observability.AsyncObservation;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AsyncObservationAutoConfigurationTest {
  @Test
  void worksWithAndWithoutTracingAndPreservesAnExplicitImplementation() {
    var runner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AsyncObservationAutoConfiguration.class));
    runner.run(
        context ->
            assertThat(context.getBean(AsyncObservation.class)).isSameAs(AsyncObservation.NOOP));
    runner
        .withBean(ObservationRegistry.class, ObservationRegistry::create)
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .run(
            context ->
                assertThat(context.getBean(AsyncObservation.class))
                    .isInstanceOf(MicrometerAsyncObservation.class));
    runner
        .withBean(AsyncObservation.class, () -> AsyncObservation.NOOP)
        .run(context -> assertThat(context).hasSingleBean(AsyncObservation.class));
  }
}
