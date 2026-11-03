package com.commercesettle.payment.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.payment.application.port.PaymentGateway;
import com.commercesettle.payment.application.port.RefundGateway;
import com.commercesettle.payment.infrastructure.client.HttpPaymentGateway;
import com.commercesettle.payment.infrastructure.client.HttpRefundGateway;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class RefundWorkerConfigurationTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withInitializer(
              c ->
                  c.getBeanFactory()
                      .setConversionService(ApplicationConversionService.getSharedInstance()))
          .withUserConfiguration(ApprovalWorkerConfiguration.class, RefundWorkerConfiguration.class)
          .withBean(Clock.class, Clock::systemUTC)
          .withBean(RestClient.Builder.class, RestClient::builder);

  @Test
  @DisplayName("HTTP 승인·환불은 별도 포트 Bean과 같은 Finance Mock 자격으로 선택한다")
  void selectsBothHttpPortsWithoutAmbiguity() {
    context
        .withPropertyValues(
            "app.payment-approval.gateway=http",
            "app.refund.gateway=http",
            "app.payment-approval.mock-credential=payment-finance-mock-credential-0123456789ab")
        .run(
            c -> {
              assertThat(c)
                  .hasNotFailed()
                  .hasSingleBean(PaymentGateway.class)
                  .hasSingleBean(RefundGateway.class);
              assertThat(c.getBean(PaymentGateway.class)).isInstanceOf(HttpPaymentGateway.class);
              assertThat(c.getBean(RefundGateway.class)).isInstanceOf(HttpRefundGateway.class);
            });
  }

  @Test
  @DisplayName("환불만 HTTP여도 호출 자격이 없으면 기동하지 않는다")
  void refusesMissingRefundCredential() {
    context.withPropertyValues("app.refund.gateway=http").run(c -> assertThat(c).hasFailed());
  }

  @Test
  @DisplayName("알 수 없는 환불 게이트웨이와 임대보다 긴 HTTP 제한 시간은 기동하지 않는다")
  void refusesInvalidRefundSettings() {
    context.withPropertyValues("app.refund.gateway=unknown").run(c -> assertThat(c).hasFailed());
    context.withPropertyValues("app.refund.http-timeout=30s").run(c -> assertThat(c).hasFailed());
  }
}
