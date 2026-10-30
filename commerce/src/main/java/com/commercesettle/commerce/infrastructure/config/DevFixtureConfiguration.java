package com.commercesettle.commerce.infrastructure.config;

import com.commercesettle.commerce.application.port.AddressCipher;
import com.commercesettle.commerce.application.port.PasswordHasher;
import com.commercesettle.commerce.infrastructure.persistence.DevFixtureLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;

/** 명시적으로 켠 개발 인스턴스에서만 실험용 DB 데이터를 적재한다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.dev-fixtures.enabled", havingValue = "true")
public class DevFixtureConfiguration {
  @Bean
  DevFixtureLoader devFixtureLoader(
      JdbcOperations jdbc, PasswordHasher passwords, AddressCipher addresses) {
    return new DevFixtureLoader(jdbc, passwords, addresses);
  }

  @Bean
  ApplicationRunner loadDevFixtures(
      DevFixtureLoader loader, @Value("${app.dev-fixtures.password:}") String password) {
    if (password.isBlank() || password.length() < 12 || password.length() > 128) {
      throw new IllegalArgumentException("DEV_FIXTURE_PASSWORD must contain 12..128 characters");
    }
    return args -> loader.load(password);
  }
}
