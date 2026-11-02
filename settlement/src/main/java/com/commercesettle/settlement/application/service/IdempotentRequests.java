package com.commercesettle.settlement.application.service;

import com.commercesettle.settlement.application.exception.IdempotencyConflictException;
import com.commercesettle.settlement.application.port.IdempotencyStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 운영 명령의 멱등 실행. 기록과 업무 변경을 호출자의 트랜잭션에 함께 저장하므로 실패하면 둘 다 롤백되고 같은 키로 다시 시도할 수 있다. 금융 작업이라 기록은 만료시키지
 * 않는다. 키와 본문 원문은 저장하지 않고 SHA-256 해시만 저장한다.
 */
@Component
public class IdempotentRequests {
  private final IdempotencyStore store;
  private final Clock clock;

  public IdempotentRequests(IdempotencyStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /**
   * 같은 키·본문의 완료 기록이 있으면 {@code replay}로 그 리소스의 현재 상태를 돌려준다. 없으면 {@code firstRun}을 실행하고 만든·바꾼 리소스로
   * 기록을 남긴다. 같은 키의 동시 요청은 기록 저장에서 하나만 남고 나머지는 롤백된다.
   *
   * @param resourceOf 결과에서 리소스 ID를 꺼낸다. 배치처럼 실행해야 ID가 정해지는 리소스가 있어 실행 뒤에 기록한다
   */
  public <T> T execute(
      Request request,
      int responseStatus,
      Supplier<T> firstRun,
      Function<T, UUID> resourceOf,
      Function<UUID, T> replay) {
    IdempotencyStore.Scope scope =
        new IdempotencyStore.Scope(
            request.actorKind(), request.actorId(), request.operation(), sha256(request.key()));
    String requestHash =
        sha256(
            fingerprint(
                request.body().stream().map(value -> value == null ? "N" : "V" + value).toList()));
    Optional<IdempotencyStore.CompletedRequest> existing = store.find(scope);
    if (existing.isPresent()) {
      if (!existing.get().requestHash().equals(requestHash)) {
        throw new IdempotencyConflictException("same key was used with a different request");
      }
      return replay.apply(existing.get().resourceId());
    }
    T result = firstRun.get();
    store.saveCompleted(
        scope, requestHash, resourceOf.apply(result), responseStatus, clock.instant());
    return result;
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
