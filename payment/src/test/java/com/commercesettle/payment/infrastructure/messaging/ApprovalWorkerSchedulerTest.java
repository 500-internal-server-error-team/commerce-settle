package com.commercesettle.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.commercesettle.payment.application.service.ApprovalWorker;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApprovalWorkerSchedulerTest {

  @Test
  @DisplayName("설정한 슬롯 수만큼 작업을 동시에 실행한다")
  void runsSlotsConcurrently() throws Exception {
    ApprovalWorker worker = mock(ApprovalWorker.class);
    CountDownLatch allStarted = new CountDownLatch(3);
    AtomicInteger calls = new AtomicInteger();
    given(worker.runOnce())
        .willAnswer(
            invocation -> {
              if (calls.incrementAndGet() > 3) {
                return false;
              }
              allStarted.countDown();
              // 세 슬롯이 모두 들어올 때까지 기다린다. 순차 실행이면 여기서 시간 초과로 실패한다.
              return allStarted.await(5, TimeUnit.SECONDS);
            });
    ApprovalWorkerScheduler scheduler = new ApprovalWorkerScheduler(worker, 20, 3);

    scheduler.poll();

    assertThat(allStarted.getCount()).isZero();
    scheduler.stop();
  }

  @Test
  @DisplayName("슬롯이 상한까지 처리하면 간격을 기다리지 않고 남은 작업을 이어서 실행한다")
  void keepsPollingWhileSlotsHitTheCap() throws Exception {
    ApprovalWorker worker = mock(ApprovalWorker.class);
    AtomicInteger remaining = new AtomicInteger(25);
    given(worker.runOnce()).willAnswer(invocation -> remaining.getAndDecrement() > 0);
    ApprovalWorkerScheduler scheduler = new ApprovalWorkerScheduler(worker, 2, 2);

    scheduler.poll();

    assertThat(remaining.get()).isNegative();
    scheduler.stop();
  }

  @Test
  @DisplayName("한 작업의 예외는 그 슬롯의 다음 작업을 막지 않는다")
  void continuesAfterJobFailure() throws Exception {
    ApprovalWorker worker = mock(ApprovalWorker.class);
    AtomicInteger calls = new AtomicInteger();
    given(worker.runOnce())
        .willAnswer(
            invocation -> {
              int call = calls.incrementAndGet();
              if (call == 1) {
                throw new IllegalStateException("boom");
              }
              return call < 4;
            });
    ApprovalWorkerScheduler scheduler = new ApprovalWorkerScheduler(worker, 20, 1);

    scheduler.poll();

    assertThat(calls.get()).isEqualTo(4);
    scheduler.stop();
  }

  @Test
  @DisplayName("슬롯 수가 1~64 밖이면 기동하지 않는다")
  void rejectsInvalidConcurrency() {
    ApprovalWorker worker = mock(ApprovalWorker.class);

    assertThatThrownBy(() -> new ApprovalWorkerScheduler(worker, 20, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApprovalWorkerScheduler(worker, 20, 65))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
