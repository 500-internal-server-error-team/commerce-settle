package com.commercesettle.payment.infrastructure.config;

import com.commercesettle.payment.application.dto.RefundWorkerSettings;
import com.commercesettle.payment.application.port.RefundGateway;
import com.commercesettle.payment.infrastructure.client.StubRefundGateway;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

  /** 지금은 즉시 성공하는 {@code stub}만 제공한다. finance-mock 환불 연동을 추가하기 전에는 다른 값으로 기동하지 않는다. */
  @Bean
  RefundGateway refundGateway(Clock clock, @Value("${app.refund.gateway:stub}") String gateway) {
    if (!"stub".equals(gateway)) {
      throw new IllegalArgumentException("Unknown refund gateway");
    }
    return new StubRefundGateway(clock);
  }
}
