package com.commercesettle.commerce.infrastructure.config;

import com.commercesettle.commerce.application.port.SettlementRetentionGateway;
import com.commercesettle.commerce.infrastructure.client.HttpSettlementRetentionGateway;
import com.commercesettle.internalauth.InternalCredentials;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class RetentionSourceConfiguration {
  @Bean
  SettlementRetentionGateway settlementRetentionGateway(
      RestClient.Builder builder,
      @Value("${app.retention.enabled:false}") boolean enabled,
      @Value("${app.retention.settlement-url:http://settlement:8080}") String url,
      @Value("${app.retention.settlement-credential:}") String credential) {
    if (enabled) InternalCredentials.requireFormat("service:commerce", credential);
    var timeout = Duration.ofSeconds(2);
    var factory =
        new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
    factory.setReadTimeout(timeout);
    return new HttpSettlementRetentionGateway(
        builder.baseUrl(url).requestFactory(factory).build(), credential);
  }
}
