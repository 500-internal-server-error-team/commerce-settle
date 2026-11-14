package com.commercesettle.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkerSlotsTest {
  @Test
  @DisplayName("4096슬롯도 유휴 복구 때는 네 번만 조회한다")
  void boundsIdleRecoveryProbes() throws Exception {
    var probed = new CountDownLatch(4);
    var excess = new CountDownLatch(1);
    var calls = new AtomicInteger();
    try (var slots =
        new WorkerSlots(
            "large-idle",
            4096,
            1,
            () -> {
              if (calls.incrementAndGet() > 4) excess.countDown();
              probed.countDown();
              return false;
            },
            e -> {
              throw new AssertionError(e);
            })) {
      slots.recover();
      assertThat(probed.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(excess.await(200, TimeUnit.MILLISECONDS)).isFalse();
    }
    assertThat(calls.get()).isEqualTo(4);
  }

  @Test
  @DisplayName("누락 신호를 복구한 뒤 실제 작업이 있으면 나머지 슬롯으로 확장한다")
  void expandsRecoveryOnlyWhenWorkExists() throws Exception {
    var busy = new CountDownLatch(32);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    try (var slots =
        new WorkerSlots(
            "fanout",
            32,
            1,
            () -> {
              if (calls.incrementAndGet() < 32) return true;
              busy.countDown();
              waitFor(release);
              return false;
            },
            e -> {
              throw new AssertionError(e);
            })) {
      try {
        slots.recover();
        assertThat(busy.await(5, TimeUnit.SECONDS)).isTrue();
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  @DisplayName("2048개의 대기 호출은 가상 스레드에서 동시에 실행한다")
  void supportsTwoThousandConcurrentWaitingCalls() throws Exception {
    var entered = new CountDownLatch(2048);
    var release = new CountDownLatch(1);
    var platformThreads = new AtomicInteger();
    try (var slots =
        new WorkerSlots(
            "large-active",
            4096,
            1,
            () -> {
              if (!Thread.currentThread().isVirtual()) platformThreads.incrementAndGet();
              entered.countDown();
              waitFor(release);
              return false;
            },
            e -> {
              throw new AssertionError(e);
            })) {
      try {
        for (int i = 0; i < 2048; i++) slots.wakeUp();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(platformThreads.get()).isZero();
      } finally {
        release.countDown();
      }
    }
  }

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
  @DisplayName("느린 슬롯이 남아 있어도 새 신호는 빈 조회를 마친 다른 슬롯으로 넘긴다")
  void assignsNewWorkToAvailableCapacity() throws Exception {
    var first = new CountDownLatch(1);
    var empty = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var seen = new CountDownLatch(1);
    var ready = new java.util.concurrent.atomic.AtomicBoolean();
    var calls = new AtomicInteger();
    try (var slots =
        new WorkerSlots(
            "idle-priority",
            2,
            1,
            () -> {
              if (calls.incrementAndGet() == 1) {
                first.countDown();
                try {
                  assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(e);
                }
                return false;
              }
              if (ready.getAndSet(false)) {
                seen.countDown();
                return true;
              }
              empty.countDown();
              return false;
            },
            e -> {
              throw new AssertionError(e);
            })) {
      try {
        slots.wakeUp();
        assertThat(first.await(5, TimeUnit.SECONDS)).isTrue();
        slots.wakeUp();
        assertThat(empty.await(5, TimeUnit.SECONDS)).isTrue();
        ready.set(true);
        slots.wakeUp();

        assertThat(seen.await(5, TimeUnit.SECONDS)).isTrue();
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  @DisplayName("오류로 멈춘 슬롯 대신 정상 유휴 슬롯이 새 작업을 처리한다")
  void doesNotBindNewSignalsToPausedCapacity() throws Exception {
    var failed = new CountDownLatch(1);
    var empty = new CountDownLatch(1);
    var seen = new CountDownLatch(1);
    var ready = new java.util.concurrent.atomic.AtomicBoolean();
    var calls = new AtomicInteger();
    try (var slots =
        new WorkerSlots(
            "paused-capacity",
            2,
            1,
            () -> {
              if (calls.incrementAndGet() == 1) throw new IllegalStateException("fixture");
              if (ready.getAndSet(false)) {
                seen.countDown();
                return true;
              }
              empty.countDown();
              return false;
            },
            e -> failed.countDown())) {
      slots.wakeUp();
      assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
      slots.wakeUp();
      assertThat(empty.await(5, TimeUnit.SECONDS)).isTrue();
      ready.set(true);
      slots.wakeUp();

      assertThat(seen.await(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("다른 슬롯이 다음 작업을 시작해도 이전 빈 조회 뒤의 대기 신호를 유지한다")
  void handsPendingWorkPastAnOlderEmptyRead(boolean continueFirst) throws Exception {
    var initial = new CountDownLatch(2);
    var advance = new CountDownLatch(1);
    var returnEmpty = new CountDownLatch(1);
    var claimed = new CountDownLatch(1);
    var releaseClaimed = new CountDownLatch(1);
    var seen = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var ready = new AtomicInteger();
    try (var slots =
        new WorkerSlots(
            "stale-empty",
            2,
            1,
            () -> {
              int call = calls.incrementAndGet();
              try {
                if (call <= 2) {
                  initial.countDown();
                  assertThat((call == 1 ? advance : returnEmpty).await(30, TimeUnit.SECONDS))
                      .isTrue();
                  return call == 1 && continueFirst;
                }
                int row = ready.getAndUpdate(value -> Math.max(0, value - 1));
                if (row == 2) {
                  claimed.countDown();
                  assertThat(releaseClaimed.await(30, TimeUnit.SECONDS)).isTrue();
                  return true;
                }
                if (row == 1) {
                  seen.countDown();
                  return true;
                }
                return false;
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
              }
            },
            exception -> {
              throw new AssertionError(exception);
            })) {
      try {
        slots.recover();
        assertThat(initial.await(5, TimeUnit.SECONDS)).isTrue();
        ready.set(2);
        slots.wakeUp();
        slots.wakeUp();
        advance.countDown();
        assertThat(claimed.await(5, TimeUnit.SECONDS)).isTrue();
        returnEmpty.countDown();

        assertThat(seen.await(5, TimeUnit.SECONDS)).isTrue();
      } finally {
        advance.countDown();
        returnEmpty.countDown();
        releaseClaimed.countDown();
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
