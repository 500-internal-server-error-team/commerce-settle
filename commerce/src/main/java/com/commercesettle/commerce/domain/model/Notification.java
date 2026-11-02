package com.commercesettle.commerce.domain.model;

import java.time.Instant;
import java.util.UUID;

/** 수신자와 렌더된 본문을 고정한 모의 발송. 금융 상태를 변경하지 않는다. */
public record Notification(
    UUID id,
    Kind kind,
    ActorKind recipientKind,
    UUID recipientId,
    String dedupKey,
    int itemCount,
    Instant occurredAt,
    long totalAmount,
    String templateVersion,
    String title,
    String body,
    Status status,
    int attemptCount,
    int retryCount,
    Instant nextRunAt,
    UUID leaseToken,
    Instant leaseUntil,
    String lastErrorCode,
    Instant createdAt,
    Instant sentAt,
    long version) {
  public enum Kind {
    PAYMENT_COMPLETED,
    SHIPMENT_STARTED,
    PURCHASE_CONFIRMED,
    CANCELLATION_COMPLETED,
    RETURN_REQUESTED,
    RETURN_DECIDED,
    REFUND_COMPLETED,
    REFUND_ACTION_REQUIRED,
    SALE_COMPLETED,
    SETTLEMENT_READY,
    PAYOUT_COMPLETED,
    PAYOUT_ACTION_REQUIRED
  }

  public enum Status {
    COLLECTING,
    PENDING,
    SENDING,
    UNKNOWN,
    RETRY,
    SENT,
    REVIEW_REQUIRED
  }

  public String recipient() {
    return "mock:" + recipientKind + ":" + recipientId;
  }

  public boolean replayable() {
    return status == Status.REVIEW_REQUIRED || status == Status.RETRY || status == Status.UNKNOWN;
  }

  public static final class StateConflictException extends RuntimeException {}

  public static final class ContentConflictException extends RuntimeException {
    public ContentConflictException(String message) {
      super(message);
    }

    public ContentConflictException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static long backoffSeconds(int retryCount) {
    return Math.min(60, 1L << Math.min(Math.max(retryCount - 1, 0), 6));
  }
}
