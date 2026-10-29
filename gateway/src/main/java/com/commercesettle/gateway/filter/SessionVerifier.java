package com.commercesettle.gateway.filter;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Mono;

/**
 * Redis의 세션과 계정별 인증 가드를 읽기 전용 스크립트로 확인한다.
 *
 * <p>Spring 템플릿에는 {@code EVALSHA_RO}가 없어 Lettuce를 직접 쓴다. 스크립트는 미리 적재하지 않고, {@code NOSCRIPT}이면 같은
 * 요청에서 {@code EVAL_RO}로 한 번 실행해 캐시에 넣는다. 그 밖의 오류는 재시도하지 않고 판정 불가로 돌려준다.
 */
public class SessionVerifier implements DisposableBean {
  private static final Logger LOGGER = LoggerFactory.getLogger(SessionVerifier.class);
  private static final String SESSION_KEY_PREFIX = "commerce-settle:auth:session:";
  private static final byte[] SCRIPT = script();
  private static final String SCRIPT_SHA = HexFormat.of().formatHex(digest("SHA-1", SCRIPT));

  private final RedisClient client;
  private final RedisURI uri;
  private final String epoch;
  private final AtomicReference<StatefulRedisConnection<String, String>> connection =
      new AtomicReference<>();

  public SessionVerifier(RedisClient client, RedisURI uri, UUID epoch) {
    this.client = client;
    this.uri = uri;
    this.epoch = epoch.toString();
  }

  /** 토큰 원문의 SHA-256 16진수로 세션을 찾는다. */
  public Mono<SessionVerification> verify(String tokenHash) {
    final String[] keys = {SESSION_KEY_PREFIX + epoch + ":" + tokenHash};
    return connection()
        .flatMap(
            current -> {
              final var commands = current.reactive();
              return commands
                  .<String>evalshaReadOnly(SCRIPT_SHA, ScriptOutputType.VALUE, keys, epoch)
                  .next()
                  .onErrorResume(
                      RedisNoScriptException.class,
                      missing ->
                          commands
                              .<String>evalReadOnly(SCRIPT, ScriptOutputType.VALUE, keys, epoch)
                              .next());
            })
        .map(SessionVerification::parse)
        .defaultIfEmpty(SessionVerification.unavailable())
        .onErrorResume(
            error -> {
              // 503 응답은 GatewayExceptionHandler가 WARN으로 기록하므로 원인은 진단용으로만 남긴다.
              LOGGER
                  .atDebug()
                  .addKeyValue("event", "gateway.session.verification")
                  .addKeyValue("result", "UNAVAILABLE")
                  .addKeyValue("errorType", error.getClass().getName())
                  .log("Session store verification failed");
              return Mono.just(SessionVerification.unavailable());
            });
  }

  // 연결은 한 번 만들어 공유한다. 끊기면 Lettuce가 다시 연결하고, 그동안의 명령은 시간 초과로 실패한다.
  private Mono<StatefulRedisConnection<String, String>> connection() {
    return Mono.defer(
        () -> {
          final var current = connection.get();
          if (current != null) {
            return Mono.just(current);
          }
          return Mono.fromCompletionStage(client.connectAsync(StringCodec.UTF8, uri))
              .map(
                  created -> {
                    if (connection.compareAndSet(null, created)) {
                      return created;
                    }
                    created.closeAsync();
                    return connection.get();
                  });
        });
  }

  @Override
  public void destroy() {
    final var current = connection.getAndSet(null);
    if (current != null) {
      current.close();
    }
  }

  private static byte[] script() {
    try (var input = new ClassPathResource("redis/verify-session.lua").getInputStream()) {
      return input.readAllBytes();
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  static byte[] digest(String algorithm, byte[] value) {
    try {
      return MessageDigest.getInstance(algorithm).digest(value);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
