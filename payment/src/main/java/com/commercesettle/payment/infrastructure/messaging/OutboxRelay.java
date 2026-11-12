package com.commercesettle.payment.infrastructure.messaging;

import com.commercesettle.observability.AsyncObservation;
import com.commercesettle.observability.RepeatedWarning;
import com.commercesettle.observability.infrastructure.BusinessMetrics;
import com.commercesettle.payment.application.port.OutboxStore.Delivery;
import com.commercesettle.payment.application.port.OutboxStore.Publication;
import com.commercesettle.payment.application.port.OutboxStore.Published;
import com.commercesettle.payment.application.service.OutboxDeliveryService;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.ProducerRecord;
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
 * 커밋된 Outbox를 Kafka로 보낸다. 전송 성공 결과는 큐에 모아 한 번에 최대 {@value #MAX_PUBLISHED_BATCH}건씩 한 트랜잭션에 저장한다. 저장
 * 작업이 돌고 있지 않으면 바로 시작하므로 부하가 낮을 때는 1건씩 저장되고, 저장 중에 끝난 전송이 다음 묶음에 모인다. 저장 전에 중단되면 행은 RUNNING으로 남아 임대
 * 만료 뒤 같은 eventId로 다시 발행된다.
 */
public class OutboxRelay {
  static final int MAX_PUBLISHED_BATCH = 100;
  private static final String RESULT_OPERATION = "payment.outbox.result";
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
  private final ConcurrentLinkedQueue<Completed> published = new ConcurrentLinkedQueue<>();
  private final AtomicBoolean flushing = new AtomicBoolean();
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
    if (accepting) {
      try {
        // 상한을 넘긴 행은 이미 같은 집계의 후행을 막고 있어 확인 상태로 바꾸는 시점이 발행 순서에 영향을 주지 않는다.
        service.reviewExhausted(100, maxAttempts);
      } catch (RuntimeException exception) {
        LOG.atWarn().addKeyValue("event", "outbox.review-exhausted").log("Outbox review failed");
      }
    }
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
    int available = Math.min(100, maxInFlight - inFlight.get());
    if (!accepting || available <= 0) {
      return;
    }
    List<Delivery> deliveries;
    try {
      deliveries = service.claim(available, leaseMs, maxAttempts);
    } catch (RuntimeException exception) {
      claimFailed.at(LOG).addKeyValue("event", "outbox.claim").log("Outbox claim failed");
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
    observations.run(
        "payment.outbox.send",
        delivery.traceParent(),
        Map.of("event.id", delivery.id().toString()),
        delivery.timing(),
        () -> sendObserved(delivery));
  }

  private void sendObserved(Delivery delivery) {
    String parent = observations.capture();
    try {
      // claim 커밋 후 원문을 전송하고 완료 콜백에서는 결과 작업만 예약한다.
      var record =
          new ProducerRecord<String, byte[]>(delivery.topic(), delivery.key(), delivery.body());
      if (delivery.orderAcceptedAt() != null) {
        record
            .headers()
            .add(
                BusinessMetrics.ORDER_ACCEPTED_AT_HEADER,
                delivery.orderAcceptedAt().toString().getBytes(StandardCharsets.US_ASCII));
      }
      var sent =
          delivery.orderAcceptedAt() == null
              ? template.send(delivery.topic(), delivery.key(), delivery.body())
              : template.send(record);
      sent.whenComplete(
          (result, failure) -> {
            if (failure == null) {
              queuePublished(delivery, result, parent);
              return;
            }
            try {
              executor.execute(
                  () ->
                      observations.run(
                          RESULT_OPERATION,
                          parent,
                          ids(delivery),
                          null,
                          () -> persistFailure(delivery, failure)));
            } catch (RejectedExecutionException exception) {
              rejected(delivery);
            }
          });
    } catch (RuntimeException exception) {
      persistFailure(delivery, exception);
    }
  }

  private void queuePublished(Delivery delivery, SendResult<String, byte[]> result, String parent) {
    var metadata = result.getRecordMetadata();
    published.add(
        new Completed(
            new Published(
                delivery,
                new Publication(metadata.topic(), metadata.partition(), metadata.offset())),
            parent));
    scheduleFlush();
  }

  private void scheduleFlush() {
    if (!flushing.compareAndSet(false, true)) {
      return;
    }
    try {
      executor.execute(this::flushPublished);
    } catch (RejectedExecutionException exception) {
      flushing.set(false);
      abandonPublished();
    }
  }

  private void flushPublished() {
    try {
      List<Completed> batch;
      while (!(batch = nextBatch()).isEmpty()) {
        persistPublished(batch);
      }
    } finally {
      flushing.set(false);
      // 마지막 조회와 종료 사이에 들어온 결과를 놓치지 않는다.
      if (!published.isEmpty()) {
        scheduleFlush();
      }
    }
  }

  private List<Completed> nextBatch() {
    var batch = new ArrayList<Completed>();
    Completed next;
    while (batch.size() < MAX_PUBLISHED_BATCH && (next = published.poll()) != null) {
      batch.add(next);
    }
    return batch;
  }

  private void persistPublished(List<Completed> batch) {
    // 이벤트마다 자기 전송 구간을 부모로 결과 구간을 열고, 같은 저장 트랜잭션이 커밋·롤백된 뒤 함께 닫는다.
    var scopes = new ArrayList<AsyncObservation.Scope>(batch.size());
    try {
      for (var completed : batch) {
        scopes.add(
            observations.start(
                RESULT_OPERATION, completed.parent(), ids(completed.published().delivery()), null));
      }
      Set<UUID> updated = service.markPublished(batch.stream().map(Completed::published).toList());
      for (var completed : batch) {
        var delivery = completed.published().delivery();
        if (!updated.contains(delivery.id())) {
          logStale(delivery);
        }
      }
    } catch (RuntimeException exception) {
      scopes.forEach(scope -> scope.error(exception));
      LOG.atError()
          .addKeyValue("event", "outbox.persist-result")
          .addKeyValue("count", batch.size())
          .addKeyValue("firstEventId", batch.getFirst().published().delivery().id())
          .log("Outbox result persistence failed; the original events remain recoverable");
    } finally {
      for (int i = scopes.size() - 1; i >= 0; i--) {
        scopes.get(i).close();
      }
      inFlight.addAndGet(-batch.size());
      wakeUp();
    }
  }

  private void persistFailure(Delivery delivery, Throwable failure) {
    try {
      if (!service.markFailed(delivery, maxAttempts)) {
        logStale(delivery);
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

  private static Map<String, String> ids(Delivery delivery) {
    return Map.of("event.id", delivery.id().toString());
  }

  private static void logStale(Delivery delivery) {
    LOG.atDebug()
        .addKeyValue("event", "outbox.stale-result")
        .addKeyValue("eventId", delivery.id())
        .log("Outbox lease no longer owns the result");
  }

  /** 저장 작업을 맡길 실행기가 멈췄다. 저장하지 못한 결과는 임대 만료 뒤 다시 발행된다. */
  private void abandonPublished() {
    accepting = false;
    Completed next;
    while ((next = published.poll()) != null) {
      inFlight.decrementAndGet();
      LOG.atWarn()
          .addKeyValue("event", "outbox.executor-stopped")
          .addKeyValue("eventId", next.published().delivery().id())
          .log(
              "Outbox dispatch stopped; unpersisted results will recover after their leases expire");
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

  /** 전송 성공 결과와 그 전송 구간의 traceparent. */
  private record Completed(Published published, String parent) {}

  @PreDestroy
  public void stop() {
    accepting = false;
    requested.set(false);
  }
}
