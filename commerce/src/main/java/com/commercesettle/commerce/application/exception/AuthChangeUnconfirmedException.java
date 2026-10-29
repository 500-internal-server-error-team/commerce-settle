package com.commercesettle.commerce.application.exception;

import java.util.UUID;

/** 가드를 막은 뒤 DB 커밋이나 Redis 확정 반영을 확인하지 못했다. DB 변경은 이미 커밋됐을 수 있으며, 가드는 같은 변경을 재조정할 때까지 막힌 상태다. */
public class AuthChangeUnconfirmedException extends RuntimeException {
  private final UUID changeId;

  public AuthChangeUnconfirmedException(UUID changeId, Throwable cause) {
    super("Account auth change was not confirmed", cause);
    this.changeId = changeId;
  }

  public UUID changeId() {
    return changeId;
  }
}
