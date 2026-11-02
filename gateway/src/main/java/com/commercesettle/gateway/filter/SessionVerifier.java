package com.commercesettle.gateway.filter;

import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.io.ClassPathResource;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * Redis의 세션과 계정별 인증 가드를 읽기 전용 스크립트로 확인한다.
 *
 * <p>Spring 템플릿에는 {@code EVALSHA_RO}가 없어 Lettuce를 직접 쓴다. 스크립트는 미리 적재하지 않고, {@code NOSCRIPT}이면 같은
 * 요청에서 {@code EVAL_RO}로 한 번 실행해 캐시에 넣는다. 그 밖의 오류는 재시도하지 않고 판정 불가로 돌려준다.
 *
 * <p>기동하면 같은 연결로 {@code INFO server}의 run_id를 확인해 {@link RunIdGate}를 열 때까지 Redis를 쓰지 않고 판정 불가다.
 * 게이트가 닫히면 재연결이나 스크립트 재적재가 성공해도 다시 열지 않는다.
 */
public class SessionVerifier implements SmartLifecycle, DisposableBean {
  private static final Logger LOGGER = LoggerFactory.getLogger(SessionVerifier.class);
  private static final String SESSION_KEY_PREFIX = "commerce-settle:auth:session:";
  private static final byte[] SCRIPT = script();
  private static final String SCRIPT_SHA = HexFormat.of().formatHex(digest("SHA-1", SCRIPT));
  private static final Duration RUN_ID_CHECK_INTERVAL = Duration.ofSeconds(1);

  private final RedisClient client;
  private final RedisURI uri;
  private final String epoch;
  private final RunIdGate gate;
  private final AtomicReference<StatefulRedisConnection<String, String>> connection =
      new AtomicReference<>();
  private volatile Disposable runIdCheck;

  public SessionVerifier(RedisClient client, RedisURI uri, UUID epoch, RunIdGate gate) {
    this.client = client;
    this.uri = uri;
    this.epoch = epoch.toString();
    this.gate = gate;
    // 끊긴 연결은 Lettuce가 자동으로 다시 붙이므로 그 전에 이벤트 루프에서 바로 닫는다. 직접 닫은 연결은 단절로 보지 않는다.
    client.addListener(
        new RedisConnectionStateListener() {
          @Override
          public void onRedisDisconnected(RedisChannelHandler<?, ?> handler) {
            if (!handler.isClosed()) {
              gate.disconnected();
            }
          }
        });
  }

  /** 토큰 원문의 SHA-256 16진수로 세션을 찾는다. */
  public Mono<SessionVerification> verify(String tokenHash) {
    if (!gate.isOpen()) {
      return Mono.just(SessionVerification.unavailable());
    }
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

  /** 세션 검증과 같은 연결에서 run_id를 한 번 확인하고 게이트 상태를 돌려준다. 실패하면 오류로 끝나며 상태는 그대로다. */
  public Mono<RunIdGate.State> checkRunId() {
    return Mono.defer(
        () -> {
          final long disconnects = gate.disconnects();
          return connection()
              .flatMap(current -> current.reactive().info("server"))
              .map(info -> gate.observe(RunIdGate.runId(info), disconnects));
        });
  }

  /** 확인 전 상태가 끝날 때까지 run_id 확인을 반복한다. Redis가 Gateway보다 늦게 떠도 연결되면 열린다. */
  @Override
  public void start() {
    runIdCheck =
        checkRunId()
            .onErrorResume(
                error -> {
                  LOGGER
                      .atDebug()
                      .addKeyValue("event", "auth.redis-gate")
                      .addKeyValue("errorType", error.getClass().getName())
                      .log("Redis run_id check failed; retrying");
                  return Mono.empty();
                })
            .filter(state -> state != RunIdGate.State.CHECKING)
            .repeatWhenEmpty(
                Integer.MAX_VALUE, attempts -> attempts.delayElements(RUN_ID_CHECK_INTERVAL))
            .subscribe();
  }

  @Override
  public void stop() {
    final var current = runIdCheck;
    if (current != null) {
      current.dispose();
    }
  }

  @Override
  public boolean isRunning() {
    final var current = runIdCheck;
    return current != null && !current.isDisposed();
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
