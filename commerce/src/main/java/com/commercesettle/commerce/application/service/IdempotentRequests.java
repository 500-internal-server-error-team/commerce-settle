package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.exception.IdempotencyConflictException;
import com.commercesettle.commerce.application.port.IdempotencyStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * 요청의 멱등 실행. 기록과 업무 변경을 호출자의 트랜잭션에 함께 저장하므로 실패하면 둘 다 롤백되고 같은 키로 다시 시도할 수 있다. 키와 본문 원문은 저장하지 않고
 * SHA-256 해시만 저장한다.
 */
@Component
public class IdempotentRequests {
  /** 일반 관리 작업의 키 보존 기간. */
  static final Duration GENERAL_RETENTION = Duration.ofHours(24);

  private final IdempotencyStore store;
  private final Clock clock;

  public IdempotentRequests(IdempotencyStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /**
   * 같은 키·본문의 완료 기록이 있으면 {@code replay}로 기존 리소스를 돌려준다. 없으면 기록을 먼저 저장해 같은 키의 동시 요청을 막은 뒤 {@code
   * firstRun}을 실행한다.
   *
   * @param resourceId 이 요청이 만들거나 바꾸는 리소스 ID
   * @param firstRun 멱등 기록 ID를 업무 참조로 받아 실제 변경을 수행한다
   */
  public <T> T execute(
      Request request,
      UUID resourceId,
      int responseStatus,
      Function<UUID, T> firstRun,
      Function<UUID, T> replay) {
    return execute(request, resourceId, responseStatus, firstRun, replay, GENERAL_RETENTION);
  }

  /** 주문·금융 키는 거래 이력과 함께 보존하며 만료시키지 않는다. */
  public <T> T executePermanent(
      Request request,
      UUID resourceId,
      int responseStatus,
      Function<UUID, T> firstRun,
      Function<UUID, T> replay) {
    return execute(request, resourceId, responseStatus, firstRun, replay, null);
  }

  private <T> T execute(
      Request request,
      UUID resourceId,
      int responseStatus,
      Function<UUID, T> firstRun,
      Function<UUID, T> replay,
      Duration retention) {
    IdempotencyStore.Scope scope =
        new IdempotencyStore.Scope(
            request.actorKind(), request.actorId(), request.operation(), sha256(request.key()));
    // 기존 24시간 키의 해시는 유지한다. 새 영구 키는 null과 문자열 "null"을 구별한다.
    List<?> body =
        retention == null
            ? request.body().stream().map(value -> value == null ? "N" : "V" + value).toList()
            : request.body();
    String requestHash = sha256(fingerprint(body));
    Instant now = clock.instant();

    Optional<IdempotencyStore.CompletedRequest> existing = store.find(scope);
    if (existing.isPresent()) {
      IdempotencyStore.CompletedRequest completed = existing.get();
      if (completed.expiresAt() != null && !completed.expiresAt().isAfter(now)) {
        store.delete(scope);
      } else if (completed.requestHash().equals(requestHash)) {
        return replay.apply(completed.resourceId());
      } else {
        throw new IdempotencyConflictException("same key was used with a different request");
      }
    }

    UUID recordId = UUID.randomUUID();
    store.saveCompleted(
        recordId,
        scope,
        requestHash,
        resourceId,
        responseStatus,
        now,
        retention == null ? null : now.plus(retention));
    return firstRun.apply(recordId);
  }

  /** 길이를 앞에 붙여 값의 경계가 섞이지 않게 한다. */
  static String fingerprint(List<?> values) {
    StringBuilder text = new StringBuilder();
    for (Object value : values) {
      String part = String.valueOf(value);
      text.append(part.length()).append(':').append(part).append(';');
    }
    return text.toString();
  }

  static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the JDK", e);
    }
  }

  /**
   * @param body 리소스 ID를 포함해 요청 의미를 결정하는 값들. 순서도 비교에 포함된다.
   */
  public record Request(
      String actorKind, String actorId, String operation, String key, List<?> body) {
    /** 멱등키 원문과 요청 본문 값은 로그·예외 메시지에 남기지 않는다. */
    @Override
    public String toString() {
      return "Request[actorKind=%s, actorId=%s, operation=%s, key=<redacted>, body=<%d values>]"
          .formatted(actorKind, actorId, operation, body == null ? 0 : body.size());
    }
  }
}
