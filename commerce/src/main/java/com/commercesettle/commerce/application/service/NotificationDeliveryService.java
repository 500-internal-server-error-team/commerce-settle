package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.port.NotificationGateway;
import com.commercesettle.commerce.application.port.NotificationStore;
import com.commercesettle.commerce.domain.model.Notification;
import com.commercesettle.commerce.domain.model.Notification.Status;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 임대와 결과 저장만 짧은 트랜잭션으로 실행한다. 발송·조회 중에는 커넥션을 보유하지 않는다. */
public final class NotificationDeliveryService {
  private static final Logger LOGGER = LoggerFactory.getLogger(NotificationDeliveryService.class);
  private final NotificationStore store;
  private final NotificationGateway gateway;
  private final ExecutorService calls;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public NotificationDeliveryService(
      NotificationStore store,
      NotificationGateway gateway,
      ExecutorService calls,
      Clock clock,
      PlatformTransactionManager manager) {
    this.store = store;
    this.gateway = gateway;
    this.calls = calls;
    this.clock = clock;
    transactions = new TransactionTemplate(manager);
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public boolean processNext() {
    long started = System.nanoTime();
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new IllegalStateException("Notification delivery must start outside a transaction");
    var claim = transactions.execute(status -> store.claim(clock.instant()));
    if (claim == null || !claim.processed()) return false;
    if (claim.expired() != null)
      log(
          claim.expired(),
          "notification.lease.expired",
          "UNKNOWN",
          "NOTIFICATION_LEASE_EXPIRED",
          started);
    var lease = claim.lease();
    if (lease == null) {
      log(
          claim.notification(),
          claim.notification().retryCount() >= 10
              ? "notification.exhausted"
              : "notification.review.required",
          "REVIEW_REQUIRED",
          claim.notification().lastErrorCode(),
          started);
      return true;
    }
    var n = lease.notification();
    log(n, "notification.claim", "SENDING", null, started);
    Status result = Status.RETRY;
    String code = null;
    String attemptStatus = "SUCCEEDED";
    Instant sentAt = null;
    Future<NotificationGateway.Receipt> pending = null;
    try {
      if (n.channel() == Notification.Channel.KAKAO_SELF && lease.operation().equals("LOOKUP"))
        throw new NotificationGateway.Failure("KAKAO_LOOKUP_UNAVAILABLE", true, false);
      pending =
          calls.submit(
              () ->
                  lease.operation().equals("LOOKUP")
                      ? gateway.lookup(n.id(), n.attemptCount())
                      : gateway.send(n, n.attemptCount()));
      var receipt = pending.get(5, TimeUnit.SECONDS);
      if (receipt == null) {
        if (!lease.operation().equals("LOOKUP"))
          throw new NotificationGateway.Failure("NOTIFICATION_RESPONSE_UNKNOWN", false, true);
        code = "NOTIFICATION_RECEIPT_NOT_FOUND";
      } else {
        if (!receipt.deliveryId().equals(n.id())
            || !receipt.requestHash().equals(gateway.requestHash(n)))
          throw new NotificationGateway.Failure("NOTIFICATION_BODY_CONFLICT", true, false);
        result = receipt.result().equals("ACCEPTED") ? Status.SENT : Status.REVIEW_REQUIRED;
        sentAt = result == Status.SENT ? receipt.processedAt() : null;
        if (result == Status.REVIEW_REQUIRED) {
          code = "NOTIFICATION_REJECTED";
          attemptStatus = "PERMANENT_FAILURE";
        }
      }
    } catch (Exception e) {
      if (pending != null) pending.cancel(true);
      Throwable cause = e instanceof java.util.concurrent.ExecutionException ? e.getCause() : e;
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      if (cause instanceof NotificationGateway.Failure failure) {
        result =
            failure.unknown()
                ? unknownStatus(n)
                : failure.permanent() ? Status.REVIEW_REQUIRED : Status.RETRY;
        code = failure.code();
        attemptStatus =
            failure.unknown()
                ? "UNKNOWN"
                : failure.permanent() ? "PERMANENT_FAILURE" : "RETRYABLE_FAILURE";
      } else {
        result = unknownStatus(n);
        code = "NOTIFICATION_RESPONSE_UNKNOWN";
        attemptStatus = "UNKNOWN";
      }
    }
    Status finalResult = result;
    String finalCode = code;
    String finalAttempt = attemptStatus;
    Instant finalSentAt = sentAt;
    var committed =
        transactions.execute(
            status ->
                store.finish(
                    lease, finalResult, finalCode, finalSentAt, finalAttempt, clock.instant()));
    if (committed != null)
      log(
          committed,
          lease.operation().equals("LOOKUP") ? "notification.lookup" : "notification.send",
          finalAttempt.equals("UNKNOWN") ? "UNKNOWN" : committed.status().name(),
          committed.lastErrorCode(),
          started);
    return true;
  }

  private static Status unknownStatus(Notification n) {
    return n.channel() == Notification.Channel.KAKAO_SELF ? Status.REVIEW_REQUIRED : Status.UNKNOWN;
  }

  private static void log(
      Notification notification, String event, String result, String code, long started) {
    Level level =
        notification.status() == Status.REVIEW_REQUIRED
                && !"NOTIFICATION_REJECTED".equals(notification.lastErrorCode())
            ? Level.ERROR
            : result.equals("UNKNOWN") || result.equals("RETRY") ? Level.WARN : Level.INFO;
    var entry =
        LOGGER
            .atLevel(level)
            .addKeyValue("event", event)
            .addKeyValue("deliveryId", notification.id())
            .addKeyValue("attempt", notification.attemptCount())
            .addKeyValue("result", result)
            .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    if (code != null) entry.addKeyValue("errorCode", code);
    entry.log("Notification delivery state committed");
  }
}
