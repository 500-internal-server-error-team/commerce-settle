package com.commercesettle.gateway.filter;

import java.util.UUID;

/** 세션 검증 스크립트의 판정. 인증되면 계정 종류와 ID를 가진다. */
public record SessionVerification(Outcome outcome, String actorKind, UUID actorId) {
  public enum Outcome {
    AUTHENTICATED,
    UNAUTHENTICATED,
    UNAVAILABLE
  }

  public static SessionVerification authenticated(String actorKind, UUID actorId) {
    return new SessionVerification(Outcome.AUTHENTICATED, actorKind, actorId);
  }

  public static SessionVerification unauthenticated() {
    return new SessionVerification(Outcome.UNAUTHENTICATED, null, null);
  }

  public static SessionVerification unavailable() {
    return new SessionVerification(Outcome.UNAVAILABLE, null, null);
  }

  static SessionVerification parse(String verdict) {
    final var parts = verdict.split(":", -1);
    return switch (parts[0]) {
      case "AUTHENTICATED" ->
          parts.length == 3 ? authenticated(parts[1], UUID.fromString(parts[2])) : unavailable();
      case "UNAUTHENTICATED" -> unauthenticated();
      default -> unavailable();
    };
  }
}
