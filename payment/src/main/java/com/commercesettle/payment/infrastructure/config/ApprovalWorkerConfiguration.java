package com.commercesettle.payment.infrastructure.config;

import com.commercesettle.payment.application.dto.ApprovalWorkerSettings;
import com.commercesettle.payment.application.port.PaymentGateway;
import com.commercesettle.payment.infrastructure.client.HttpPaymentGateway;
import com.commercesettle.payment.infrastructure.client.StubPaymentGateway;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 승인 Worker 설정과 PG 게이트웨이. 잘못된 조합(HTTP 제한 시간 ≥ 임대 시간 등)이면 기동을 멈춘다. */
@Configuration
public class ApprovalWorkerConfiguration {
  @Bean
  ApprovalWorkerSettings approvalWorkerSettings(
      @Value("${app.payment-approval.lease-duration:30s}") Duration leaseDuration,
      @Value("${app.payment-approval.http-timeout:5s}") Duration httpTimeout,
      @Value("${app.payment-approval.unconfirmed-retry-delay:5s}") Duration retryDelay,
      @Value("${app.payment-approval.max-attempts:10}") int maxAttempts,
      @Value("${app.payment-approval.card-provider:PG_A}") String cardProvider,
      @Value("${app.payment-approval.funding-account-ref:payout-funding-default}")
          String fundingAccountRef,
      @Value("${app.payment-approval.scenario-version:pg-normal-v1}") String scenarioVersion) {
    return new ApprovalWorkerSettings(
        leaseDuration,
        httpTimeout,
        retryDelay,
        maxAttempts,
        cardProvider,
        fundingAccountRef,
        scenarioVersion);
  }

  /** {@code http}면 finance-mock PG 대역을 호출하고, {@code stub}이면 즉시 승인하는 개발용 대역을 쓴다. 그 밖의 값은 기동을 멈춘다. */
  @Bean
  PaymentGateway paymentGateway(
      RestClient.Builder builder,
      ApprovalWorkerSettings settings,
      Clock clock,
      @Value("${app.payment-approval.gateway:stub}") String gateway,
      @Value("${app.payment-approval.mock-base-url:http://finance-mock:8080}") String baseUrl) {
    return switch (gateway) {
      case "stub" -> new StubPaymentGateway(clock);
      case "http" -> {
        HttpClient http = HttpClient.newBuilder().connectTimeout(settings.httpTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(settings.httpTimeout());
        yield new HttpPaymentGateway(
            builder.baseUrl(baseUrl).requestFactory(requestFactory).build());
      }
      default -> throw new IllegalArgumentException("Unknown payment approval gateway");
    };
  }
}
