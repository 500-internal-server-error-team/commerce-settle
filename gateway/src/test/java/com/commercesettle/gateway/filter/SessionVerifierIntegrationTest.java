package com.commercesettle.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.commercesettle.gateway.filter.RunIdGate.State;
import com.commercesettle.gateway.filter.SessionVerification.Outcome;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.KillArgs;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// Gateway 판정은 Lua 안에 있으므로 compose와 같은 ACL의 gateway 사용자로 실제 Redis에서 확인한다.
@Testcontainers
class SessionVerifierIntegrationTest {
  private static final String ADMIN_PASSWORD = "admin-password";
  private static final String GATEWAY_PASSWORD = "gateway-password";
  private static final UUID EPOCH = UUID.randomUUID();
  private static final String TOKEN_HASH = "a".repeat(64);
  private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:8.10.2-alpine")
          .withExposedPorts(6379)
          .withCommand(
              "redis-server",
              "--requirepass",
              ADMIN_PASSWORD,
              "--user",
              "gateway",
              "on",
              ">" + GATEWAY_PASSWORD,
              "resetkeys",
              "%R~commerce-settle:auth:session:*",
              "%R~commerce-settle:auth:subject:*",
              "resetchannels",
              "-@all",
              "+evalsha_ro",
              "+eval_ro",
              "+hmget",
              "+time",
              "+info");

  private static RedisClient client;
  private static StatefulRedisConnection<String, String> admin;
  private static String runId;
  // 게이트는 한 번 닫히면 다시 열리지 않으므로 시험마다 새 Gateway 연결을 만든다.
  private RedisClient gatewayClient;
  private SessionVerifier verifier;
  private RunIdGate gate;

  @BeforeAll
  static void connect() {
    client = client();
    admin =
        client.connect(
            RedisURI.builder()
                .withHost(REDIS.getHost())
                .withPort(REDIS.getMappedPort(6379))
                .withPassword(ADMIN_PASSWORD.toCharArray())
                .build());
    runId = RunIdGate.runId(admin.sync().info("server"));
  }

  @BeforeEach
  void openGate() {
    gatewayClient = client();
    verifier = verifier(runId);
    assertThat(verifier.checkRunId().block(Duration.ofSeconds(5))).isEqualTo(State.OPEN);
  }

  @AfterEach
  void clear() {
    verifier.destroy();
    gatewayClient.shutdown();
    admin.sync().flushall();
  }

  @AfterAll
  static void disconnect() {
    admin.close();
    client.shutdown();
  }

  @Test
  @DisplayName("유효한 세션과 ACTIVE 가드의 버전이 같으면 계정 종류와 ID를 돌려준다")
  void authenticatesActiveSession() {
    saveSession(session -> {});
    saveGuard(guard -> {});

    assertThat(verify()).isEqualTo(SessionVerification.authenticated("SELLER", ACTOR_ID));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejectedSessions")
  @DisplayName("세션이 없거나 만료·폐기됐으면 인증 실패로 판정한다")
  void rejectsInvalidSession(String scenario, Runnable arrange) {
    arrange.run();

    assertThat(verify().outcome()).isEqualTo(Outcome.UNAUTHENTICATED);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("undecidableSessions")
  @DisplayName("가드가 없거나 변경 중이거나 데이터가 손상됐으면 판정 불가로 처리한다")
  void refusesUndecidableSession(String scenario, Runnable arrange) {
    arrange.run();

    assertThat(verify().outcome()).isEqualTo(Outcome.UNAVAILABLE);
  }

  @Test
  @DisplayName("스크립트 캐시가 비워져도 EVAL_RO로 다시 적재해 같은 판정을 내린다")
  void reloadsScriptAfterFlush() throws IOException {
    saveSession(session -> {});
    saveGuard(guard -> {});
    verify();
    admin.sync().scriptFlush();

    final var result = verify();

    assertThat(result.outcome()).isEqualTo(Outcome.AUTHENTICATED);
    assertThat(admin.sync().scriptExists(scriptSha())).containsExactly(true);
  }

  @Test
  @DisplayName("Redis에 연결할 수 없으면 판정 불가로 처리한다")
  void refusesWhenRedisIsUnreachable() throws IOException {
    final int closedPort;
    try (var socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    final var unreachable =
        new SessionVerifier(gatewayClient, gatewayUri(closedPort), EPOCH, new RunIdGate(runId));

    assertThatThrownBy(() -> unreachable.checkRunId().block(Duration.ofSeconds(10)))
        .isInstanceOf(RedisConnectionException.class);
    assertThat(unreachable.verify(TOKEN_HASH).block(Duration.ofSeconds(10)).outcome())
        .isEqualTo(Outcome.UNAVAILABLE);
  }

  @Test
  @DisplayName("run_id를 확인하기 전에는 Redis를 읽지 않고 판정 불가로 처리한다")
  void refusesBeforeRunIdCheck() {
    saveSession(session -> {});
    saveGuard(guard -> {});
    final var unchecked = verifier(runId);

    assertThat(unchecked.verify(TOKEN_HASH).block(Duration.ofSeconds(5)).outcome())
        .isEqualTo(Outcome.UNAVAILABLE);
  }

  @Test
  @DisplayName("승인한 run_id와 실제 Redis가 다르면 게이트를 닫고 유효한 세션도 판정 불가로 처리한다")
  void closesOnRunIdMismatch() {
    saveSession(session -> {});
    saveGuard(guard -> {});
    final var mismatched = verifier("0".repeat(40));

    assertThat(mismatched.checkRunId().block(Duration.ofSeconds(5))).isEqualTo(State.CLOSED);
    assertThat(mismatched.verify(TOKEN_HASH).block(Duration.ofSeconds(5)).outcome())
        .isEqualTo(Outcome.UNAVAILABLE);
  }

  @Test
  @DisplayName("연 뒤 연결이 끊기면 재연결과 run_id 재확인이 성공해도 판정 불가를 유지한다")
  void staysClosedAfterReconnect() {
    saveSession(session -> {});
    saveGuard(guard -> {});
    assertThat(verify().outcome()).isEqualTo(Outcome.AUTHENTICATED);

    admin.sync().clientKill(KillArgs.Builder.user("gateway"));

    await().atMost(Duration.ofSeconds(5)).until(() -> gate.state() == State.CLOSED);
    // Lettuce가 다시 연결해 같은 Redis의 run_id를 읽어도 닫힌 게이트는 열리지 않는다.
    await()
        .atMost(Duration.ofSeconds(10))
        .ignoreExceptions()
        .until(() -> verifier.checkRunId().block(Duration.ofSeconds(2)) == State.CLOSED);
    assertThat(verify().outcome()).isEqualTo(Outcome.UNAVAILABLE);
  }

  @Test
  @DisplayName("Gateway만 재시작하고 run_id·세대가 같으면 기존 세션을 그대로 인증한다")
  void keepsSessionsAcrossGatewayRestart() {
    saveSession(session -> {});
    saveGuard(guard -> {});
    verifier.destroy();
    gatewayClient.shutdown();

    gatewayClient = client();
    verifier = verifier(runId);
    assertThat(verifier.checkRunId().block(Duration.ofSeconds(5))).isEqualTo(State.OPEN);

    assertThat(verify()).isEqualTo(SessionVerification.authenticated("SELLER", ACTOR_ID));
  }

  static Stream<Arguments> rejectedSessions() {
    return Stream.of(
        Arguments.of("세션 없음", (Runnable) () -> saveGuard(guard -> {})),
        Arguments.of(
            "다른 목적",
            (Runnable)
                () -> {
                  saveSession(session -> session.put("purpose", "PASSWORD_RESET"));
                  saveGuard(guard -> {});
                }),
        Arguments.of(
            "만료 시각 도달",
            (Runnable)
                () -> {
                  saveSession(session -> session.put("expiresAt", Long.toString(redisMillis())));
                  saveGuard(guard -> {});
                }),
        Arguments.of(
            "인증 버전 불일치",
            (Runnable)
                () -> {
                  saveSession(session -> {});
                  saveGuard(guard -> guard.put("authVersion", "1"));
                }),
        Arguments.of(
            "확정 차단 가드",
            (Runnable)
                () -> {
                  saveSession(session -> {});
                  saveGuard(guard -> guard.put("state", "BLOCKED"));
                }),
        Arguments.of(
            "다른 인증 세대",
            (Runnable)
                () -> {
                  admin
                      .sync()
                      .hset(
                          "commerce-settle:auth:session:" + UUID.randomUUID() + ":" + TOKEN_HASH,
                          sessionFields());
                  saveGuard(guard -> {});
                }));
  }

  static Stream<Arguments> undecidableSessions() {
    return Stream.of(
        Arguments.of("가드 없음", (Runnable) () -> saveSession(session -> {})),
        Arguments.of(
            "변경 중 가드",
            (Runnable)
                () -> {
                  saveSession(session -> {});
                  saveGuard(
                      guard -> {
                        guard.put("state", "BLOCKED");
                        guard.put("pendingChangeId", UUID.randomUUID().toString());
                      });
                }),
        Arguments.of(
            "손상된 세션",
            (Runnable)
                () -> {
                  saveSession(session -> session.remove("actorId"));
                  saveGuard(guard -> {});
                }),
        corruptedSession("expiresAt", "nan"),
        corruptedSession("expiresAt", "inf"),
        corruptedSession("expiresAt", "0x7fffffffffff"),
        corruptedSession("expiresAt", "9".repeat(16)),
        corruptedSession("authVersion", "nan"),
        Arguments.of(
            "손상된 가드 authVersion=nan",
            (Runnable)
                () -> {
                  saveSession(session -> {});
                  saveGuard(guard -> guard.put("authVersion", "nan"));
                }));
  }

  private static Arguments corruptedSession(String field, String value) {
    return Arguments.of(
        "손상된 세션 " + field + "=" + value,
        (Runnable)
            () -> {
              saveSession(session -> session.put(field, value));
              saveGuard(guard -> {});
            });
  }

  private SessionVerification verify() {
    return verifier.verify(TOKEN_HASH).block(Duration.ofSeconds(5));
  }

  private SessionVerifier verifier(String approvedRunId) {
    gate = new RunIdGate(approvedRunId);
    return new SessionVerifier(gatewayClient, gatewayUri(REDIS.getMappedPort(6379)), EPOCH, gate);
  }

  private static RedisClient client() {
    final var created = RedisClient.create();
    created.setOptions(
        ClientOptions.builder()
            .timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(2)))
            .build());
    return created;
  }

  private static void saveSession(Consumer<HashMap<String, String>> change) {
    final var fields = sessionFields();
    change.accept(fields);
    commands().hset("commerce-settle:auth:session:" + EPOCH + ":" + TOKEN_HASH, fields);
  }

  private static HashMap<String, String> sessionFields() {
    final var now = redisMillis();
    final var fields = new HashMap<String, String>();
    fields.put("actorKind", "SELLER");
    fields.put("actorId", ACTOR_ID.toString());
    fields.put("purpose", "SESSION");
    fields.put("authVersion", "0");
    fields.put("issuedAt", Long.toString(now));
    fields.put("expiresAt", Long.toString(now + 60_000));
    return fields;
  }

  private static void saveGuard(Consumer<HashMap<String, String>> change) {
    final var fields = new HashMap<String, String>();
    fields.put("authVersion", "0");
    fields.put("state", "ACTIVE");
    change.accept(fields);
    commands().hset("commerce-settle:auth:subject:" + EPOCH + ":SELLER:" + ACTOR_ID, fields);
  }

  private static long redisMillis() {
    final var time = commands().time();
    return Long.parseLong(time.get(0)) * 1000 + Long.parseLong(time.get(1)) / 1000;
  }

  private static RedisCommands<String, String> commands() {
    return admin.sync();
  }

  private static String scriptSha() throws IOException {
    try (var input = new ClassPathResource("redis/verify-session.lua").getInputStream()) {
      return HexFormat.of().formatHex(SessionVerifier.digest("SHA-1", input.readAllBytes()));
    }
  }

  private static RedisURI gatewayUri(int port) {
    return RedisURI.builder()
        .withHost(REDIS.getHost())
        .withPort(port)
        .withAuthentication("gateway", GATEWAY_PASSWORD)
        .withTimeout(Duration.ofSeconds(2))
        .build();
  }
}
