package com.commercesettle.settlement.application.exception;

import java.util.UUID;

/** 대기 Inbox 한 건의 재판정이 예외로 롤백됐다. 처리기가 별도 트랜잭션에서 그 행의 다음 판정을 미룰 수 있게 ID를 싣는다. */
public class WaitingInboxFailedException extends RuntimeException {
  private final UUID inboxId;

  public WaitingInboxFailedException(UUID inboxId, RuntimeException cause) {
    super("Waiting inbox processing failed: " + inboxId, cause);
    this.inboxId = inboxId;
  }

  public UUID inboxId() {
    return inboxId;
  }
}
