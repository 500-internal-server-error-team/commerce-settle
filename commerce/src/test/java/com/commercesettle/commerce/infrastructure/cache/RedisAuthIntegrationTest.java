package com.commercesettle.commerce.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.application.exception.AuthenticationFailedException;
import com.commercesettle.commerce.application.exception.AuthenticationUnavailableException;
import com.commercesettle.commerce.application.exception.LoginRateLimitedException;
import com.commercesettle.commerce.application.port.SessionStore.IssuedSession;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.infrastructure.config.AuthProperties;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 발급·삭제·제한 판정은 Lua 안에 있으므로 compose와 같은 ACL의 commerce 사용자로 실제 Redis에서 확인한다.
@Testcontainers
@Timeout(60)
class RedisAuthIntegrationTest {
  private static final String ADMIN_PASSWORD = "admin-password";
  private static final String COMMERCE_PASSWORD = "commerce-password";
  private static final UUID EPOCH = UUID.randomUUID();
  private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final String EMAIL = "seller@example.com";
  private static final int LOGIN_MAX_REQUESTS = 5;

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
  private static LettuceConnectionFactory commerce;
  private static RedisSessionStore sessions;
  private static RedisLoginRateLimiter limiter;

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
    commerce = factory(REDIS.getMappedPort(6379));
    var template = new StringRedisTemplate(commerce);
    sessions = new RedisSessionStore(template, properties(LOGIN_MAX_REQUESTS, 900));
    limiter = new RedisLoginRateLimiter(template, properties(LOGIN_MAX_REQUESTS, 900));
  }

  @AfterEach
  void clear() {
    admin.sync().flushall();
  }

  @AfterAll
  static void disconnect() {
    commerce.destroy();
    admin.close();
    client.shutdown();
  }

  @Test
  @DisplayName("가드가 없으면 같은 인증 버전의 ACTIVE 가드를 만들고 30분 고정 만료 세션을 저장한다")
  void issuesSessionAndCreatesGuard() {
    IssuedSession issued = sessions.issue(ActorKind.SELLER, ACTOR_ID, 3);

    String key = sessionKey(issued);
    Map<String, String> session = redis().hgetall(key);
    assertThat(session)
        .containsEntry("actorKind", "SELLER")
        .containsEntry("actorId", ACTOR_ID.toString())
        .containsEntry("purpose", "SESSION")
        .containsEntry("authVersion", "3")
        .containsEntry("expiresAt", Long.toString(issued.expiresAt().toEpochMilli()));
    assertThat(Long.parseLong(session.get("expiresAt")) - Long.parseLong(session.get("issuedAt")))
        .isEqualTo(Duration.ofMinutes(30).toMillis());
    assertThat(redis().pexpiretime(key)).isEqualTo(issued.expiresAt().toEpochMilli());
    assertThat(redis().hgetall(guardKey(ActorKind.SELLER)))
        .isEqualTo(Map.of("authVersion", "3", "state", "ACTIVE"));
    assertThat(issued.token()).hasSizeGreaterThanOrEqualTo(43).doesNotContain("=");
  }

  @Test
  @DisplayName("발급한 세션은 Gateway 검증 스크립트가 같은 계정으로 인증한다")
  void gatewayAuthenticatesIssuedSession() throws IOException {
    IssuedSession issued = sessions.issue(ActorKind.BUYER, ACTOR_ID, 0);

    assertThat(verifyWithGateway(issued)).isEqualTo("AUTHENTICATED:BUYER:" + ACTOR_ID);
  }

  @Test
  @DisplayName("같은 버전의 ACTIVE 가드가 있으면 가드를 바꾸지 않고 세션을 추가 발급한다")
  void issuesAnotherSessionForActiveGuard() {
    IssuedSession first = sessions.issue(ActorKind.SELLER, ACTOR_ID, 0);

    IssuedSession second = sessions.issue(ActorKind.SELLER, ACTOR_ID, 0);

    assertThat(second.token()).isNotEqualTo(first.token());
    assertThat(redis().exists(sessionKey(first), sessionKey(second))).isEqualTo(2);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("refusedGuards")
  @DisplayName("가드가 막혔거나 버전이 다르거나 변경 중·손상이면 세션을 저장하지 않는다")
  void refusesSessionWithoutMatchingActiveGuard(
      String scenario, Consumer<Map<String, String>> guard, Class<? extends Exception> error) {
    Map<String, String> fields = new HashMap<>(Map.of("authVersion", "3", "state", "ACTIVE"));
    guard.accept(fields);
    redis().hset(guardKey(ActorKind.SELLER), fields);

    assertThatThrownBy(() -> sessions.issue(ActorKind.SELLER, ACTOR_ID, 3)).isInstanceOf(error);
    assertThat(redis().keys("commerce-settle:auth:session:*")).isEmpty();
  }

  static Stream<Arguments> refusedGuards() {
    return Stream.of(
        refused("확정 BLOCKED", g -> g.put("state", "BLOCKED"), AuthenticationFailedException.class),
        refused(
            "DB 확인 뒤 버전 변경", g -> g.put("authVersion", "4"), AuthenticationFailedException.class),
        refused(
            "변경 중",
            g -> g.put("pendingChangeId", UUID.randomUUID().toString()),
            AuthenticationUnavailableException.class),
        refused(
            "변경 중인 BLOCKED",
            g -> {
              g.put("state", "BLOCKED");
              g.put("pendingChangeId", UUID.randomUUID().toString());
            },
            AuthenticationUnavailableException.class),
        refused("상태 누락", g -> g.remove("state"), AuthenticationUnavailableException.class),
        refused(
            "숫자가 아닌 버전",
            g -> g.put("authVersion", "nan"),
            AuthenticationUnavailableException.class),
        refused(
            "알 수 없는 상태", g -> g.put("state", "OPEN"), AuthenticationUnavailableException.class));
  }

  private static Arguments refused(
      String scenario, Consumer<Map<String, String>> guard, Class<? extends Exception> error) {
    return Arguments.of(scenario, guard, error);
  }

  @Test
  @DisplayName("로그아웃은 본인 세션을 지우며 이후 Gateway 검증은 인증 실패다")
  void deletesOwnSession() throws IOException {
    IssuedSession issued = sessions.issue(ActorKind.SELLER, ACTOR_ID, 0);

    sessions.delete(RedisSessionStore.hash(issued.token()), ActorKind.SELLER, ACTOR_ID);

    assertThat(redis().exists(sessionKey(issued))).isZero();
    assertThat(verifyWithGateway(issued)).isEqualTo("UNAUTHENTICATED");
  }

  @Test
  @DisplayName("이미 삭제된 세션의 로그아웃은 삭제된 것으로 본다")
  void treatsMissingSessionAsDeleted() {
    sessions.delete("a".repeat(64), ActorKind.SELLER, ACTOR_ID);

    assertThat(redis().keys("commerce-settle:auth:session:*")).isEmpty();
  }

  @Test
  @DisplayName("다른 계정의 세션 해시로는 로그아웃하지 않는다")
  void keepsSessionOfAnotherActor() {
    IssuedSession issued = sessions.issue(ActorKind.SELLER, ACTOR_ID, 0);
    String hash = RedisSessionStore.hash(issued.token());

    assertThatThrownBy(() -> sessions.delete(hash, ActorKind.BUYER, ACTOR_ID))
        .isInstanceOf(AuthenticationFailedException.class);
    assertThatThrownBy(() -> sessions.delete(hash, ActorKind.SELLER, UUID.randomUUID()))
        .isInstanceOf(AuthenticationFailedException.class);
    assertThat(redis().exists(sessionKey(issued))).isOne();
  }

  @Test
  @DisplayName("로그인 제한은 상한까지 허용하고 다음 요청에 남은 시간창을 초 단위로 올려 알린다")
  void limitsLoginAfterMaxRequests() {
    for (int i = 0; i < LOGIN_MAX_REQUESTS; i++) {
      limiter.acquire(ActorKind.SELLER, EMAIL);
    }

    assertThatThrownBy(() -> limiter.acquire(ActorKind.SELLER, EMAIL))
        .isInstanceOfSatisfying(
            LoginRateLimitedException.class,
            e -> assertThat(e.retryAfterSeconds()).isBetween(899L, 900L));
    String key = redis().keys("commerce-settle:auth:rate-limit:LOGIN:SELLER:*").getFirst();
    assertThat(key).doesNotContain(EMAIL).matches(".*:[0-9a-f]{64}");
    assertThat(redis().pttl(key)).isPositive().isLessThanOrEqualTo(900_000L);
  }

  @Test
  @DisplayName("구매자와 판매자의 로그인 제한은 같은 이메일이어도 따로 센다")
  void separatesLimitByActorKind() {
    for (int i = 0; i < LOGIN_MAX_REQUESTS; i++) {
      limiter.acquire(ActorKind.SELLER, EMAIL);
    }

    limiter.acquire(ActorKind.BUYER, EMAIL);

    assertThatThrownBy(() -> limiter.acquire(ActorKind.SELLER, EMAIL))
        .isInstanceOf(LoginRateLimitedException.class);
  }

  @Test
  @DisplayName("동시 로그인 시도도 상한을 넘겨 허용하지 않는다")
  void keepsLimitUnderConcurrency() throws Exception {
    int attempts = 20;
    var start = new CountDownLatch(1);
    var tasks = new ArrayList<Callable<Boolean>>();
    for (int i = 0; i < attempts; i++) {
      tasks.add(
          () -> {
            start.await();
            try {
              limiter.acquire(ActorKind.BUYER, EMAIL);
              return true;
            } catch (LoginRateLimitedException e) {
              return false;
            }
          });
    }

    long allowed;
    try (var executor = Executors.newFixedThreadPool(attempts)) {
      var futures = tasks.stream().map(executor::submit).toList();
      start.countDown();
      allowed = futures.stream().filter(RedisAuthIntegrationTest::get).count();
    }

    assertThat(allowed).isEqualTo(LOGIN_MAX_REQUESTS);
  }

  @Test
  @DisplayName("시간창이 끝나면 제한이 풀리고 새 시간창에서 다시 센다")
  void releasesLimitAfterWindow() throws InterruptedException {
    var shortLimiter =
        new RedisLoginRateLimiter(new StringRedisTemplate(commerce), properties(1, 1));
    shortLimiter.acquire(ActorKind.BUYER, EMAIL);
    assertThatThrownBy(() -> shortLimiter.acquire(ActorKind.BUYER, EMAIL))
        .isInstanceOf(LoginRateLimitedException.class);

    Instant deadline = Instant.now().plusSeconds(5);
    String key = redis().keys("commerce-settle:auth:rate-limit:LOGIN:BUYER:*").getFirst();
    while (redis().exists(key) == 1 && Instant.now().isBefore(deadline)) {
      Thread.sleep(50);
    }

    shortLimiter.acquire(ActorKind.BUYER, EMAIL);
    assertThat(redis().get(key)).isEqualTo("1");
  }

  @Test
  @DisplayName("Redis에 연결할 수 없으면 세션 발급·삭제·제한을 확정하지 않고 판정 불가로 처리한다")
  void refusesWhenStoreIsUnavailable() throws IOException {
    LettuceConnectionFactory closed = factory(unusedPort());
    try {
      var template = new StringRedisTemplate(closed);
      var unavailableSessions = new RedisSessionStore(template, properties(5, 900));
      var unavailableLimiter = new RedisLoginRateLimiter(template, properties(5, 900));

      assertThatThrownBy(() -> unavailableSessions.issue(ActorKind.SELLER, ACTOR_ID, 0))
          .isInstanceOf(AuthenticationUnavailableException.class);
      assertThatThrownBy(
              () -> unavailableSessions.delete("a".repeat(64), ActorKind.SELLER, ACTOR_ID))
          .isInstanceOf(AuthenticationUnavailableException.class);
      assertThatThrownBy(() -> unavailableLimiter.acquire(ActorKind.SELLER, EMAIL))
          .isInstanceOf(AuthenticationUnavailableException.class);
    } finally {
      closed.destroy();
    }
  }

  private static RedisCommands<String, String> redis() {
    return admin.sync();
  }

  private static String sessionKey(IssuedSession issued) {
    return AuthKeys.session(EPOCH, RedisSessionStore.hash(issued.token()));
  }

  private static String guardKey(ActorKind kind) {
    return AuthKeys.subject(EPOCH, kind, ACTOR_ID);
  }

  private static String verifyWithGateway(IssuedSession issued) throws IOException {
    String script =
        new ClassPathResource("gateway/verify-session.lua")
            .getContentAsString(StandardCharsets.UTF_8);
    return redis()
        .eval(script, ScriptOutputType.VALUE, new String[] {sessionKey(issued)}, EPOCH.toString());
  }

  private static AuthProperties properties(int maxRequests, int windowSeconds) {
    return new AuthProperties(
        EPOCH, new AuthProperties.RateLimit("k".repeat(32), maxRequests, windowSeconds));
  }

  private static LettuceConnectionFactory factory(int port) {
    var configuration = new RedisStandaloneConfiguration(REDIS.getHost(), port);
    configuration.setUsername("commerce");
    configuration.setPassword(COMMERCE_PASSWORD);
    var factory = new LettuceConnectionFactory(configuration);
    factory.afterPropertiesSet();
    factory.start();
    return factory;
  }

  private static int unusedPort() throws IOException {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static boolean get(Future<Boolean> future) {
    try {
      return future.get();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
