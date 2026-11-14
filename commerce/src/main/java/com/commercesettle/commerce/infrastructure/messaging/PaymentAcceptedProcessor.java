package com.commercesettle.commerce.infrastructure.messaging;

import com.commercesettle.commerce.application.service.PaymentAcceptedService;
import com.commercesettle.observability.RepeatedWarning;
import com.commercesettle.worker.WorkerSlots;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 커밋된 Inbox를 전용 슬롯에서 처리하고, 기동·주기 조회로 누락 신호와 도래한 대기를 회수한다. */
public class PaymentAcceptedProcessor {
  private final WorkerSlots slots;
  private final RepeatedWarning paused = new RepeatedWarning();

  public PaymentAcceptedProcessor(PaymentAcceptedService service, int batchSize, int concurrency) {
    if (batchSize < 1 || batchSize > 100 || concurrency < 1 || concurrency > 64) {
      throw new IllegalArgumentException("Invalid Inbox worker limits");
    }
    slots =
        new WorkerSlots(
            "commerce-payment-accepted-processor",
            concurrency,
            batchSize,
            service::processNext,
            exception ->
                paused
                    .at(LoggerFactory.getLogger(PaymentAcceptedProcessor.class))
                    .setCause(exception)
                    .addKeyValue("event", "commerce.payment-accepted-processor")
                    .log("Inbox slot paused until recovery"));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onWorkAvailable(PaymentWorkAvailable event) {
    if (event.accepted() == true) slots.wakeUp();
  }

  @EventListener(ApplicationReadyEvent.class)
  @Scheduled(
      initialDelayString = "${app.payment-accepted-processor.interval-ms:1000}",
      fixedDelayString = "${app.payment-accepted-processor.interval-ms:1000}")
  public void run() {
    slots.recover();
  }

  @PreDestroy
  public void stop() throws InterruptedException {
    slots.close();
  }
}
