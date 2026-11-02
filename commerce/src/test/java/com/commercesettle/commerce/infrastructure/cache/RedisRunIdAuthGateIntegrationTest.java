package com.commercesettle.commerce.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.commercesettle.commerce.application.exception.AuthenticationUnavailableException;
import com.commercesettle.commerce.infrastructure.config.AuthProperties;
import io.lettuce.core.KillArgs;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 게이트 판정은 연결 이벤트와 INFO server에 달려 있으므로 compose와 같은 ACL의 commerce 사용자로 실제 Redis에서 확인한다.
@Testcontainers
@Timeout(60)
class RedisRunIdAuthGateIntegrationTest {
  private static final String ADMIN_PASSWORD = "admin-password";
  private static final String COMMERCE_PASSWORD = "commerce-password";

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:8.10.2-alpine")
          .withExposedPorts(6379)
          .withCommand(
              "redis-server",
              "--requirepass",
              ADMIN_PASSWORD,
              "--user",
              "commerce",
              "on",
              ">" + COMMERCE_PASSWORD,
              "resetkeys",
              "~commerce-settle:auth:*",
              "resetchannels",
              "-@all",
              "+@connection",
              "+@read",
              "+@write",
              "+eval",
              "+evalsha",
              "-@dangerous",
              "-@admin",
              "-function",
              "-scan",
              "-randomkey",
              "-dbsize",
              "-select",
              "+info",
              "+time");

  private static RedisClient client;
  private static StatefulRedisConnection<String, String> admin;
  private static String runId;
  private final List<Runnable> cleanups = new ArrayList<>();
  private LettuceConnectionFactory lastFactory;

  @BeforeAll
  static void connect() {
    client = RedisClient.create();
    admin =
        client.connect(
            RedisURI.builder()
                .withHost(REDIS.getHost())
                .withPort(REDIS.getMappedPort(6379))
                .withPassword(ADMIN_PASSWORD.toCharArray())
                .build());
    runId = RunIdGate.runId(admin.sync().info("server"));
  }

  @AfterEach
  void stopGates() {
    cleanups.forEach(Runnable::run);
    cleanups.clear();
  }

  @AfterAll
  static void disconnect() {
    admin.close();
    client.shutdown();
  }

  @Test
  @DisplayName("기동 뒤 run_id를 확인하기 전에는 거절하고, 같으면 연다")
  void opensWhenRunIdMatches() {
    var gate = gate(runId);

    await().atMost(Duration.ofSeconds(10)).ignoreExceptions().untilAsserted(gate::requireOpen);
  }

  @Test
  @DisplayName("승인한 run_id와 실제 Redis가 다르면 계속 503으로 거절한다")
  void staysClosedOnMismatch() {
    var gate = gate("0".repeat(40));

    await()
        .during(Duration.ofSeconds(2))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThatThrownBy(gate::requireOpen)
                    .isInstanceOf(AuthenticationUnavailableException.class));
  }

  @Test
  @DisplayName("연 뒤 연결이 끊기면 Lettuce가 같은 Redis에 다시 붙어도 닫은 채로 둔다")
  void staysClosedAfterReconnect() {
    var gate = gate(runId);
    await().atMost(Duration.ofSeconds(10)).ignoreExceptions().untilAsserted(gate::requireOpen);

    admin.sync().clientKill(KillArgs.Builder.user("commerce"));

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThatThrownBy(gate::requireOpen)
                    .isInstanceOf(AuthenticationUnavailableException.class));
    // 재연결된 공유 연결로 명령이 다시 성공하는 것을 확인한 뒤에도 열리지 않아야 한다.
    await()
        .atMost(Duration.ofSeconds(10))
        .ignoreExceptions()
        .until(() -> template(lastFactory).hasKey("commerce-settle:auth:probe") != null);
    assertThatThrownBy(gate::requireOpen).isInstanceOf(AuthenticationUnavailableException.class);
  }

  @Test
  @DisplayName("커머스만 재시작하고 run_id가 같으면 다시 연다")
  void reopensAfterProcessRestartWithSameRunId() {
    var first = gate(runId);
    await().atMost(Duration.ofSeconds(10)).ignoreExceptions().untilAsserted(first::requireOpen);
    stopGates();

    var restarted = gate(runId);

    await().atMost(Duration.ofSeconds(10)).ignoreExceptions().untilAsserted(restarted::requireOpen);
    assertThatCode(restarted::requireOpen).doesNotThrowAnyException();
  }

  private RedisRunIdAuthGate gate(String approvedRunId) {
    var configuration =
        new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
    configuration.setUsername("commerce");
    configuration.setPassword(COMMERCE_PASSWORD);
    var factory = new LettuceConnectionFactory(configuration);
    factory.afterPropertiesSet();
    factory.start();
    lastFactory = factory;
    var gate = new RedisRunIdAuthGate(properties(approvedRunId), factory, template(factory));
    gate.start();
    cleanups.add(
        () -> {
          gate.stop();
          factory.destroy();
        });
    return gate;
  }

  private static StringRedisTemplate template(LettuceConnectionFactory factory) {
    var template = new StringRedisTemplate(factory);
    template.afterPropertiesSet();
    return template;
  }

  private static AuthProperties properties(String approvedRunId) {
    return new AuthProperties(
        UUID.randomUUID(),
        approvedRunId,
        new AuthProperties.RateLimit("k".repeat(32), 5, 900, 3, 3600),
        new AuthProperties.PasswordReset("d".repeat(32)));
  }
}
