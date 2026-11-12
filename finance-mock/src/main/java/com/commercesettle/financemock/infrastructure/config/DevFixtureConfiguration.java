package com.commercesettle.financemock.infrastructure.config;

import com.commercesettle.financemock.infrastructure.persistence.DevFixtureLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.dev-fixtures.enabled", havingValue = "true")
public class DevFixtureConfiguration {
  @Bean
  ApplicationRunner financeDevFixtures(
      DevFixtureLoader loader,
      @Value("${app.dev-fixtures.run-scenarios:false}") boolean runScenarios) {
    return args -> {
      loader.load();
      if (runScenarios) loader.runScenarios();
    };
  }
}
