package com.commercesettle.payment.infrastructure.messaging;

import com.commercesettle.observability.RepeatedWarning;
import com.commercesettle.payment.application.service.ApprovalWorker;
import com.commercesettle.payment.domain.repository.PaymentJobRepository.LeaseLane;
import com.commercesettle.worker.WorkerSlots;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 신규 승인과 재시도에 별도 실행 예산을 둔다. 각 슬롯이 한 건만 임대하고 호출 전후 DB 연결을 반환한다. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "PAYMENT_ROLE", havingValue = "worker")
@ConditionalOnProperty(name = "app.payment-approval.enabled", havingValue = "true")
class ApprovalWorkerScheduler {
  private final WorkerSlots fresh;
  private final WorkerSlots retries;
  private final RepeatedWarning paused = new RepeatedWarning();

  @Autowired
  ApprovalWorkerScheduler(
      ApprovalWorker worker,
      @Value("${app.payment-approval.max-jobs-per-poll:20}") int batchSize,
      @Value("${app.payment-approval.concurrency:6}") int concurrency,
      @Value("${app.payment-approval.retry-concurrency:1}") int retryConcurrency) {
    if (concurrency < 1
        || concurrency > 1024
        || retryConcurrency < 1
        || retryConcurrency > 64
        || (concurrency > 1 && retryConcurrency >= concurrency))
      throw new IllegalArgumentException("Invalid retry concurrency");
    // 동시성 1인 기존 설정은 한 공용 슬롯으로 유지한다.
    int reserved = concurrency == 1 ? 0 : retryConcurrency;
    fresh =
        slots(
            worker,
            reserved == 0 ? LeaseLane.ANY : LeaseLane.NEW,
            concurrency - reserved,
            batchSize);
    retries = reserved == 0 ? null : slots(worker, LeaseLane.RETRY, reserved, batchSize);
  }

  private WorkerSlots slots(ApprovalWorker worker, LeaseLane lane, int concurrency, int batchSize) {
    return new WorkerSlots(
        "payment-approval-" + lane.name().toLowerCase(java.util.Locale.ROOT),
        concurrency,
        batchSize,
        () -> worker.runOnce(lane),
        exception ->
            paused
                .at(LoggerFactory.getLogger(ApprovalWorkerScheduler.class))
                .setCause(exception)
                .addKeyValue("event", "payment.approval")
                .addKeyValue("lane", lane.name())
                .log("Approval slot paused until recovery"));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onWorkAvailable(PaymentWorkAvailable event) {
    if (event.kind() == PaymentWorkAvailable.Kind.APPROVAL) fresh.wakeUp();
  }

  @EventListener(ApplicationReadyEvent.class)
  @Scheduled(
      initialDelayString = "${app.payment-approval.poll-interval-ms:1000}",
      fixedDelayString = "${app.payment-approval.poll-interval-ms:1000}")
  void poll() {
    fresh.recover();
    if (retries != null) retries.recover();
  }

  @PreDestroy
  void stop() throws InterruptedException {
    try {
      fresh.close();
    } finally {
      if (retries != null) retries.close();
    }
  }
}
