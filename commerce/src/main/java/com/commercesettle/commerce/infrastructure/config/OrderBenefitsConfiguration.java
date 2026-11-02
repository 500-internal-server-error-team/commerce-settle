package com.commercesettle.commerce.infrastructure.config;

import com.commercesettle.commerce.application.exception.OrderDependencyUnavailableException;
import com.commercesettle.commerce.application.port.OrderBenefits;
import com.commercesettle.commerce.domain.model.Order;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrderBenefitsConfiguration {
  @Bean
  @ConditionalOnMissingBean(OrderBenefits.class)
  OrderBenefits unconnectedOrderBenefits() {
    // TODO(#110, #103): 쿠폰/이벤트 소유 어댑터로 교체. 같은 DB 트랜잭션에서
    // 마지막 예산·참여 경쟁, 예약 중 만료, 이전 회차 해제, 환불 중복 복원을 통합 검증한다.
    return new OrderBenefits() {
      public Offer quote(
          UUID buyer,
          UUID product,
          UUID seller,
          long price,
          int quantity,
          UUID coupon,
          UUID event,
          Order.ExternalMethod method,
          Instant now) {
        if (coupon != null || event != null) throw new OrderDependencyUnavailableException();
        return new Offer(price, null, 0, 0);
      }

      private void requireNoBenefits(Order order) {
        if (order.getBuyerCouponId() != null || order.getEventId() != null)
          throw new OrderDependencyUnavailableException();
      }

      public void reserve(Order order, Long version, Instant now) {
        requireNoBenefits(order);
      }

      public void retry(Order order, Instant now) {
        requireNoBenefits(order);
      }

      public void consume(Order order, Instant now) {
        requireNoBenefits(order);
      }

      public void release(Order order, Instant now) {
        requireNoBenefits(order);
      }

      public void receiveReturn(Order order, UUID requestId, Instant now) {
        requireNoBenefits(order);
      }

      public void restore(Order order, UUID refundId, Instant now) {
        requireNoBenefits(order);
      }
    };
  }
}
