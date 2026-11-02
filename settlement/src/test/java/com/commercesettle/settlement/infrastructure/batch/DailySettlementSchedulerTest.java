package com.commercesettle.settlement.infrastructure.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.TaskScheduler;

/** 기동 복구의 재확인 예약. 예약한 작업은 바로 실행하는 대역으로 바꿔 DB 정상화 뒤 재기동 없이 이어지는지 본다. */
class DailySettlementSchedulerTest {
  private static final LocalDate BATCH_DATE = LocalDate.of(2026, 11, 1);

  private final DailySettlementLauncher launcher = mock(DailySettlementLauncher.class);
  private final DailySettlementRecovery recovery = mock(DailySettlementRecovery.class);
  private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
  private DailySettlementScheduler scheduler;

  @BeforeEach
  void setUp() {
    when(recovery.staleAfter()).thenReturn(Duration.ofMinutes(10));
    doAnswer(
            call -> {
              call.<Runnable>getArgument(0).run();
              return null;
            })
        .when(taskScheduler)
        .schedule(any(Runnable.class), any(Instant.class));
    scheduler = new DailySettlementScheduler(launcher, recovery, taskScheduler, Clock.systemUTC());
  }

  @Test
  @DisplayName("남은 실행 조회가 일시 오류로 실패해도 다음 조회를 예약해, DB가 정상화되면 재기동 없이 그 날짜를 복구한다")
  void retriesListingAfterTransientFailure() {
    when(recovery.runningBatchDates())
        .thenThrow(new DataAccessResourceFailureException("db down"))
        .thenReturn(Set.of(BATCH_DATE));
    when(launcher.run(BATCH_DATE)).thenReturn(BatchStatus.COMPLETED);

    scheduler.recoverInterruptedBatches();

    verify(launcher, timeout(5_000)).run(BATCH_DATE);
    verify(taskScheduler, after(200).times(1)).schedule(any(Runnable.class), any(Instant.class));
  }

  @Test
  @DisplayName("날짜 재확인이 잠금·조회 오류로 실패해도 다음 확인을 예약하고, 완료되면 더 예약하지 않는다")
  void retriesResumeAfterTransientFailure() {
    when(recovery.runningBatchDates()).thenReturn(Set.of(BATCH_DATE));
    when(launcher.run(BATCH_DATE))
        .thenThrow(new DataAccessResourceFailureException("daily settlement lock is unavailable"))
        .thenReturn(BatchStatus.COMPLETED);

    scheduler.recoverInterruptedBatches();

    verify(launcher, timeout(5_000).times(2)).run(BATCH_DATE);
    verify(taskScheduler, after(200).times(1)).schedule(any(Runnable.class), any(Instant.class));
  }

  @Test
  @DisplayName("다른 실행자가 실행 중이라 STARTED면 계속 예약하고, 완료되면 멈춘다")
  void keepsCheckingWhileStarted() {
    when(recovery.runningBatchDates()).thenReturn(Set.of(BATCH_DATE));
    when(launcher.run(BATCH_DATE))
        .thenReturn(BatchStatus.STARTED, BatchStatus.STARTED, BatchStatus.COMPLETED);

    scheduler.recoverInterruptedBatches();

    verify(launcher, timeout(5_000).times(3)).run(BATCH_DATE);
    verify(taskScheduler, after(200).times(2)).schedule(any(Runnable.class), any(Instant.class));
  }
}
