package com.commercesettle.payment.application.port;

import java.util.UUID;

/** 선점한 WAITING 행의 원문을 이벤트로 읽을 수 없다. 다시 시도해도 같은 결과이므로 처리기는 이 행을 확인 대상으로 넘긴다. */
public class UnreadableInboxEventException extends RuntimeException {
  private final UUID inboxId;
  private final long version;
  private final int attempts;

  public UnreadableInboxEventException(UUID inboxId, long version, int attempts) {
    // 원문을 메시지에 싣지 않는다.
    super("Stored inbox body cannot be read: " + inboxId);
    this.inboxId = inboxId;
    this.version = version;
    this.attempts = attempts;
  }

  public UUID inboxId() {
    return inboxId;
  }

  public long version() {
    return version;
  }

  public int attempts() {
    return attempts;
  }
}
