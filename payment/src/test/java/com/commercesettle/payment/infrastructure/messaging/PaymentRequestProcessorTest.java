package com.commercesettle.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercesettle.payment.application.service.ApplyOrderPaymentRequestService;
import com.commercesettle.payment.infrastructure.config.PaymentRequestProcessorConfiguration;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PaymentRequestProcessorTest {
  @Test
  @DisplayName("요청 저장 신호만 처리하고 남은 묶음을 주기 대기 없이 이어간다")
  void wakesForRequestsAndDrainsRemainingWork() throws Exception {
    var service = mock(ApplyOrderPaymentRequestService.class);
    var calls = new AtomicInteger();
    when(service.processNext()).thenAnswer(i -> calls.incrementAndGet() <= 5);
    var processor = new PaymentRequestProcessor(service, 2, 1);
    try {
      processor.onWorkAvailable(new PaymentWorkAvailable(PaymentWorkAvailable.Kind.APPROVAL));
      verifyNoInteractions(service);
      processor.onWorkAvailable(new PaymentWorkAvailable(PaymentWorkAvailable.Kind.REQUEST));
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(() -> assertThat(calls.get()).isEqualTo(6));
    } finally {
      processor.stop();
    }
  }

  @Test
  @DisplayName("느린 업무 처리 중에도 복구 예약 호출은 반환한다")
  void returnsSchedulerWhileProcessingIsBlocked() throws Exception {
    var service = mock(ApplyOrderPaymentRequestService.class);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(service.processNext())
        .thenAnswer(
            i -> {
              entered.countDown();
              release.await(5, TimeUnit.SECONDS);
              return false;
            });
    var processor = new PaymentRequestProcessor(service, 1, 1);
    try {
      processor.run();
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      release.countDown();
      processor.stop();
    }
  }

  @ParameterizedTest
  @CsvSource({"worker, true, true", "api, true, false", "worker, false, false", "app, true, false"})
  @DisplayName("요청 처리기는 활성 Worker 역할에서만 등록한다")
  void createsProcessorOnlyForEnabledWorker(String role, boolean enabled, boolean expected) {
    var service = mock(ApplyOrderPaymentRequestService.class);
    new ApplicationContextRunner()
        .withUserConfiguration(PaymentRequestProcessorConfiguration.class)
        .withBean(ApplyOrderPaymentRequestService.class, () -> service)
        .withPropertyValues(
            "PAYMENT_ROLE=" + role, "app.payment-request-processor.enabled=" + enabled)
        .run(
            context ->
                assertThat(context.getBeansOfType(PaymentRequestProcessor.class))
                    .hasSize(expected ? 1 : 0));
  }
}
