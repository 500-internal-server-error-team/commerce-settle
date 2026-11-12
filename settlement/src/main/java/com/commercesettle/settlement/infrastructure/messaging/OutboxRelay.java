package com.commercesettle.settlement.infrastructure.messaging;

import com.commercesettle.observability.AsyncObservation;
import com.commercesettle.observability.RepeatedWarning;
import com.commercesettle.settlement.application.port.OutboxStore.Delivery;
import com.commercesettle.settlement.application.port.OutboxStore.Publication;
import com.commercesettle.settlement.application.port.OutboxStore.PublishResult;
import com.commercesettle.settlement.application.service.OutboxDeliveryService;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Payment {@code OutboxRelay}를 바탕으로 한다. 커밋 후·기동 시와, 발행 결과 저장 뒤 같은 집계의 다음 이벤트가 풀렸거나 저장 중 새 Outbox 커밋
 * 신호가 왔거나 슬롯이 모자랐을 때 깨우고 1초 복구 조회로 신호 누락·재시도·만료 임대·소진 정리를 처리한다. 빈 슬롯만 선점하며 전송은 전용 실행기에서 한다.
 */
public class OutboxRelay {
  private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);
  private final RepeatedWarning wakeupRejected = new RepeatedWarning();
  private final RepeatedWarning claimFailed = new RepeatedWarning();
  private final OutboxDeliveryService service;
  private final KafkaTemplate<String, byte[]> template;
  private final Executor executor;
  private final AsyncObservation observations;
  private final Executor coordinator;
  private final AtomicBoolean requested = new AtomicBoolean();
  private final AtomicBoolean scheduled = new AtomicBoolean();
  // 마지막 선점이 빈 슬롯을 다 채웠거나 슬롯이 없어 기한이 지난 행이 더 남았을 수 있다. 결과 저장 뒤 다시 선점할지 정한다.
  private final AtomicBoolean saturated = new AtomicBoolean();
  // 시도를 다 쓴 행 정리는 복구 주기에만 한다.
  private final AtomicBoolean reviewDue = new AtomicBoolean();
  // 같은 프로세스의 Outbox 커밋 신호 수. 발행 결과 저장 중 신호가 왔는지 판정한다.
  private final AtomicLong appendSignals = new AtomicLong();
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
    this(
        service,
        template,
        maxInFlight,
        leaseMs,
        maxAttempts,
        coordinator,
        executor,
        AsyncObservation.NOOP);
  }

  public OutboxRelay(
      OutboxDeliveryService service,
      KafkaTemplate<String, byte[]> template,
      int maxInFlight,
      long leaseMs,
      int maxAttempts,
      Executor coordinator,
      Executor executor,
      AsyncObservation observations) {
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
    this.observations = observations;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onOutboxAppended(OutboxAppended event) {
    appendSignals.incrementAndGet();
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
    reviewDue.set(true);
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
      wakeupRejected
          .at(LOG)
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
    if (!accepting) {
      return;
    }
    if (reviewDue.getAndSet(false)) {
      try {
        service.reviewExhausted(100, maxAttempts);
      } catch (RuntimeException exception) {
        LOG.atWarn().addKeyValue("event", "outbox.review-exhausted").log("Outbox review failed");
      }
    }
    int available = Math.min(100, maxInFlight - inFlight.get());
    if (available <= 0) {
      saturated.set(true);
      return;
    }
    List<Delivery> deliveries;
    try {
      deliveries = service.claim(available, leaseMs, maxAttempts);
    } catch (RuntimeException exception) {
      claimFailed.at(LOG).addKeyValue("event", "outbox.claim").log("Outbox claim failed");
      return;
    }
    saturated.set(deliveries.size() >= available);
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
    observations.run(
        "settlement.outbox.send",
        delivery.traceParent(),
        Map.of("event.id", delivery.id().toString()),
        delivery.timing(),
        () -> sendObserved(delivery));
  }

  private void sendObserved(Delivery delivery) {
    String parent = observations.capture();
    try {
      // claim 커밋 후 원문을 전송하고 완료 콜백에서는 결과 작업만 예약한다.
      template
          .send(delivery.topic(), delivery.key(), delivery.body())
          .whenComplete(
              (result, failure) -> {
                try {
                  executor.execute(
                      () ->
                          observations.run(
                              "settlement.outbox.result",
                              parent,
                              Map.of("event.id", delivery.id().toString()),
                              null,
                              () -> persistResult(delivery, result, failure)));
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
    boolean successorWaiting = false;
    long signalsBefore = appendSignals.get();
    try {
      boolean updated;
      if (failure == null) {
        var metadata = result.getRecordMetadata();
        var published =
            service.markPublished(
                delivery,
                new Publication(metadata.topic(), metadata.partition(), metadata.offset()));
        updated = published != PublishResult.STALE;
        // 결과 UPDATE 스냅샷 뒤 커밋된 뒤 이벤트는 결과가 보지 못하고, 그 신호의 선점은 앞 이벤트가 아직 RUNNING이라 건너뛸 수 있다.
        // 그 뒤 이벤트의 커밋 신호는 결과 저장이 시작된 뒤에 오므로 저장 중 신호 수가 바뀌었으면 커밋 뒤 다시 선점한다.
        successorWaiting =
            published == PublishResult.PUBLISHED_WITH_SUCCESSOR
                || (published == PublishResult.PUBLISHED && appendSignals.get() != signalsBefore);
      } else {
        updated = service.markFailed(delivery, maxAttempts);
      }
      if (!updated) {
        LOG.atDebug()
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
      // 결과마다 선점하지 않는다. 같은 집계의 다음 이벤트가 풀렸을 수 있거나 슬롯 부족으로 남은 행이 있을 때만 깨운다. 실패 결과의
      // 재시도는 1초 이상 뒤라 새 Outbox 커밋 신호와 복구 주기가 처리한다. 다른 인스턴스가 저장한 뒤 이벤트는 복구 주기가 찾는다.
      if (successorWaiting || saturated.get()) {
        wakeUp();
      }
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
