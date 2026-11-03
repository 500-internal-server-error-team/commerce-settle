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
  @DisplayName("작업 오류가 나면 이번 폴링을 끝내고 다음 폴링에서 다시 실행한다")
  void endsPollAfterJobFailureAndResumesNextPoll() throws Exception {
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

    try {
      scheduler.poll();

      assertThat(calls.get()).isEqualTo(1);
      scheduler.poll();
      assertThat(calls.get()).isEqualTo(4);
    } finally {
      scheduler.stop();
    }
  }

  @Test
  @DisplayName("선점 오류가 계속돼도 슬롯마다 한 번 실패한 뒤 폴링을 끝낸다")
  void endsPollWhenClaimsKeepFailing() throws Exception {
    ApprovalWorker worker = mock(ApprovalWorker.class);
    AtomicInteger calls = new AtomicInteger();
    given(worker.runOnce())
        .willAnswer(
            invocation -> {
              // 기존 구현도 유한하게 반환하도록 실패 횟수를 제한한다.
              if (calls.incrementAndGet() <= 6) {
                throw new IllegalStateException("database unavailable");
              }
              return false;
            });
    ApprovalWorkerScheduler scheduler = new ApprovalWorkerScheduler(worker, 2, 3);

    try {
      scheduler.poll();

      assertThat(calls.get()).isEqualTo(3);
    } finally {
      scheduler.stop();
    }
  }

  @Test
  @DisplayName("다른 슬롯이 처리 상한에 닿아도 오류 뒤에는 이번 폴링을 끝낸다")
  void endsPollWhenAnotherSlotHitsTheCapAfterFailure() throws Exception {
    ApprovalWorker worker = mock(ApprovalWorker.class);
    AtomicInteger calls = new AtomicInteger();
    given(worker.runOnce())
        .willAnswer(
            invocation -> {
              int call = calls.incrementAndGet();
              if (call == 1) {
                throw new IllegalStateException("database unavailable");
              }
              return call < 6;
            });
    ApprovalWorkerScheduler scheduler = new ApprovalWorkerScheduler(worker, 1, 2);

    try {
      scheduler.poll();

      assertThat(calls.get()).isEqualTo(2);
    } finally {
      scheduler.stop();
    }
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
