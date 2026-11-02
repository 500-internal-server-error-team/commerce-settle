package com.commercesettle.commerce.infrastructure.config;

import com.commercesettle.commerce.infrastructure.security.InternalCallerFilter;
import com.commercesettle.commerce.infrastructure.security.InternalCredentials;
import com.commercesettle.commerce.infrastructure.security.InternalOperations;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** 내부 호출 자격(ADR 0010) 판정을 모든 요청의 컨트롤러 앞에 둔다. Gateway 자격이 없으면 기동하지 않는다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InternalAuthProperties.class)
public class InternalAuthConfiguration {
  @Bean
  InternalCredentials internalCredentials(InternalAuthProperties properties) {
    final var credentials = new LinkedHashMap<String, List<String>>();
    properties
        .senders()
        .forEach(
            (sender, values) ->
                credentials.put(
                    sender,
                    Stream.of(values.current(), values.previous())
                        .filter(value -> value != null && !value.isEmpty())
                        .toList()));
    return new InternalCredentials(Map.copyOf(credentials), "gateway");
  }

  @Bean
  InternalOperations internalOperations(
      ObjectMapper mapper, @Value("classpath:contracts/openapi.json") Resource contract)
      throws IOException {
    try (var input = contract.getInputStream()) {
      return InternalOperations.load(mapper, input);
    }
  }

  @Bean
  FilterRegistrationBean<InternalCallerFilter> internalCallerFilter(
      InternalCredentials credentials,
      InternalOperations operations,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors,
      ObjectProvider<MeterRegistry> meters) {
    final var registration =
        new FilterRegistrationBean<>(
            new InternalCallerFilter(
                "commerce",
                credentials,
                operations,
                errors,
                meters.getIfAvailable(SimpleMeterRegistry::new)));
    // 추적 문맥을 만든 관측 필터 뒤, 다른 요청 처리 전에 판정한다.
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }
}
