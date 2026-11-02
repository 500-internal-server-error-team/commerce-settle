package com.commercesettle.commerce.infrastructure.scheduling;

import com.commercesettle.commerce.domain.model.CouponIssueRun;
import com.commercesettle.commerce.infrastructure.batch.GradeCouponLauncher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 매월 1일 00:10 KST부터 그 달의 월간 우수고객 회차를 만들고 실행한다. 10분마다 다시 확인해 미생성·미완료 회차와 앱 중단으로 놓친 실행을 이어간다. 완료한 회차는
 * 실행하지 않으므로 반복 확인은 회차 조회만 한다.
 */
public class GradeCouponScheduler {
  private static final Logger LOG = LoggerFactory.getLogger(GradeCouponScheduler.class);
  static final Duration START_DELAY = Duration.ofMinutes(10);

  private final GradeCouponLauncher launcher;
  private final Clock clock;

  public GradeCouponScheduler(GradeCouponLauncher launcher, Clock clock) {
    this.launcher = launcher;
    this.clock = clock;
  }

  @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul")
  public void run() {
    Instant now = clock.instant();
    LocalDate month = LocalDate.ofInstant(now, CouponIssueRun.BUSINESS_ZONE).withDayOfMonth(1);
    if (now.isBefore(CouponIssueRun.monthStart(month).plus(START_DELAY))) {
      return;
    }
    try {
      launcher.run(month);
    } catch (RuntimeException e) {
      // 예외 메시지는 남기지 않고 종류만 기록한다. 다음 확인에서 같은 회차를 재시작한다.
      LOG.atWarn()
          .addKeyValue("event", "coupon.grade-job-failed")
          .addKeyValue("targetMonth", month)
          .addKeyValue("errorType", e.getClass().getName())
          .log("Grade coupon job failed; it will be retried on the next check");
    }
  }
}
