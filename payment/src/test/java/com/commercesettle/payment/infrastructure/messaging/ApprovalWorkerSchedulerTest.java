package com.commercesettle.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercesettle.payment.application.service.ApprovalWorker;
import com.commercesettle.payment.domain.repository.PaymentJobRepository.LeaseLane;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ApprovalWorkerSchedulerTest {
  @Test
  @DisplayName("재시도가 막혀 있어도 신규 승인 슬롯은 남은 작업을 처리한다")
  void isolatesBlockedRetriesFromNewApprovals() throws Exception {
    var worker = mock(ApprovalWorker.class);
    var retryEntered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var fresh = new AtomicInteger();
    when(worker.runOnce(LeaseLane.RETRY))
        .thenAnswer(
            i -> {
              retryEntered.countDown();
              release.await(5, TimeUnit.SECONDS);
              return false;
            });
    when(worker.runOnce(LeaseLane.NEW)).thenAnswer(i -> fresh.incrementAndGet() <= 5);
    var scheduler = new ApprovalWorkerScheduler(worker, 1, 2, 1);
    try {
      scheduler.poll();
      assertThat(retryEntered.await(5, TimeUnit.SECONDS)).isTrue();
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(() -> assertThat(fresh.get()).isGreaterThanOrEqualTo(6));
    } finally {
      release.countDown();
      scheduler.stop();
    }
  }

  @Test
  @DisplayName("승인 Job 저장 신호는 신규 승인만 깨우고 재시도는 복구 주기에서 실행한다")
  void wakesOnlyNewLaneForCreatedJob() throws Exception {
    var worker = mock(ApprovalWorker.class);
    var scheduler = new ApprovalWorkerScheduler(worker, 1, 2, 1);
    try {
      scheduler.onWorkAvailable(new PaymentWorkAvailable(PaymentWorkAvailable.Kind.REQUEST));
      verifyNoInteractions(worker);
      scheduler.onWorkAvailable(new PaymentWorkAvailable(PaymentWorkAvailable.Kind.APPROVAL));
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(() -> verify(worker).runOnce(LeaseLane.NEW));
      verify(worker, never()).runOnce(LeaseLane.RETRY);
    } finally {
      scheduler.stop();
    }
  }

  @Test
  @DisplayName("동시성 1 설정은 추가 슬롯 없이 신규·재시도를 공용 처리한다")
  void keepsSingleSlotConfigurationWithinItsLimit() throws Exception {
    var worker = mock(ApprovalWorker.class);
    var scheduler = new ApprovalWorkerScheduler(worker, 1, 1, 1);
    try {
      scheduler.poll();
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(() -> verify(worker).runOnce(LeaseLane.ANY));
      verify(worker, never()).runOnce(LeaseLane.NEW);
      verify(worker, never()).runOnce(LeaseLane.RETRY);
    } finally {
      scheduler.stop();
    }
  }

  @ParameterizedTest
  @CsvSource({"worker,true,true", "api,true,false", "worker,false,false"})
  @DisplayName("승인 실행기는 활성 Worker 역할에서만 등록한다")
  void createsOnlyForEnabledWorker(String role, boolean enabled, boolean expected) {
    new ApplicationContextRunner()
        .withUserConfiguration(ApprovalWorkerScheduler.class)
        .withBean(ApprovalWorker.class, () -> mock(ApprovalWorker.class))
        .withPropertyValues("PAYMENT_ROLE=" + role, "app.payment-approval.enabled=" + enabled)
        .run(
            context ->
                assertThat(context.getBeansOfType(ApprovalWorkerScheduler.class))
                    .hasSize(expected ? 1 : 0));
  }

  @Test
  @DisplayName("신규 승인과 재시도 동시성의 범위를 검증한다")
  void rejectsInvalidConcurrency() {
    var worker = mock(ApprovalWorker.class);
    assertThatThrownBy(() -> new ApprovalWorkerScheduler(worker, 1, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApprovalWorkerScheduler(worker, 1, 1025, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApprovalWorkerScheduler(worker, 1, 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
