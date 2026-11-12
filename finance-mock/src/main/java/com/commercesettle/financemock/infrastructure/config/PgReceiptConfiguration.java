package com.commercesettle.financemock.infrastructure.config;

import com.commercesettle.financemock.application.service.MockPaymentService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 놓친 입금도 매분 다시 조회한다. 원승인 잠금과 현금 유니크 키로 중복 반영을 막는다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    name = "finance-mock.pg.receipts-enabled",
    havingValue = "true",
    matchIfMissing = true)
class PgReceiptConfiguration {
  private final MockPaymentService payments;

  PgReceiptConfiguration(MockPaymentService payments) {
    this.payments = payments;
  }

  @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
  void receiveDue() {
    payments.receiveDue();
  }
}
