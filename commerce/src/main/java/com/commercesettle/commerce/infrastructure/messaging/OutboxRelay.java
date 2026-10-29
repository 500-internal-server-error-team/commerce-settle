package com.commercesettle.commerce.infrastructure.messaging;

import com.commercesettle.commerce.application.port.OutboxStore.Delivery;
import com.commercesettle.commerce.application.port.OutboxStore.Publication;
import com.commercesettle.commerce.application.service.OutboxDeliveryService;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

public class OutboxRelay {
  private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);
  private final OutboxDeliveryService service;
  private final KafkaTemplate<String, byte[]> template;
  private final Executor executor;
  private final Executor coordinator;
  private final AtomicBoolean requested = new AtomicBoolean();
  private final AtomicBoolean scheduled = new AtomicBoolean();
  private final int maxInFlight;
  private final long leaseMs;
  private final int maxAttempts;
  private final AtomicInteger inFlight = new AtomicInteger();
  private volatile boolean accepting = true;

  public OutboxRelay(
      OutboxDeliveryService service,
      KafkaTemplate<String, byte[]> template,
      int maxInFlight,
      long leaseMs,
      int maxAttempts,
      Executor coordinator,
      Executor executor) {
    if (maxInFlight < 1 || leaseMs <= 0 || maxAttempts < 1 || maxAttempts > 10) {
      throw new IllegalArgumentException("Invalid outbox relay limits");
    }
    this.service = service;
    this.template = template;
    this.executor = executor;
    this.coordinator = coordinator;
    this.maxInFlight = maxInFlight;
    this.leaseMs = leaseMs;
    this.maxAttempts = maxAttempts;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onOutboxAppended(OutboxAppended event) {
    wakeUp();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    wakeUp();
  }

  @Scheduled(
      initialDelayString = "${app.outbox.recovery-interval-ms:1000}",
      fixedDelayString = "${app.outbox.recovery-interval-ms:1000}")
  public void recover() {
    wakeUp();
  }

  public void wakeUp() {
    if (!accepting) {
      return;
    }
    requested.set(true);
    scheduleDrain();
  }

  private void scheduleDrain() {
    if (!accepting || !scheduled.compareAndSet(false, true)) {
      return;
    }
    try {
      coordinator.execute(this::drain);
    } catch (RejectedExecutionException exception) {
      scheduled.set(false);
      // pending 신호는 유지하며 재귀 제출하지 않는다. 복구 조회가 다시 깨운다.
      LOG.atWarn()
          .addKeyValue("event", "outbox.wakeup-rejected")
          .log("Outbox wakeup could not be scheduled; stored events remain recoverable");
    }
  }

  private void drain() {
    try {
      while (accepting && requested.getAndSet(false)) {
        publishDue();
      }
    } finally {
      scheduled.set(false);
      // 마지막 조회와 종료 사이에 들어온 신호를 놓치지 않는다.
      if (accepting && requested.get()) {
        scheduleDrain();
      }
    }
  }

  private void publishDue() {
    int available = Math.min(100, maxInFlight - inFlight.get());
    if (!accepting || available <= 0) {
      return;
    }
    List<Delivery> deliveries;
    try {
      deliveries = service.claim(available, leaseMs, maxAttempts);
    } catch (RuntimeException exception) {
      LOG.atWarn().addKeyValue("event", "outbox.claim").log("Outbox claim failed");
      return;
    }
    for (var delivery : deliveries) {
      inFlight.incrementAndGet();
      try {
        executor.execute(() -> send(delivery));
      } catch (RejectedExecutionException exception) {
        rejected(delivery);
      }
    }
  }

  private void send(Delivery delivery) {
    try {
      // claim 커밋 후 원문을 전송하고 완료 콜백에서는 결과 작업만 예약한다.
      template
          .send(delivery.topic(), delivery.key(), delivery.body())
          .whenComplete(
              (result, failure) -> {
                try {
                  executor.execute(() -> persistResult(delivery, result, failure));
                } catch (RejectedExecutionException exception) {
                  rejected(delivery);
                }
              });
    } catch (RuntimeException exception) {
      persistResult(delivery, null, exception);
    }
  }

  private void persistResult(
      Delivery delivery, SendResult<String, byte[]> result, Throwable failure) {
    try {
      boolean updated;
      if (failure == null) {
        var metadata = result.getRecordMetadata();
        updated =
            service.markPublished(
                delivery,
                new Publication(metadata.topic(), metadata.partition(), metadata.offset()));
      } else {
        updated = service.markFailed(delivery, maxAttempts);
      }
      if (!updated) {
        LOG.atWarn()
            .addKeyValue("event", "outbox.stale-result")
            .addKeyValue("eventId", delivery.id())
            .log("Outbox lease no longer owns the result");
      }
    } catch (RuntimeException exception) {
      LOG.atError()
          .addKeyValue("event", "outbox.persist-result")
          .addKeyValue("eventId", delivery.id())
          .log("Outbox result persistence failed; the original event remains recoverable");
    } finally {
      inFlight.decrementAndGet();
      wakeUp();
    }
  }

  private void rejected(Delivery delivery) {
    accepting = false;
    inFlight.decrementAndGet();
    LOG.atWarn()
        .addKeyValue("event", "outbox.executor-stopped")
        .addKeyValue("eventId", delivery.id())
        .log("Outbox dispatch stopped; unpersisted results will recover after their leases expire");
  }

  @PreDestroy
  public void stop() {
    accepting = false;
    requested.set(false);
  }
}
