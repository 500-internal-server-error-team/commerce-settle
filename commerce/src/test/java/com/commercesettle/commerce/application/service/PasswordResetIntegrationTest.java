package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercesettle.commerce.application.exception.AuthChangeUnconfirmedException;
import com.commercesettle.commerce.application.exception.AuthRateLimitedException;
import com.commercesettle.commerce.application.exception.AuthenticationFailedException;
import com.commercesettle.commerce.application.exception.AuthenticationUnavailableException;
import com.commercesettle.commerce.application.port.PasswordResetDelivery;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.PasswordResetToken;
import com.commercesettle.commerce.domain.model.SellerAccount;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL·Redis에서 복구 토큰 발급과 재설정의 DB 커밋, 가드 차단·확정, Gateway 판정 결과를 함께 확인한다. */
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "app.auth.session-epoch=" + PasswordResetIntegrationTest.EPOCH,
      "app.auth.rate-limit.identifier-hmac-key=password-reset-test-hmac-key-32bytes",
      "app.auth.password-reset.delivery-key=password-reset-test-delivery-key-32bytes"
    })
@Testcontainers
class PasswordResetIntegrationTest {
  static final String EPOCH = "00000000-0000-4000-8000-0000000000aa";
  private static final String EMAIL = "seller@example.com";
  private static final String OLD_PASSWORD = "old-password-1";
  private static final String NEW_PASSWORD = "new-password-1";
  private static final RedisScript<String> VERIFY =
      RedisScript.of(new ClassPathResource("gateway/verify-session.lua"), String.class);

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  @Container
  @ServiceConnection(name = "redis")
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);

  @Autowired PasswordResetService passwordResets;
  @Autowired AccountService accounts;
  @Autowired SessionService sessions;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired StringRedisTemplate redis;
  @MockitoSpyBean PasswordResetDelivery delivery;

  @AfterEach
  void clear() {
    jdbc.update("DELETE FROM commerce.audit_logs");
    jdbc.update("DELETE FROM commerce.password_reset_tokens");
    jdbc.update("DELETE FROM commerce.seller_accounts");
    redis.execute(
        (RedisCallback<Object>)
            connection -> {
              connection.serverCommands().flushAll();
              return null;
            });
  }

  @Test
  @DisplayName("복구 요청은 토큰 해시와 전달용 암호문을 PENDING으로 저장하고 원문은 남기지 않는다")
  void storesPendingDeliveryWithoutRawToken() {
    SellerAccount seller = register();

    String token = requestToken(" Seller@Example.com ");

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM commerce.password_reset_tokens");
    assertThat(row)
        .containsEntry("actor_kind", "SELLER")
        .containsEntry("actor_id", seller.getId())
        .containsEntry("purpose", "PASSWORD_RESET")
        .containsEntry("token_hash", PasswordResetToken.hash(token))
        .containsEntry("delivery_status", "PENDING");
    assertThat(new String((byte[]) row.get("delivery_ciphertext"), StandardCharsets.ISO_8859_1))
        .doesNotContain(token);
    assertThat(
            Duration.between(
                ((Timestamp) row.get("created_at")).toInstant(),
                ((Timestamp) row.get("expires_at")).toInstant()))
        .isEqualTo(Duration.ofMinutes(10));
  }

  @Test
  @DisplayName("새 복구 요청은 이전 토큰을 폐기해 이전 토큰으로 재설정할 수 없다")
  void newRequestRevokesPreviousToken() {
    register();
    String first = requestToken(EMAIL);
    String second = requestToken(EMAIL);

    assertThat(openTokens()).isOne();
    assertThatThrownBy(() -> passwordResets.reset(ActorKind.SELLER, first, NEW_PASSWORD))
        .isInstanceOf(AuthenticationFailedException.class);
    passwordResets.reset(ActorKind.SELLER, second, NEW_PASSWORD);
  }

  @Test
  @DisplayName("없는 계정과 다른 종류 계정의 복구 요청도 같은 결과로 끝나며 토큰을 만들지 않는다")
  void acceptsUnknownAccountWithoutToken() {
    register();

    passwordResets.request(ActorKind.SELLER, "none@example.com");
    passwordResets.request(ActorKind.BUYER, EMAIL);

    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM commerce.password_reset_tokens", Long.class))
        .isZero();
    verifyNoInteractions(delivery);
  }

  @Test
  @DisplayName("재설정은 비밀번호·인증 버전·토큰·감사를 커밋하고 가드를 새 버전으로 열어 이전 세션을 무효로 만든다")
  void resetsPasswordAndRevokesOldSessions() {
    SellerAccount seller = register();
    SessionService.Login before = sessions.login(ActorKind.SELLER, EMAIL, OLD_PASSWORD);
    assertThat(gatewayVerdict(before.token())).isEqualTo("AUTHENTICATED:SELLER:" + seller.getId());
    String token = requestToken(EMAIL);

    passwordResets.reset(ActorKind.SELLER, token, NEW_PASSWORD);

    assertThat(gatewayVerdict(before.token())).isEqualTo("UNAUTHENTICATED");
    assertThat(redis.<String, String>opsForHash().entries(guardKey(seller.getId())))
        .isEqualTo(Map.of("authVersion", "1", "state", "ACTIVE"));
    assertThat(
            jdbc.queryForObject(
                "SELECT auth_version FROM commerce.seller_accounts WHERE id = ?",
                Long.class,
                seller.getId()))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "SELECT consumed_at IS NOT NULL FROM commerce.password_reset_tokens",
                Boolean.class))
        .isTrue();
    Map<String, Object> audit = jdbc.queryForMap("SELECT * FROM commerce.audit_logs");
    assertThat(audit)
        .containsEntry("actor_kind", "SELLER")
        .containsEntry("actor_ref", seller.getId().toString())
        .containsEntry("action", "ACCOUNT_AUTH_CHANGED")
        .containsEntry("aggregate_type", "SELLER_ACCOUNT")
        .containsEntry("aggregate_id", seller.getId())
        .containsEntry("before_status", "ACTIVE")
        .containsEntry("after_status", "ACTIVE")
        .containsEntry("reason_code", "PASSWORD_RESET");
    assertThat(UUID.fromString((String) audit.get("correlation_id"))).isNotNull();

    assertThatThrownBy(() -> sessions.login(ActorKind.SELLER, EMAIL, OLD_PASSWORD))
        .isInstanceOf(AuthenticationFailedException.class);
    SessionService.Login after = sessions.login(ActorKind.SELLER, EMAIL, NEW_PASSWORD);
    assertThat(gatewayVerdict(after.token())).isEqualTo("AUTHENTICATED:SELLER:" + seller.getId());
  }

  @Test
  @DisplayName("소비한 토큰을 다시 쓰면 401이며 비밀번호와 인증 버전을 다시 바꾸지 않는다")
  void rejectsConsumedToken() {
    SellerAccount seller = register();
    String token = requestToken(EMAIL);
    passwordResets.reset(ActorKind.SELLER, token, NEW_PASSWORD);

    assertThatThrownBy(() -> passwordResets.reset(ActorKind.SELLER, token, "another-password"))
        .isInstanceOf(AuthenticationFailedException.class);
    assertThat(authVersion(seller)).isOne();
    sessions.login(ActorKind.SELLER, EMAIL, NEW_PASSWORD);
  }

  @Test
  @DisplayName("만료됐거나 다른 계정 종류로 보낸 토큰은 가드를 막지 않고 401이다")
  void rejectsExpiredOrOtherKindToken() {
    SellerAccount seller = register();
    String token = requestToken(EMAIL);

    assertThatThrownBy(() -> passwordResets.reset(ActorKind.BUYER, token, NEW_PASSWORD))
        .isInstanceOf(AuthenticationFailedException.class);
    jdbc.update(
        "UPDATE commerce.password_reset_tokens SET expires_at = clock_timestamp() - interval '1 second'");
    assertThatThrownBy(() -> passwordResets.reset(ActorKind.SELLER, token, NEW_PASSWORD))
        .isInstanceOf(AuthenticationFailedException.class);

    assertThat(redis.hasKey(guardKey(seller.getId()))).isFalse();
    assertThat(authVersion(seller)).isZero();
  }

  @Test
  @DisplayName("다른 인증 변경이 진행 중이면 DB를 바꾸지 않고 503이며 토큰은 그대로 남는다")
  void refusesWhileAnotherChangeIsPending() {
    SellerAccount seller = register();
    String token = requestToken(EMAIL);
    String pending = UUID.randomUUID().toString();
    redis
        .opsForHash()
        .putAll(
            guardKey(seller.getId()),
            Map.of("authVersion", "0", "state", "BLOCKED", "pendingChangeId", pending));

    assertThatThrownBy(() -> passwordResets.reset(ActorKind.SELLER, token, NEW_PASSWORD))
        .isInstanceOf(AuthenticationUnavailableException.class);
    assertThat(authVersion(seller)).isZero();
    assertThat(openTokens()).isOne();
    assertThat(redis.opsForHash().get(guardKey(seller.getId()), "pendingChangeId"))
        .isEqualTo(pending);
  }

  @Test
  @DisplayName("같은 토큰의 동시 재설정은 한 번만 반영한다")
  void appliesConcurrentResetsOnce() throws Exception {
    SellerAccount seller = register();
    String token = requestToken(EMAIL);
    int attempts = 4;
    var start = new CountDownLatch(1);
    var tasks = new ArrayList<Callable<Boolean>>();
    for (int i = 0; i < attempts; i++) {
      String password = NEW_PASSWORD + i;
      tasks.add(
          () -> {
            start.await();
            try {
              passwordResets.reset(ActorKind.SELLER, token, password);
              return true;
            } catch (AuthenticationFailedException | AuthenticationUnavailableException e) {
              return false;
            }
          });
    }

    long succeeded;
    try (var executor = Executors.newFixedThreadPool(attempts)) {
      List<Future<Boolean>> futures = tasks.stream().map(executor::submit).toList();
      start.countDown();
      succeeded = futures.stream().filter(PasswordResetIntegrationTest::get).count();
    }

    assertThat(succeeded).isOne();
    assertThat(authVersion(seller)).isOne();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.audit_logs", Long.class)).isOne();
  }

  @Test
  @DisplayName("다른 트랜잭션이 계정 행을 오래 잡으면 대기 상한 안에 롤백하고 비밀번호를 바꾸지 않는다")
  void rollsBackWhenAccountLockWaitExceedsLimit() throws Exception {
    SellerAccount seller = register();
    String token = requestToken(EMAIL);

    try (Connection holder = lockAccount(seller)) {
      long started = System.nanoTime();
      assertThatThrownBy(() -> passwordResets.reset(ActorKind.SELLER, token, NEW_PASSWORD))
          .isInstanceOf(AuthChangeUnconfirmedException.class)
          .hasCauseInstanceOf(PessimisticLockingFailureException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
      holder.rollback();
    }

    assertThat(authVersion(seller)).isZero();
    assertThat(openTokens()).isOne();
  }

  @Test
  @DisplayName("잠금 대기 중 만료된 토큰은 재설정에 성공하지 못하고 비밀번호를 바꾸지 않는다")
  void rejectsTokenExpiredWhileWaitingForLock() throws Exception {
    SellerAccount seller = register();
    String token = requestToken(EMAIL);
    jdbc.update(
        "UPDATE commerce.password_reset_tokens SET expires_at = clock_timestamp() + interval '500 milliseconds'");

    try (Connection holder = lockAccount(seller);
        var executor = Executors.newSingleThreadExecutor()) {
      Future<?> reset =
          executor.submit(() -> passwordResets.reset(ActorKind.SELLER, token, NEW_PASSWORD));
      Thread.sleep(900);
      holder.rollback();
      assertThatThrownBy(reset::get)
          .hasCauseInstanceOf(AuthChangeUnconfirmedException.class)
          .cause()
          .hasCauseInstanceOf(AuthenticationFailedException.class);
    }

    assertThat(authVersion(seller)).isZero();
    assertThat(openTokens()).isOne();
  }

  @Test
  @DisplayName("복구 요청 제한을 넘으면 기존 토큰을 폐기하지 않고 새 토큰도 만들지 않는다")
  void keepsExistingTokenWhenRateLimited() {
    register();
    String last = null;
    for (int i = 0; i < 3; i++) {
      last = requestToken(EMAIL);
    }

    assertThatThrownBy(() -> passwordResets.request(ActorKind.SELLER, EMAIL))
        .isInstanceOf(AuthRateLimitedException.class);
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM commerce.password_reset_tokens", Long.class))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT token_hash FROM commerce.password_reset_tokens"
                    + " WHERE consumed_at IS NULL AND revoked_at IS NULL",
                String.class))
        .isEqualTo(PasswordResetToken.hash(last));
  }

  private SellerAccount register() {
    return accounts.registerSeller(EMAIL, OLD_PASSWORD, "판매자", "payout-ref");
  }

  // 별도 연결에서 계정 행 잠금을 잡은 채 돌려준다. 호출자가 롤백해 해제한다.
  private Connection lockAccount(SellerAccount seller) throws SQLException {
    Connection connection = dataSource.getConnection();
    connection.setAutoCommit(false);
    try (var statement =
        connection.prepareStatement(
            "SELECT 1 FROM commerce.seller_accounts WHERE id = ? FOR UPDATE")) {
      statement.setObject(1, seller.getId());
      statement.execute();
    }
    return connection;
  }

  private String requestToken(String email) {
    passwordResets.request(ActorKind.SELLER, email);
    ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
    verify(delivery, atLeastOnce()).seal(any(), token.capture());
    return token.getValue();
  }

  private long openTokens() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM commerce.password_reset_tokens"
            + " WHERE consumed_at IS NULL AND revoked_at IS NULL",
        Long.class);
  }

  private long authVersion(SellerAccount seller) {
    return jdbc.queryForObject(
        "SELECT auth_version FROM commerce.seller_accounts WHERE id = ?",
        Long.class,
        seller.getId());
  }

  private String gatewayVerdict(String sessionToken) {
    String key =
        "commerce-settle:auth:session:" + EPOCH + ":" + PasswordResetToken.hash(sessionToken);
    return redis.execute(VERIFY, List.of(key), EPOCH);
  }

  private static String guardKey(UUID sellerId) {
    return "commerce-settle:auth:subject:" + EPOCH + ":SELLER:" + sellerId;
  }

  private static boolean get(Future<Boolean> future) {
    try {
      return future.get();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
