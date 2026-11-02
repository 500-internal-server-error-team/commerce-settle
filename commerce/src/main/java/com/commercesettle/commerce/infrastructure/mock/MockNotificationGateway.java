package com.commercesettle.commerce.infrastructure.mock;

import com.commercesettle.commerce.application.port.NotificationGateway;
import com.commercesettle.commerce.domain.model.Notification;
import com.commercesettle.commerce.infrastructure.persistence.notification.NotificationReceiptStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** 별도 HTTP 서버 없이 모의 수신 원결과만 독립 커밋한다. 지연 중에는 DB를 점유하지 않는다. */
public final class MockNotificationGateway implements NotificationGateway {
  private final NotificationReceiptStore receipts;
  private final ObjectMapper mapper = new ObjectMapper();
  private final Clock clock;
  private final long seed;
  private final long delayMs;
  private final int temporary;
  private final int permanent;
  private final int loss;

  public MockNotificationGateway(
      NotificationReceiptStore receipts,
      Clock clock,
      long seed,
      long delayMs,
      int temporary,
      int permanent,
      int loss) {
    if (delayMs < 0
        || delayMs > 60000
        || temporary < 0
        || temporary > 1000
        || permanent < 0
        || permanent > 1000
        || loss < 0
        || loss > 1000) throw new IllegalArgumentException("Invalid mock notification settings");
    this.receipts = receipts;
    this.clock = clock;
    this.seed = seed;
    this.delayMs = delayMs;
    this.temporary = temporary;
    this.permanent = permanent;
    this.loss = loss;
  }

  @Override
  public Receipt send(Notification notification, int attempt) {
    requireOutsideTransaction();
    var existing = receipts.find(notification.id());
    String hash = requestHash(notification);
    if (existing != null) {
      if (!existing.requestHash().equals(hash))
        throw new Failure("NOTIFICATION_BODY_CONFLICT", true, false);
      return existing;
    }
    fault(notification.id(), attempt, "SEND");
    String result = score(notification.id(), 0, "RESULT") < permanent ? "REJECTED" : "ACCEPTED";
    var receipt = receipts.save(notification.id(), hash, result, clock.instant());
    if (score(notification.id(), attempt, "LOSS") < loss)
      throw new Failure("NOTIFICATION_RESPONSE_UNKNOWN", false, true);
    return receipt;
  }

  @Override
  public Receipt lookup(UUID deliveryId, int attempt) {
    requireOutsideTransaction();
    if (attempt > 0) fault(deliveryId, attempt, "LOOKUP");
    return receipts.find(deliveryId);
  }

  @Override
  public String requestHash(Notification notification) {
    try {
      byte[] body =
          mapper
              .writer()
              .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
              .writeValueAsBytes(
                  Map.of(
                      "channel",
                      "MOCK_KAKAO",
                      "recipient",
                      notification.recipient(),
                      "kind",
                      notification.kind().name(),
                      "templateVersion",
                      notification.templateVersion(),
                      "title",
                      notification.title(),
                      "body",
                      notification.body()));
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("Notification hash unavailable", e);
    }
  }

  private void fault(UUID id, int attempt, String operation) {
    requireOutsideTransaction();
    try {
      Thread.sleep(delayMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Failure("NOTIFICATION_RESPONSE_UNKNOWN", false, true);
    }
    if (score(id, attempt, operation) < temporary)
      throw new Failure("NOTIFICATION_TEMPORARILY_UNAVAILABLE", false, operation.equals("LOOKUP"));
  }

  private static void requireOutsideTransaction() {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new IllegalStateException("Notification calls must run outside a transaction");
  }

  private int score(UUID id, int attempt, String operation) {
    try {
      byte[] bytes =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  (seed + ":" + id + ":" + attempt + ":" + operation)
                      .getBytes(StandardCharsets.UTF_8));
      return ((bytes[0] & 255) * 256 + (bytes[1] & 255)) % 1000;
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
