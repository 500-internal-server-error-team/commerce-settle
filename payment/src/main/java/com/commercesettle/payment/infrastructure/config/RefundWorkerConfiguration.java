package com.commercesettle.payment.infrastructure.config;

import com.commercesettle.payment.application.dto.RefundWorkerSettings;
import com.commercesettle.payment.application.port.RefundGateway;
import com.commercesettle.payment.infrastructure.client.HttpRefundGateway;
import com.commercesettle.payment.infrastructure.client.StubRefundGateway;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** 환불 Worker 설정과 외부 환불 게이트웨이. 잘못된 조합(HTTP 제한 시간 ≥ 임대 시간 등)이면 기동을 멈춘다. */
@Configuration
public class RefundWorkerConfiguration {
  @Bean
  RefundWorkerSettings refundWorkerSettings(
      @Value("${app.refund.lease-duration:30s}") Duration leaseDuration,
      @Value("${app.refund.http-timeout:5s}") Duration httpTimeout,
      @Value("${app.refund.max-attempts:10}") int maxAttempts) {
    return new RefundWorkerSettings(leaseDuration, httpTimeout, maxAttempts);
  }

  /** 승인과 같은 Finance Mock 주소·자격을 사용하며, 환불의 HTTP 제한 시간을 적용한다. */
  @Bean
  RefundGateway refundGateway(
      RestClient.Builder builder,
      RefundWorkerSettings settings,
      Clock clock,
      @Value("${app.refund.gateway:stub}") String gateway,
      @Value("${app.payment-approval.mock-base-url:http://finance-mock:8080}") String baseUrl,
      @Value("${app.payment-approval.mock-credential:}") String credential) {
    return switch (gateway) {
      case "stub" -> new StubRefundGateway(clock);
      case "http" ->
          new HttpRefundGateway(
              ApprovalWorkerConfiguration.httpClient(builder, settings.httpTimeout(), baseUrl),
              credential);
      default -> throw new IllegalArgumentException("Unknown refund gateway");
    };
  }
}
