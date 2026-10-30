package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.application.exception.AuthenticationFailedException;
import com.commercesettle.commerce.application.exception.IdempotencyConflictException;
import com.commercesettle.commerce.application.service.BuyerAddressService.StoredAddress;
import com.commercesettle.commerce.domain.exception.BuyerAddressNotFoundException;
import com.commercesettle.commerce.domain.exception.VersionConflictException;
import com.commercesettle.commerce.domain.model.ShippingAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL에서 기본 주소 부분 UNIQUE·암호문 저장과 함께 주소록 유즈케이스의 커밋 결과를 확인한다. */
// 인증 설정은 필수 기동 값이다. 주소록 시험은 Redis 인증 경로를 호출하지 않는다.
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "app.auth.session-epoch=00000000-0000-4000-8000-000000000000",
      "app.auth.rate-limit.identifier-hmac-key=buyer-address-test-hmac-key-32bytes",
      "app.auth.password-reset.delivery-key=buyer-address-test-delivery-key-32bytes",
      "app.address.encryption-key=buyer-address-test-address-key-32bytes"
    })
@Testcontainers
class BuyerAddressIntegrationTest {
  private static final ShippingAddress HOME =
      new ShippingAddress("홍길동", "010-1234-5678", "06236", "서울 강남구 테헤란로 1", "101호");
  private static final ShippingAddress OFFICE =
      new ShippingAddress("홍길동", "010-1234-5678", "04524", "서울 중구 세종대로 2", "");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  @Autowired BuyerAddressService addresses;
  @Autowired JdbcTemplate jdbc;

  @Test
  @DisplayName("첫 주소는 기본 요청이 없어도 기본이 되고 배송 정보는 암호문으로만 저장한다")
  void firstAddressBecomesDefaultAndIsEncrypted() {
    UUID buyer = buyer();

    StoredAddress first = addresses.register(buyer, key(), HOME, false);

    assertThat(first.isDefault()).isTrue();
    assertThat(first.shipping()).isEqualTo(HOME);
    byte[] stored =
        jdbc.queryForObject(
            "select address_ciphertext from commerce.buyer_addresses where id = ?",
            byte[].class,
            first.id());
    assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("테헤란로");
  }

  @Test
  @DisplayName("다른 주소를 기본으로 등록하면 기존 기본을 해제하고 목록은 기본 주소가 먼저다")
  void movesDefaultOnRegister() {
    UUID buyer = buyer();
    StoredAddress home = addresses.register(buyer, key(), HOME, false);

    StoredAddress office = addresses.register(buyer, key(), OFFICE, true);
    StoredAddress third = addresses.register(buyer, key(), HOME, false);

    List<StoredAddress> list = addresses.addresses(buyer, 0, 20).items();
    assertThat(list)
        .extracting(StoredAddress::id)
        .containsExactly(office.id(), third.id(), home.id());
    assertThat(list).filteredOn(StoredAddress::isDefault).hasSize(1);
  }

  @Test
  @DisplayName("변경으로 기본을 옮기면 기존 기본 주소의 버전도 올라간다")
  void movesDefaultOnChange() {
    UUID buyer = buyer();
    StoredAddress home = addresses.register(buyer, key(), HOME, false);
    StoredAddress office = addresses.register(buyer, key(), OFFICE, false);

    StoredAddress changed =
        addresses.change(buyer, office.id(), key(), OFFICE, true, office.version());

    assertThat(changed.isDefault()).isTrue();
    assertThat(changed.version()).isEqualTo(office.version() + 1);
    StoredAddress previous = find(buyer, home.id());
    assertThat(previous.isDefault()).isFalse();
    assertThat(previous.version()).isEqualTo(home.version() + 1);
  }

  @Test
  @DisplayName("기본 주소를 삭제하면 남은 주소에 기본을 넘기지 않는다")
  void deletingDefaultLeavesNoDefault() {
    UUID buyer = buyer();
    StoredAddress home = addresses.register(buyer, key(), HOME, false);
    addresses.register(buyer, key(), OFFICE, false);

    addresses.delete(buyer, home.id(), key(), home.version());

    List<StoredAddress> list = addresses.addresses(buyer, 0, 20).items();
    assertThat(list).hasSize(1);
    assertThat(list.get(0).isDefault()).isFalse();
  }

  @Test
  @DisplayName("타인·삭제 주소는 존재하지 않는 주소와 같게 거절한다")
  void hidesOthersAndDeletedAddresses() {
    UUID buyer = buyer();
    StoredAddress home = addresses.register(buyer, key(), HOME, false);

    assertThatThrownBy(() -> addresses.change(buyer(), home.id(), key(), OFFICE, false, 0))
        .isInstanceOf(BuyerAddressNotFoundException.class);

    addresses.delete(buyer, home.id(), key(), 0);
    assertThatThrownBy(() -> addresses.change(buyer, home.id(), key(), OFFICE, false, 1))
        .isInstanceOf(BuyerAddressNotFoundException.class);
  }

  @Test
  @DisplayName("기대 버전이 다르면 변경을 거절하고 멱등 기록도 남기지 않는다")
  void rejectsStaleVersion() {
    UUID buyer = buyer();
    StoredAddress home = addresses.register(buyer, key(), HOME, false);
    String key = key();

    assertThatThrownBy(() -> addresses.change(buyer, home.id(), key, OFFICE, false, 7))
        .isInstanceOf(VersionConflictException.class);

    StoredAddress changed = addresses.change(buyer, home.id(), key, OFFICE, false, 0);
    assertThat(changed.shipping()).isEqualTo(OFFICE);
  }

  @Test
  @DisplayName("기본 이동 중 변경이 실패하면 기존 기본 해제와 멱등 기록도 함께 롤백한다")
  void rollsBackDefaultMoveAndIdempotencyRecordOnFailure() {
    UUID buyer = buyer();
    StoredAddress home = addresses.register(buyer, key(), HOME, false);
    StoredAddress office = addresses.register(buyer, key(), OFFICE, false);
    String key = key();

    assertThatThrownBy(() -> addresses.change(buyer, office.id(), key, OFFICE, true, 7))
        .isInstanceOf(VersionConflictException.class);

    StoredAddress previous = find(buyer, home.id());
    assertThat(previous.isDefault()).isTrue();
    assertThat(previous.version()).isEqualTo(home.version());
    StoredAddress changed = addresses.change(buyer, office.id(), key, OFFICE, true, 0);
    assertThat(changed.isDefault()).isTrue();
  }

  @Test
  @DisplayName("구매자 계정이 없으면 인증 실패이며 주소와 멱등 기록을 남기지 않는다")
  void rejectsMissingBuyerAccount() {
    UUID missing = UUID.randomUUID();

    assertThatThrownBy(() -> addresses.register(missing, key(), HOME, false))
        .isInstanceOf(AuthenticationFailedException.class);

    assertThat(addresses.addresses(missing, 0, 20).totalElements()).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from commerce.idempotency_records where actor_id = ?",
                Long.class,
                missing.toString()))
        .isZero();
  }

  @Test
  @DisplayName("같은 키·본문은 기존 주소를 돌려주고 같은 키의 다른 본문은 충돌이다")
  void replaysAndRejectsByIdempotencyKey() {
    UUID buyer = buyer();
    String key = key();

    StoredAddress first = addresses.register(buyer, key, HOME, false);
    StoredAddress replay = addresses.register(buyer, key, HOME, false);

    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(addresses.addresses(buyer, 0, 20).totalElements()).isEqualTo(1);
    assertThatThrownBy(() -> addresses.register(buyer, key, OFFICE, false))
        .isInstanceOf(IdempotencyConflictException.class);
  }

  @Test
  @DisplayName("주소가 없는 구매자의 동시 등록은 모두 성공하고 기본 주소는 하나다")
  void concurrentFirstRegistrationsKeepOneDefault() throws Exception {
    UUID buyer = buyer();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<StoredAddress>> results =
          List.of(HOME, OFFICE).stream()
              .map(
                  shipping ->
                      pool.submit(
                          () -> {
                            start.await();
                            return addresses.register(buyer, key(), shipping, false);
                          }))
              .toList();
      start.countDown();
      for (Future<StoredAddress> result : results) {
        result.get(10, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    List<StoredAddress> stored = addresses.addresses(buyer, 0, 20).items();
    assertThat(stored).hasSize(2);
    assertThat(stored).filteredOn(StoredAddress::isDefault).hasSize(1);
  }

  @Test
  @DisplayName("DB는 구매자당 삭제되지 않은 기본 주소를 하나만 허용한다")
  void databaseAllowsOneDefault() {
    UUID buyer = buyer();
    addresses.register(buyer, key(), HOME, false);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into commerce.buyer_addresses
                      (id, buyer_id, recipient_ciphertext, phone_ciphertext, address_ciphertext,
                       is_default, created_at, updated_at, version)
                    values (?, ?, '\\x00', '\\x00', '\\x00', true, now(), now(), 0)
                    """,
                    UUID.randomUUID(),
                    buyer))
        .hasMessageContaining("uk_buyer_addresses_default");
  }

  private StoredAddress find(UUID buyer, UUID addressId) {
    return addresses.addresses(buyer, 0, 100).items().stream()
        .filter(address -> address.id().equals(addressId))
        .findFirst()
        .orElseThrow();
  }

  // 주소록 쓰기는 구매자 행을 잠그므로 실제 계정 행을 준비한다.
  private UUID buyer() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into commerce.buyer_accounts
          (id, email_normalized, password_hash, display_name, status, created_at, updated_at,
           version)
        values (?, ?, 'hash', '구매자', 'ACTIVE', now(), now(), 0)
        """,
        id,
        id + "@example.com");
    return id;
  }

  private static String key() {
    return "key-" + UUID.randomUUID();
  }
}
