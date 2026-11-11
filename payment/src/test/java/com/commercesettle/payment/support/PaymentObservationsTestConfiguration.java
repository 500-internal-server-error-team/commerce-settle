package com.commercesettle.payment.support;

import com.commercesettle.payment.application.port.PaymentProcessingObservation;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 업무 회귀 시나리오는 관측 인프라 없이 실행하며 실제 계측은 별도 통합 테스트에서 검증한다. */
@TestConfiguration
public class PaymentObservationsTestConfiguration {
  @Bean
  PaymentProcessingObservation paymentProcessingObservation() {
    return PaymentProcessingObservation.NOOP;
  }
}
