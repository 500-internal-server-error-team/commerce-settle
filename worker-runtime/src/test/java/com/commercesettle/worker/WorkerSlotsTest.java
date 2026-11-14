package com.commercesettle.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkerSlotsTest {
  @Test
  @DisplayName("느린 슬롯의 묶음이 끝나지 않아도 다른 슬롯은 다음 작업을 처리한다")
  void continuesWhileAnotherSlotIsBlocked() throws Exception {
    var release = new CountDownLatch(1);
    var continued = new CountDownLatch(4);
    var calls = new AtomicInteger();
    try (var slots =
        new WorkerSlots(
            "independent",
            2,
            1,
            () -> {
              int call = calls.incrementAndGet();
              if (call == 1) {
                waitFor(release);
                return true;
              }
              if (call <= 5) {
                continued.countDown();
                return true;
              }
              return false;
            },
            exception -> {
              throw new AssertionError(exception);
            })) {
      try {
        slots.recover();

        assertThat(continued.await(5, TimeUnit.SECONDS)).isTrue();
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  @DisplayName("빈 조회가 끝나기 직전 들어온 신호를 놓치지 않는다")
  void keepsWakeupDuringLastEmptyRead() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var again = new CountDownLatch(1);
    var calls = new AtomicInteger();
    try (var slots =
        new WorkerSlots(
            "last-read",
            1,
            1,
            () -> {
              if (calls.incrementAndGet() == 1) {
                entered.countDown();
                waitFor(release);
              } else again.countDown();
              return false;
            },
            exception -> {
              throw new AssertionError(exception);
            })) {
      try {
        slots.wakeUp();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        slots.wakeUp();
        release.countDown();

        assertThat(again.await(5, TimeUnit.SECONDS)).isTrue();
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  @DisplayName("대량 신호를 합치고 진행 중 작업과 빈 조회를 슬롯 수로 제한한다")
  void coalescesSignalsWithoutBuildingAWorkQueue() throws Exception {
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    try (var slots =
        new WorkerSlots(
            "burst",
            2,
            1,
            () -> {
              calls.incrementAndGet();
              entered.countDown();
              waitFor(release);
              return false;
            },
            exception -> {
              throw new AssertionError(exception);
            })) {
      try {
        slots.recover();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 10_000; i++) slots.wakeUp();
        assertThat(calls.get()).isEqualTo(2);
        release.countDown();

        await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> assertThat(calls.get()).isEqualTo(4));
      } finally {
        release.countDown();
      }
    }
    assertThat(calls.get()).isEqualTo(4);
  }

  @Test
  @DisplayName("오류 슬롯은 새 작업 신호로 재시도하지 않고 복구 조회에서 재개한다")
  void pausesFailedSlotsUntilRecovery() throws Exception {
    var calls = new AtomicInteger();
    var failures = new AtomicInteger();
    try (var slots =
        new WorkerSlots(
            "failure",
            3,
            1,
            () -> {
              calls.incrementAndGet();
              throw new IllegalStateException("database unavailable");
            },
            exception -> failures.incrementAndGet())) {
      slots.recover();
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(() -> assertThat(failures.get()).isEqualTo(3));
      for (int i = 0; i < 1_000; i++) slots.wakeUp();
      assertThat(calls.get()).isEqualTo(3);

      slots.recover();
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(() -> assertThat(failures.get()).isEqualTo(6));
    }
    assertThat(calls.get()).isEqualTo(6);
  }

  @Test
  @DisplayName("한 건의 저장 신호는 슬롯 수만큼 빈 조회를 만들지 않고 종료 뒤에는 선점하지 않는다")
  void wakesOneSlotAndStopsAfterClose() throws Exception {
    var calls = new AtomicInteger();
    var slots =
        new WorkerSlots(
            "empty",
            8,
            1,
            () -> {
              calls.incrementAndGet();
              return false;
            },
            e -> {});
    slots.wakeUp();
    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(calls.get()).isEqualTo(1));
    slots.close();
    slots.wakeUp();
    slots.recover();

    assertThat(calls.get()).isEqualTo(1);
  }

  private static void waitFor(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}
