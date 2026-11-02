package com.commercesettle.payment.infrastructure.messaging;

import com.commercesettle.payment.application.service.ApprovalWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 승인 Worker 폴링. Worker 역할에서 명시적으로 켰을 때만 동작한다. 정해진 간격마다 도래한 작업을 한 건씩 상한까지 실행하며, 한 작업의 예외는 기록하고 다음
 * 작업으로 넘어간다. 그 작업은 임대 만료 뒤 다시 회수되고 시도 상한에서 운영 확인으로 넘어간다.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "PAYMENT_ROLE", havingValue = "worker")
@ConditionalOnProperty(name = "app.payment-approval.enabled", havingValue = "true")
class ApprovalWorkerScheduler {
  private static final Logger LOG = LoggerFactory.getLogger(ApprovalWorkerScheduler.class);

  private final ApprovalWorker worker;
  private final int maxJobsPerPoll;

  ApprovalWorkerScheduler(
      ApprovalWorker worker,
      @Value("${app.payment-approval.max-jobs-per-poll:20}") int maxJobsPerPoll) {
    if (maxJobsPerPoll < 1) {
      throw new IllegalArgumentException("Invalid approval jobs per poll");
    }
    this.worker = worker;
    this.maxJobsPerPoll = maxJobsPerPoll;
  }

  @Scheduled(
      initialDelayString = "${app.payment-approval.poll-interval-ms:1000}",
      fixedDelayString = "${app.payment-approval.poll-interval-ms:1000}")
  void poll() {
    for (int processed = 0; processed < maxJobsPerPoll; processed++) {
      try {
        if (!worker.runOnce()) {
          return;
        }
      } catch (RuntimeException e) {
        LOG.atError()
            .setCause(e)
            .addKeyValue("event", "payment.approval")
            .addKeyValue("result", "ERROR")
            .addKeyValue("errorType", e.getClass().getName())
            .log("Approval job failed; it will be retried after its lease expires");
      }
    }
  }
}
