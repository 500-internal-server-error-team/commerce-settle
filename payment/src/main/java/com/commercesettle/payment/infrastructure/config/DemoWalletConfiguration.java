package com.commercesettle.payment.infrastructure.config;

import com.commercesettle.payment.infrastructure.persistence.DemoWalletFixture;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 시연 지갑은 API 역할 하나가 준비한다. 금융 스냅샷 fixture 설정과 독립적으로 켠다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.dev-fixtures.demo-wallets", havingValue = "true")
public class DemoWalletConfiguration {
  @Bean
  @ConditionalOnProperty(name = "PAYMENT_ROLE", havingValue = "api", matchIfMissing = true)
  ApplicationRunner paymentDemoWallets(DemoWalletFixture fixture) {
    return args -> fixture.load();
  }
}
