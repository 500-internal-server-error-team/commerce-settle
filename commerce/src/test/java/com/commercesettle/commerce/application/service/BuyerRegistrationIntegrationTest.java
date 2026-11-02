package com.commercesettle.commerce.application.service;

import static com.commercesettle.commerce.support.ConcurrentTransactions.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.reset;

import com.commercesettle.commerce.application.exception.IdempotencyConflictException;
import com.commercesettle.commerce.domain.exception.DuplicateAccountEmailException;
import com.commercesettle.commerce.domain.model.BuyerAccount;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL에서 구매자 가입·웰컴 쿠폰·멱등 기록이 함께 커밋·롤백되는지와 재요청·경합 결과를 확인한다. */
// 인증 설정은 필수 기동 값이다. 가입은 Redis 인증 경로를 호출하지 않는다.
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "app.coupons.expiry-scheduler-enabled=false",
      "app.auth.session-epoch=00000000-0000-4000-8000-000000000000",
      "app.auth.rate-limit.identifier-hmac-key=buyer-signup-test-hmac-key-32bytes-long",
      "app.auth.password-reset.delivery-key=buyer-signup-test-delivery-key-32bytes",
      "app.address.encryption-key=buyer-signup-test-address-key-32bytes-long",
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123"
    })
@Testcontainers
class BuyerRegistrationIntegrationTest {
  private static final String PASSWORD = "password-1234";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  @Autowired AccountService accounts;
  @Autowired JdbcTemplate jdbc;
  @MockitoSpyBean CouponService coupons;

  @Test
  @DisplayName("가입하면 계정과 가입 시각+14일 기한의 웰컴 쿠폰 한 장, 비밀번호 원문 없는 멱등 기록을 함께 저장한다")
  void registersBuyerWithWelcomeCoupon() {
    String email = email();

    BuyerAccount buyer =
        accounts.registerBuyer(key(), "  " + email.toUpperCase() + " ", PASSWORD, "구매자");

    assertThat(buyer.getEmailNormalized()).isEqualTo(email);
    Instant expiresAt =
        jdbc.queryForObject(
            "select expires_at from commerce.buyer_coupons where buyer_id = ? and kind = 'WELCOME'",
            Instant.class,
            buyer.getId());
    Instant createdAt =
        jdbc.queryForObject(
            "select created_at from commerce.buyer_accounts where id = ?",
            Instant.class,
            buyer.getId());
    assertThat(expiresAt).isEqualTo(createdAt.plus(Duration.ofDays(14)));
    List<String> hashes =
        jdbc.queryForList(
            "select request_hash from commerce.idempotency_records where actor_kind = 'PUBLIC'"
                + " and actor_id = 'BUYER_REGISTRATION' and operation = 'REGISTER' and resource_id = ?",
            String.class,
            buyer.getId());
    assertThat(hashes).hasSize(1);
    assertThat(hashes.getFirst()).doesNotContain(PASSWORD);
  }

  @Test
  @DisplayName("같은 키·본문의 재요청은 같은 계정을 돌려주고 쿠폰을 더 발급하지 않는다")
  void replaysSameKeyAndBody() {
    String key = key();
    String email = email();

    BuyerAccount first = accounts.registerBuyer(key, email, PASSWORD, "구매자");
    BuyerAccount again = accounts.registerBuyer(key, email, PASSWORD, "구매자");

    assertThat(again.getId()).isEqualTo(first.getId());
    assertThat(welcomeCoupons(first.getId())).isEqualTo(1);
  }

  @Test
  @DisplayName("같은 키에 비밀번호만 다른 본문은 충돌이며 계정을 바꾸지 않는다")
  void rejectsSameKeyWithDifferentBody() {
    String key = key();
    String email = email();
    BuyerAccount first = accounts.registerBuyer(key, email, PASSWORD, "구매자");

    assertThatThrownBy(() -> accounts.registerBuyer(key, email, "other-password-99", "구매자"))
        .isInstanceOf(IdempotencyConflictException.class);
    assertThat(buyersWithEmail(email)).isEqualTo(1);
    assertThat(welcomeCoupons(first.getId())).isEqualTo(1);
  }

  @Test
  @DisplayName("다른 키로 같은 이메일을 가입하면 409 대상이며 기존 계정이나 쿠폰을 재사용하지 않는다")
  void rejectsSameEmailWithNewKey() {
    String email = email();
    accounts.registerBuyer(key(), email, PASSWORD, "구매자");

    assertThatThrownBy(() -> accounts.registerBuyer(key(), email, PASSWORD, "구매자"))
        .isInstanceOf(DuplicateAccountEmailException.class);
    assertThat(buyersWithEmail(email)).isEqualTo(1);
  }

  @Test
  @DisplayName("쿠폰 발급이 실패하면 계정·멱등 기록도 남지 않고 같은 키로 다시 가입할 수 있다")
  void rollsBackWhenCouponFails() {
    String key = key();
    String email = email();
    long recordsBefore = registrationRecords();
    willThrow(new IllegalStateException("coupon store failed"))
        .given(coupons)
        .issueWelcome(any(), any());

    assertThatThrownBy(() -> accounts.registerBuyer(key, email, PASSWORD, "구매자"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(buyersWithEmail(email)).isZero();
    assertThat(registrationRecords()).isEqualTo(recordsBefore);

    reset(coupons);
    BuyerAccount retried = accounts.registerBuyer(key, email, PASSWORD, "구매자");
    assertThat(welcomeCoupons(retried.getId())).isEqualTo(1);
  }

  @Test
  @DisplayName("같은 키·본문의 동시 요청은 계정·쿠폰 한 건만 만들고 나머지는 같은 계정 또는 처리 중 충돌이다")
  void concurrentSameKeyCreatesOneAccount() throws Exception {
    String key = key();
    String email = email();

    List<Object> results =
        runConcurrently(5, () -> accounts.registerBuyer(key, email, PASSWORD, "구매자").getId());

    assertThat(results)
        .allSatisfy(
            r -> assertThat(r).isInstanceOfAny(UUID.class, IdempotencyConflictException.class));
    assertThat(results.stream().filter(UUID.class::isInstance).distinct()).hasSize(1);
    assertThat(buyersWithEmail(email)).isEqualTo(1);
  }

  @Test
  @DisplayName("다른 키로 같은 이메일을 동시에 가입하면 한 계정만 남고 나머지는 이메일 중복이다")
  void concurrentSameEmailCreatesOneAccount() throws Exception {
    String email = email();

    List<Object> results =
        runConcurrently(5, () -> accounts.registerBuyer(key(), email, PASSWORD, "구매자").getId());

    assertThat(results).filteredOn(UUID.class::isInstance).hasSize(1);
    assertThat(results)
        .filteredOn(r -> !(r instanceof UUID))
        .allSatisfy(r -> assertThat(r).isInstanceOf(DuplicateAccountEmailException.class));
    UUID buyer = (UUID) results.stream().filter(UUID.class::isInstance).findFirst().orElseThrow();
    assertThat(welcomeCoupons(buyer)).isEqualTo(1);
  }

  private long registrationRecords() {
    return jdbc.queryForObject(
        "select count(*) from commerce.idempotency_records where actor_id = 'BUYER_REGISTRATION'",
        Long.class);
  }

  private long welcomeCoupons(UUID buyerId) {
    return jdbc.queryForObject(
        "select count(*) from commerce.buyer_coupons where buyer_id = ? and kind = 'WELCOME'",
        Long.class,
        buyerId);
  }

  private long buyersWithEmail(String email) {
    return jdbc.queryForObject(
        "select count(*) from commerce.buyer_accounts where email_normalized = ?",
        Long.class,
        email);
  }

  private static String email() {
    return "buyer-" + UUID.randomUUID() + "@example.com";
  }

  private static String key() {
    return "signup-" + UUID.randomUUID();
  }
}
