package com.commercesettle.commerce.infrastructure.scheduling;

import com.commercesettle.commerce.domain.model.CouponIssueRun;
import com.commercesettle.commerce.infrastructure.batch.GradeCouponLauncher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 매월 1일 00:10 KST부터 그 달의 월간 우수고객 회차를 만들고 실행한다. 10분마다 다시 확인해 미생성·미완료 회차와 앱 중단으로 놓친 실행을 이어간다. 완료한 회차는
 * 실행하지 않으므로 반복 확인은 회차 조회만 한다. 이전 월에 끝나지 않은 회차도 먼저 실행해, 원래 기한이 지났으면 새 발급 없이 EXPIRED로 종결한다. 스케줄러 스레드를
 * 오래 잡지 않도록 실행은 전용 실행기에 넘기고(ADR 0008), 이 인스턴스의 앞 실행이 끝나지 않았으면 새 실행을 넘기지 않는다. 다른 인스턴스와의 중복 실행은 런처의
 * 회차 잠금이 막는다.
 */
public class GradeCouponScheduler {
  private static final Logger LOG = LoggerFactory.getLogger(GradeCouponScheduler.class);
  static final Duration START_DELAY = Duration.ofMinutes(10);

  private final GradeCouponLauncher launcher;
  private final Clock clock;
  private final Executor executor;
  private final AtomicBoolean running = new AtomicBoolean();

  public GradeCouponScheduler(GradeCouponLauncher launcher, Clock clock, Executor executor) {
    this.launcher = launcher;
    this.clock = clock;
    this.executor = executor;
  }

  @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul")
  public void run() {
    Instant now = clock.instant();
    LocalDate month = LocalDate.ofInstant(now, CouponIssueRun.BUSINESS_ZONE).withDayOfMonth(1);
    if (now.isBefore(CouponIssueRun.monthStart(month).plus(START_DELAY))) {
      return;
    }
    if (!running.compareAndSet(false, true)) {
      LOG.atInfo()
          .addKeyValue("event", "coupon.grade-job")
          .addKeyValue("targetMonth", month)
          .addKeyValue("result", "PREVIOUS_RUN_ACTIVE")
          .log("Previous grade coupon run is still active; skipping this check");
      return;
    }
    try {
      executor.execute(() -> launch(month));
    } catch (RuntimeException e) {
      running.set(false);
      failed(month, e);
    }
  }

  private void launch(LocalDate month) {
    try {
      try {
        launcher.unfinishedMonthsBefore(month).forEach(this::launchOne);
      } catch (RuntimeException e) {
        failed(month, e);
      }
      launchOne(month);
    } finally {
      running.set(false);
    }
  }

  // 월마다 실패를 따로 기록해 이전 월의 오류가 현재 월 실행을 막지 않는다.
  private void launchOne(LocalDate month) {
    try {
      launcher.run(month);
    } catch (RuntimeException e) {
      failed(month, e);
    }
  }

  // 예외 메시지는 남기지 않고 종류만 기록한다. 다음 확인에서 같은 회차를 재시작한다.
  private static void failed(LocalDate month, RuntimeException e) {
    LOG.atWarn()
        .addKeyValue("event", "coupon.grade-job-failed")
        .addKeyValue("targetMonth", month)
        .addKeyValue("errorType", e.getClass().getName())
        .log("Grade coupon job failed; it will be retried on the next check");
  }
}
