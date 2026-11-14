package com.commercesettle.payment.infrastructure.messaging;

import com.commercesettle.observability.RepeatedWarning;
import com.commercesettle.payment.application.service.ApplyOrderPaymentRequestService;
import com.commercesettle.worker.WorkerSlots;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Inbox를 독립 슬롯으로 처리한다. DB 인계·선점·업무 반영·재시도 트랜잭션은 서비스가 소유한다. */
public class PaymentRequestProcessor {
  private final WorkerSlots slots;
  private final RepeatedWarning paused = new RepeatedWarning();

  public PaymentRequestProcessor(ApplyOrderPaymentRequestService service, int batchSize) {
    this(service, batchSize, 1);
  }

  public PaymentRequestProcessor(
      ApplyOrderPaymentRequestService service, int batchSize, int concurrency) {
    if (concurrency < 1 || concurrency > 64)
      throw new IllegalArgumentException("Invalid request concurrency");
    slots =
        new WorkerSlots(
            "payment-request",
            concurrency,
            batchSize,
            service::processNext,
            exception ->
                paused
                    .at(LoggerFactory.getLogger(PaymentRequestProcessor.class))
                    .setCause(exception)
                    .addKeyValue("event", "payment.request")
                    .log("Request slot paused until recovery"));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onWorkAvailable(PaymentWorkAvailable event) {
    if (event.kind() == PaymentWorkAvailable.Kind.REQUEST) slots.wakeUp();
  }

  @EventListener(ApplicationReadyEvent.class)
  @Scheduled(
      initialDelayString = "${app.payment-request-processor.interval-ms:1000}",
      fixedDelayString = "${app.payment-request-processor.interval-ms:1000}")
  public void run() {
    slots.recover();
  }

  @PreDestroy
  void stop() throws InterruptedException {
    slots.close();
  }
}
