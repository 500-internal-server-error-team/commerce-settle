package com.commercesettle.payment.infrastructure.config;

import com.commercesettle.payment.application.service.ApplyOrderPaymentRequestService;
import com.commercesettle.payment.infrastructure.messaging.PaymentRequestProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 결제 요청 처리기는 Worker 역할에서 명시적으로 활성화했을 때만 실행한다. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "PAYMENT_ROLE", havingValue = "worker")
@ConditionalOnProperty(name = "app.payment-request-processor.enabled", havingValue = "true")
public class PaymentRequestProcessorConfiguration {
  @Bean
  PaymentRequestProcessor paymentRequestProcessor(
      ApplyOrderPaymentRequestService service,
      @Value("${app.payment-request-processor.batch-size:100}") int batchSize) {
    return new PaymentRequestProcessor(service, batchSize);
  }
}
