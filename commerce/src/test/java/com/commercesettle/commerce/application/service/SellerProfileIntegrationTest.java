package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.application.exception.AuthenticationFailedException;
import com.commercesettle.commerce.application.exception.IdempotencyConflictException;
import com.commercesettle.commerce.application.service.SellerProfileService.SettlementContext;
import com.commercesettle.commerce.domain.exception.ProductNotFoundException;
import com.commercesettle.commerce.domain.exception.VersionConflictException;
import com.commercesettle.commerce.domain.model.AccountStatus;
import com.commercesettle.commerce.domain.model.SellerAccount;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL에서 판매자 프로필 변경의 버전·멱등 커밋 결과와 Settlement용 지급 컨텍스트를 확인한다. */
// 인증 설정은 필수 기동 값이다. 이 시험은 Redis 인증 경로를 호출하지 않는다.
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "app.auth.session-epoch=00000000-0000-4000-8000-000000000000",
      "app.auth.rate-limit.identifier-hmac-key=seller-profile-test-hmac-key-32bytes",
      "app.auth.password-reset.delivery-key=seller-profile-test-delivery-key-32bytes",
      "app.address.encryption-key=seller-profile-test-address-key-32bytes",
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123"
    })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SellerProfileIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  @Autowired SellerProfileService sellers;
  @Autowired JdbcTemplate jdbc;

  @Test
  @DisplayName("변경은 표시명·수취 계정만 바꾸고 버전을 올리며 판매 상태와 인증 버전은 그대로다")
  void changesProfileAndBumpsVersion() {
    UUID seller = seller("ACTIVE");

    SellerAccount changed = sellers.change(seller, key(), "새 상점", "account-new", 0);

    assertThat(changed.getDisplayName()).isEqualTo("새 상점");
    assertThat(changed.getPayoutAccountRef()).isEqualTo("account-new");
    assertThat(changed.getVersion()).isEqualTo(1);
    assertThat(changed.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(changed.getAuthVersion()).isZero();
    assertThat(sellers.profile(seller).getPayoutAccountRef()).isEqualTo("account-new");
  }

  @Test
  @DisplayName("같은 키·본문은 다시 바꾸지 않고, 같은 키의 다른 본문은 충돌이다")
  void replaysAndRejectsByIdempotencyKey() {
    UUID seller = seller("ACTIVE");
    String key = key();

    sellers.change(seller, key, "새 상점", "account-new", 0);
    SellerAccount replay = sellers.change(seller, key, "새 상점", "account-new", 0);

    assertThat(replay.getVersion()).isEqualTo(1);
    assertThatThrownBy(() -> sellers.change(seller, key, "다른 상점", "account-new", 0))
        .isInstanceOf(IdempotencyConflictException.class);
  }

  @Test
  @DisplayName("기대 버전이 다르면 거절하고 멱등 기록도 남기지 않아 같은 키로 다시 요청할 수 있다")
  void rejectsStaleVersion() {
    UUID seller = seller("ACTIVE");
    String key = key();

    assertThatThrownBy(() -> sellers.change(seller, key, "새 상점", "account-new", 7))
        .isInstanceOf(VersionConflictException.class);

    assertThat(sellers.profile(seller).getVersion()).isZero();
    assertThat(sellers.change(seller, key, "새 상점", "account-new", 0).getVersion()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"SUSPENDED", "DELETED"})
  @DisplayName("정지·삭제 판매자는 조회·변경 모두 인증 실패이며 멱등 기록을 남기지 않는다")
  void rejectsInactiveSeller(String status) {
    UUID seller = seller(status);

    assertThatThrownBy(() -> sellers.profile(seller))
        .isInstanceOf(AuthenticationFailedException.class);
    assertThatThrownBy(() -> sellers.change(seller, key(), "새 상점", "account-new", 0))
        .isInstanceOf(AuthenticationFailedException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from commerce.idempotency_records where actor_id = ?",
                Long.class,
                seller.toString()))
        .isZero();
  }

  @Test
  @DisplayName("지급 컨텍스트는 상품의 소유 판매자와 현재 수취 계정·버전·상태를 돌려준다")
  void returnsSettlementContext() {
    UUID seller = seller("ACTIVE");
    UUID product = product(seller, "ON_SALE");

    assertThat(sellers.settlementContext(product))
        .isEqualTo(
            new SettlementContext(product, seller, "account-" + seller, 0, AccountStatus.ACTIVE));

    sellers.change(seller, key(), "새 상점", "account-new", 0);
    assertThat(sellers.settlementContext(product))
        .isEqualTo(new SettlementContext(product, seller, "account-new", 1, AccountStatus.ACTIVE));
  }

  @ParameterizedTest
  @ValueSource(strings = {"SUSPENDED", "DELETED"})
  @DisplayName("정지·삭제 판매자의 상품도 상태를 그대로 돌려줘 Settlement가 지급하지 않게 한다")
  void returnsInactiveSellerStatus(String status) {
    UUID seller = seller(status);
    UUID product = product(seller, "ON_SALE");

    assertThat(sellers.settlementContext(product).sellerStatus())
        .isEqualTo(AccountStatus.valueOf(status));
  }

  @Test
  @DisplayName("논리삭제한 상품도 지난 판매의 지급을 위해 컨텍스트를 돌려주고, 없는 상품은 거절한다")
  void includesDeletedProductAndRejectsMissing() {
    UUID seller = seller("ACTIVE");
    UUID deleted = product(seller, "DELETED");

    assertThat(sellers.settlementContext(deleted).sellerId()).isEqualTo(seller);
    assertThatThrownBy(() -> sellers.settlementContext(UUID.randomUUID()))
        .isInstanceOf(ProductNotFoundException.class);
  }

  private UUID seller(String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into commerce.seller_accounts
          (id, email_normalized, password_hash, display_name, status, payout_account_ref,
           created_at, updated_at, version, deleted_at)
        values (?, ?, 'hash', '상점', ?, ?, now(), now(), 0,
                case when ? = 'DELETED' then now() end)
        """,
        id,
        id + "@example.com",
        status,
        "account-" + id,
        status);
    return id;
  }

  private UUID product(UUID seller, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into commerce.products
          (id, seller_id, name, unit_price, status, created_at, updated_at, version, deleted_at)
        values (?, ?, '수납 상자', 23000, ?, now(), now(), 0,
                case when ? = 'DELETED' then now() end)
        """,
        id,
        seller,
        status,
        status);
    return id;
  }

  private static String key() {
    return "key-" + UUID.randomUUID();
  }
}
