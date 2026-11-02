package com.commercesettle.commerce.infrastructure.config;

import com.commercesettle.commerce.application.exception.OrderDependencyUnavailableException;
import com.commercesettle.commerce.application.port.RefundClosureEvidence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RefundClosureConfiguration {
  @Bean
  @ConditionalOnMissingBean(RefundClosureEvidence.class)
  RefundClosureEvidence unconnectedRefundClosureEvidence() {
    // TODO(#110): Payment 원환불·Settlement 예약 해제·고객확인 소유 조회로 교체한다.
    // 외부 지연 중 DB 트랜잭션 미점유, UNKNOWN 거절, 조회 후 상태 경합을 실제 서비스로 검증한다.
    return (refund, customer) -> {
      throw new OrderDependencyUnavailableException();
    };
  }
}
