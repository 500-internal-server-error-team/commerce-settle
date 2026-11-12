package com.commercesettle.commerce.domain.model;

import java.time.Instant;
import java.util.UUID;

/** 수신자·채널 계정과 렌더된 본문을 고정한다. 금융 상태를 변경하지 않는다. */
public record Notification(
    UUID id,
    Kind kind,
    ActorKind recipientKind,
    UUID recipientId,
    Channel channel,
    String channelAccountRef,
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
    SHIPMENT_DELIVERED,
    PURCHASE_CONFIRMED,
    CANCELLATION_COMPLETED,
    RETURN_REQUESTED,
    RETURN_DECIDED,
    RETURN_RECEIVED,
    REFUND_COMPLETED,
    REFUND_ACTION_REQUIRED,
    SALE_COMPLETED,
    SETTLEMENT_READY,
    PAYOUT_COMPLETED,
    PAYOUT_ACTION_REQUIRED
  }

  public enum Channel {
    MOCK_KAKAO,
    KAKAO_SELF
  }

  public record Destination(Channel channel, String accountRef) {
    public Destination {
      if (channel == null
          || (channel == Channel.MOCK_KAKAO && accountRef != null)
          || (channel == Channel.KAKAO_SELF
              && (accountRef == null || !accountRef.matches("[0-9a-f]{64}")))) {
        throw new IllegalArgumentException("Invalid notification destination");
      }
    }

    public static Destination mock() {
      return new Destination(Channel.MOCK_KAKAO, null);
    }
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
    return (channel == Channel.MOCK_KAKAO ? "mock:" : "kakao-self:" + channelAccountRef + ":")
        + recipientKind
        + ":"
        + recipientId;
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
