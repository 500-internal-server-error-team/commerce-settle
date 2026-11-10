package com.commercesettle.commerce.infrastructure.config;

import com.commercesettle.commerce.application.service.MileageAccrualSettings;
import com.commercesettle.commerce.domain.model.MileageAccrualPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 구매 적립 정책 설정. 초기 정책은 외부 결제액의 1%(100bp)·주문당 1,000점이다. docs/policies.md#마일리지와-외부-대역 */
@Configuration(proxyBeanMethods = false)
public class MileageAccrualConfiguration {
  @Bean
  MileageAccrualSettings mileageAccrualSettings(
      @Value("${app.mileage-accrual.enabled:true}") boolean enabled,
      @Value("${app.mileage-accrual.policy-version:mileage-accrual-v1}") String version,
      @Value("${app.mileage-accrual.rate-basis-points:100}") int rateBasisPoints,
      @Value("${app.mileage-accrual.per-order-cap:1000}") long perOrderCap) {
    return enabled
        ? MileageAccrualSettings.enabled(
            new MileageAccrualPolicy(version, rateBasisPoints, perOrderCap))
        : MileageAccrualSettings.disabled();
  }
}
