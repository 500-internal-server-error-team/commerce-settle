package com.commercesettle.commerce.infrastructure.config;

import com.commercesettle.commerce.application.exception.OrderDependencyUnavailableException;
import com.commercesettle.commerce.application.port.RefundClosureEvidence;
import com.commercesettle.commerce.application.port.RefundClosureStore;
import com.commercesettle.commerce.infrastructure.client.HttpRefundClosureEvidence;
import com.commercesettle.internalauth.InternalCredentials;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RefundClosureConfiguration {
  @Bean
  @ConditionalOnProperty(name = "app.refund-closure.enabled", havingValue = "true")
  RefundClosureEvidence connectedRefundClosureEvidence(
      RefundClosureStore store,
      ObjectMapper mapper,
      Clock clock,
      @Value("${app.refund-closure.payment-url}") String paymentUrl,
      @Value("${app.refund-closure.payment-credential}") String paymentCredential,
      @Value("${app.refund-closure.settlement-url}") String settlementUrl,
      @Value("${app.refund-closure.settlement-credential}") String settlementCredential) {
    return new HttpRefundClosureEvidence(
        client(paymentUrl, paymentCredential),
        client(settlementUrl, settlementCredential),
        store,
        mapper,
        clock);
  }

  private static RestClient client(String url, String credential) {
    var timeout = Duration.ofSeconds(3);
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    factory.setReadTimeout(timeout);
    return RestClient.builder()
        .baseUrl(url)
        .requestFactory(factory)
        .defaultHeader(
            HttpHeaders.AUTHORIZATION,
            "Bearer " + InternalCredentials.requireFormat("service:commerce", credential))
        .build();
  }

  @Bean
  @ConditionalOnMissingBean(RefundClosureEvidence.class)
  RefundClosureEvidence unconnectedRefundClosureEvidence() {
    // 명시적으로 연결하지 않은 환경은 금융 성공 근거를 만들지 않는다.
    return (refund, customer) -> {
      throw new OrderDependencyUnavailableException();
    };
  }
}
