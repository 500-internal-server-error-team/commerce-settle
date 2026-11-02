package com.commercesettle.internalauth;

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
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 내부 호출 자격(ADR 0010) 판정을 서블릿 앱의 모든 요청에서 컨트롤러 앞에 둔다. 소유 서비스 이름이나 필수 발신자의 자격이 없으면 기동하지 않는다. 관리 포트는 별도
 * 웹 서버라 이 필터를 거치지 않는다.
 *
 * <p>명세는 앱 리소스의 {@code contracts/openapi.json}을 읽는다.
 */
@AutoConfiguration(after = WebMvcAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(InternalAuthProperties.class)
public class InternalAuthAutoConfiguration {
  @Bean
  InternalCredentials internalCredentials(InternalAuthProperties properties) {
    if (properties.senders() == null) {
      throw new IllegalStateException("Missing internal credentials");
    }
    final var credentials = new LinkedHashMap<String, List<String>>();
    properties
        .senders()
        .forEach(
            (sender, values) -> {
              if (values == null || values.current() == null || values.current().isEmpty()) {
                throw new IllegalStateException(
                    "Missing current internal credential for " + sender);
              }
              credentials.put(
                  sender,
                  Stream.of(values.current(), values.previous())
                      .filter(value -> value != null && !value.isEmpty())
                      .toList());
            });
    return new InternalCredentials(Map.copyOf(credentials), properties.requiredSender());
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
      InternalAuthProperties properties,
      InternalCredentials credentials,
      InternalOperations operations,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors,
      ObjectProvider<MeterRegistry> meters) {
    if (properties.service() == null || properties.service().isBlank()) {
      throw new IllegalStateException("Missing internal-auth service name");
    }
    final var registration =
        new FilterRegistrationBean<>(
            new InternalCallerFilter(
                properties.service(),
                properties.excludedPaths(),
                credentials,
                operations,
                errors,
                meters.getIfAvailable(SimpleMeterRegistry::new)));
    // 추적 문맥을 만든 관측 필터 뒤, 다른 요청 처리 전에 판정한다.
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }
}
