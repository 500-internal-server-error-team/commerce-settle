package com.commercesettle.commerce.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercesettle.commerce.domain.model.CouponIssueRun;
import com.commercesettle.commerce.infrastructure.batch.GradeCouponLauncher;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 스케줄러가 실행을 전용 실행기에 넘기고, 앞 실행이 끝나기 전에는 새 실행을 넘기지 않는지 확인한다. */
class GradeCouponSchedulerTest {
  private static final LocalDate MONTH = LocalDate.of(2033, 1, 1);

  private final GradeCouponLauncher launcher = mock(GradeCouponLauncher.class);
  private final List<Runnable> submitted = new ArrayList<>();
  private final Executor executor = submitted::add;

  @Test
  @DisplayName("확인 시점에는 실행을 실행기에 넘기기만 하고 런처는 실행기 스레드에서 부른다")
  void handsLaunchToExecutor() {
    GradeCouponScheduler scheduler = scheduler(GradeCouponScheduler.START_DELAY);

    scheduler.run();

    assertThat(submitted).hasSize(1);
    verify(launcher, never()).run(MONTH);
    submitted.get(0).run();
    verify(launcher).run(MONTH);
  }

  @Test
  @DisplayName("앞 실행이 끝나기 전의 확인은 건너뛰고, 끝난 뒤의 확인은 다시 넘긴다")
  void skipsWhilePreviousRunIsActive() {
    GradeCouponScheduler scheduler = scheduler(GradeCouponScheduler.START_DELAY.plusMinutes(10));

    scheduler.run();
    scheduler.run();
    assertThat(submitted).hasSize(1);

    submitted.get(0).run();
    scheduler.run();
    assertThat(submitted).hasSize(2);
  }

  @Test
  @DisplayName("실행이 실패해도 다음 확인에서 같은 회차를 다시 넘긴다")
  void retriesAfterFailure() {
    doThrow(new IllegalStateException("db down")).when(launcher).run(MONTH);
    GradeCouponScheduler scheduler = scheduler(GradeCouponScheduler.START_DELAY);

    scheduler.run();
    submitted.get(0).run();
    scheduler.run();
    submitted.get(1).run();

    verify(launcher, times(2)).run(MONTH);
  }

  @Test
  @DisplayName("실행기가 작업을 받지 못하면 다음 확인에서 다시 넘긴다")
  void releasesWhenExecutorRejects() {
    List<Runnable> accepted = new ArrayList<>();
    Executor rejectingOnce =
        new Executor() {
          private boolean rejected;

          @Override
          public void execute(Runnable task) {
            if (!rejected) {
              rejected = true;
              throw new IllegalStateException("rejected");
            }
            accepted.add(task);
          }
        };
    GradeCouponScheduler scheduler =
        new GradeCouponScheduler(
            launcher, clockAt(GradeCouponScheduler.START_DELAY), rejectingOnce);

    scheduler.run();
    scheduler.run();

    assertThat(accepted).hasSize(1);
  }

  @Test
  @DisplayName("이전 월에 끝나지 않은 회차를 현재 월보다 먼저 실행하고, 이전 월이 실패해도 현재 월은 실행한다")
  void launchesUnfinishedEarlierMonthsFirst() {
    LocalDate october = MONTH.minusMonths(3);
    LocalDate november = MONTH.minusMonths(2);
    when(launcher.unfinishedMonthsBefore(MONTH)).thenReturn(List.of(october, november));
    doThrow(new IllegalStateException("db down")).when(launcher).run(october);
    GradeCouponScheduler scheduler = scheduler(GradeCouponScheduler.START_DELAY);

    scheduler.run();
    submitted.get(0).run();

    var order = inOrder(launcher);
    order.verify(launcher).run(october);
    order.verify(launcher).run(november);
    order.verify(launcher).run(MONTH);
  }

  @Test
  @DisplayName("혜택 월 1일 00:10 KST 전에는 실행을 넘기지 않는다")
  void waitsUntilStartDelay() {
    scheduler(GradeCouponScheduler.START_DELAY.minusSeconds(1)).run();

    assertThat(submitted).isEmpty();
  }

  private GradeCouponScheduler scheduler(Duration sinceMonthStart) {
    return new GradeCouponScheduler(launcher, clockAt(sinceMonthStart), executor);
  }

  private static Clock clockAt(Duration sinceMonthStart) {
    return Clock.fixed(CouponIssueRun.monthStart(MONTH).plus(sinceMonthStart), ZoneOffset.UTC);
  }
}
