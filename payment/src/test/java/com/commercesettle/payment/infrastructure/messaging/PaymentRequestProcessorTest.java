package com.commercesettle.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.payment.application.service.ApplyOrderPaymentRequestService;
import com.commercesettle.payment.infrastructure.config.PaymentRequestProcessorConfiguration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
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
  @DisplayName("처리할 요청이 없으면 이번 주기를 끝낸다")
  void stopsWhenNothingIsLeft() {
    StubService service = new StubService(true, true, false, true);

    new PaymentRequestProcessor(service, 10).run();

    assertThat(service.calls).isEqualTo(3);
  }

  @Test
  @DisplayName("배치 크기까지 처리하면 간격을 기다리지 않고 남은 요청을 이어서 처리한다")
  void keepsProcessingWhileBatchesAreFull() {
    StubService service = new StubService(true, true, true, true, true);

    new PaymentRequestProcessor(service, 2).run();

    assertThat(service.calls).isEqualTo(6);
  }

  @Test
  @DisplayName("설정한 슬롯 수만큼 요청을 동시에 처리한다")
  void processesWithConcurrentSlots() throws Exception {
    CountDownLatch allStarted = new CountDownLatch(3);
    AtomicInteger calls = new AtomicInteger();
    ApplyOrderPaymentRequestService service =
        new StubService() {
          @Override
          public boolean processNext() {
            if (calls.incrementAndGet() > 3) {
              return false;
            }
            allStarted.countDown();
            try {
              // 세 슬롯이 모두 들어올 때까지 기다린다. 순차 실행이면 시간 초과로 false가 된다.
              return allStarted.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return false;
            }
          }
        };
    PaymentRequestProcessor processor = new PaymentRequestProcessor(service, 10, 3);

    processor.run();

    assertThat(allStarted.getCount()).isZero();
    processor.stop();
  }

  @Test
  @DisplayName("처리 중 예외가 나면 이번 주기를 멈춘다")
  void stopsCycleOnFailure() {
    StubService service = new StubService(true);
    service.failNext = true;

    assertThatThrownBy(() -> new PaymentRequestProcessor(service, 10).run())
        .isInstanceOf(IllegalStateException.class);
    assertThat(service.calls).isEqualTo(1);
  }

  @ParameterizedTest
  @CsvSource({"worker, true, true", "api, true, false", "worker, false, false", "app, true, false"})
  @DisplayName("처리기는 Worker 역할에서 명시적으로 활성화했을 때만 만든다")
  void createsProcessorOnlyForEnabledWorker(String role, boolean enabled, boolean expected) {
    new ApplicationContextRunner()
        .withUserConfiguration(PaymentRequestProcessorConfiguration.class)
        .withBean(ApplyOrderPaymentRequestService.class, () -> new StubService())
        .withPropertyValues(
            "PAYMENT_ROLE=" + role, "app.payment-request-processor.enabled=" + enabled)
        .run(
            context ->
                assertThat(context.getBeansOfType(PaymentRequestProcessor.class))
                    .hasSize(expected ? 1 : 0));
  }

  private static class StubService extends ApplyOrderPaymentRequestService {
    private final Queue<Boolean> results;
    private boolean failNext;
    private int calls;

    StubService(Boolean... results) {
      super(null, null, null, null, null, null, null, null, null, null, 1);
      this.results = new ArrayDeque<>(List.of(results));
    }

    @Override
    public boolean processNext() {
      calls++;
      if (failNext) {
        throw new IllegalStateException("processing failed");
      }
      return Boolean.TRUE.equals(results.poll());
    }
  }
}
