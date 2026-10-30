package com.commercesettle.settlement.infrastructure.worker;

import com.commercesettle.settlement.application.service.PayoutWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 지급 Worker 폴링. 정해진 간격마다 도래한 작업을 한 건씩 상한까지 실행한다. 주말·공휴일도 같다. 지금은 한 인스턴스에서 순차 실행하며 ADR 0005의 동시 실행
 * 슬롯은 아직 두지 않았다. 한 작업의 예외는 기록하고 다음 작업으로 넘어간다. 그 작업은 임대 만료 뒤 다시 회수되고 시도 상한에서 운영 확인으로 넘어간다.
 */
@Component
@ConditionalOnProperty(
    prefix = "settlement.payout-worker",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
class PayoutWorkerScheduler {
  private static final Logger LOGGER = LoggerFactory.getLogger(PayoutWorkerScheduler.class);

  private final PayoutWorker worker;
  private final int maxJobsPerPoll;

  PayoutWorkerScheduler(
      PayoutWorker worker,
      @Value("${settlement.payout-worker.max-jobs-per-poll}") int maxJobsPerPoll) {
    this.worker = worker;
    this.maxJobsPerPoll = maxJobsPerPoll;
  }

  @Scheduled(fixedDelayString = "${settlement.payout-worker.poll-interval-ms}")
  void poll() {
    for (int processed = 0; processed < maxJobsPerPoll; processed++) {
      try {
        if (!worker.runOnce()) {
          return;
        }
      } catch (RuntimeException e) {
        LOGGER
            .atError()
            .setCause(e)
            .addKeyValue("event", "settlement.payout")
            .addKeyValue("result", "ERROR")
            .addKeyValue("errorType", e.getClass().getName())
            .log("Payout job failed; it will be retried after its lease expires");
      }
    }
  }
}
