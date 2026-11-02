package com.commercesettle.commerce.infrastructure.cache;

import com.commercesettle.commerce.application.exception.AuthenticationUnavailableException;
import com.commercesettle.commerce.application.port.AuthGate;
import com.commercesettle.commerce.infrastructure.config.AuthProperties;
import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.RedisConnectionStateListener;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 인증 스크립트가 쓰는 Spring Redis 연결에서 {@code INFO server}의 run_id와 단절을 보고 {@link RunIdGate}를 판정한다.
 *
 * <p>기동 뒤 확인 전 상태가 끝날 때까지 1초 간격으로 확인한다. Redis가 커머스보다 늦게 떠도 연결되면 열린다.
 */
@Component
public class RedisRunIdAuthGate implements AuthGate, SmartLifecycle {
  private static final Logger LOGGER = LoggerFactory.getLogger(RedisRunIdAuthGate.class);
  private static final long CHECK_INTERVAL_MILLIS = 1000;

  private final RunIdGate gate;
  private final LettuceConnectionFactory connections;
  private final StringRedisTemplate redis;
  private volatile ScheduledExecutorService checker;

  public RedisRunIdAuthGate(
      AuthProperties properties, LettuceConnectionFactory connections, StringRedisTemplate redis) {
    this.gate = new RunIdGate(properties.redisRunId());
    this.connections = connections;
    this.redis = redis;
  }

  @Override
  public void requireOpen() {
    if (!gate.isOpen()) {
      throw new AuthenticationUnavailableException("Redis auth gate is " + gate.state());
    }
  }

  @Override
  public void start() {
    // 끊긴 연결은 Lettuce가 자동으로 다시 붙이므로 그 전에 이벤트 루프에서 바로 닫는다. 직접 닫은 연결은 단절로 보지 않는다.
    connections
        .getRequiredNativeClient()
        .addListener(
            new RedisConnectionStateListener() {
              @Override
              public void onRedisDisconnected(RedisChannelHandler<?, ?> handler) {
                if (!handler.isClosed()) {
                  gate.disconnected();
                }
              }
            });
    final var started =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("auth-redis-run-id").daemon().factory());
    started.scheduleWithFixedDelay(
        () -> check(started), 0, CHECK_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    checker = started;
  }

  @Override
  public void stop() {
    final var current = checker;
    if (current != null) {
      current.shutdownNow();
    }
  }

  @Override
  public boolean isRunning() {
    final var current = checker;
    return current != null && !current.isShutdown();
  }

  private void check(ScheduledExecutorService self) {
    if (gate.state() != RunIdGate.State.CHECKING) {
      self.shutdown();
      return;
    }
    final long disconnects = gate.disconnects();
    try {
      final Properties info =
          redis.execute((RedisCallback<Properties>) c -> c.serverCommands().info("server"));
      gate.observe(info == null ? null : info.getProperty("run_id"), disconnects);
    } catch (RuntimeException error) {
      LOGGER
          .atDebug()
          .addKeyValue("event", "auth.redis-gate")
          .addKeyValue("errorType", error.getClass().getName())
          .log("Redis run_id check failed; retrying");
    }
  }
}
