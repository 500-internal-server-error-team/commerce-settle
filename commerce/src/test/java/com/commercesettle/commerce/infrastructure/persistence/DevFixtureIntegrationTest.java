package com.commercesettle.commerce.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.commercesettle.commerce.application.port.AddressCipher;
import com.commercesettle.commerce.application.port.PasswordHasher;
import com.commercesettle.commerce.application.service.BuyerAddressService;
import com.commercesettle.commerce.application.service.InventoryReservationService;
import com.commercesettle.commerce.domain.model.ShippingAddress;
import com.commercesettle.commerce.domain.repository.OrderRepository;
import com.commercesettle.commerce.infrastructure.config.DevFixtureConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 DB 기동 적재·로그인·주소 복호화와 반복 실행의 보존·롤백을 확인한다. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.tracing.enabled=false",
      "app.dev-fixtures.enabled=true",
      "app.dev-fixtures.password=" + DevFixtureIntegrationTest.PASSWORD,
      "app.order-stub.enabled=true",
      "app.auth.session-epoch=" + DevFixtureIntegrationTest.EPOCH,
      "app.auth.rate-limit.identifier-hmac-key=dev-fixture-test-hmac-key-32bytes",
      "app.auth.password-reset.delivery-key=dev-fixture-test-delivery-key-32bytes",
      "app.address.encryption-key=dev-fixture-test-address-key-32bytes"
    })
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DevFixtureIntegrationTest {
  static final String PASSWORD = "fixture-integration-password";
  static final String EPOCH = "00000000-0000-4000-8000-000000000090";
  private static final RedisScript<String> VERIFY =
      RedisScript.of(new ClassPathResource("gateway/verify-session.lua"), String.class);

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  @Container
  @ServiceConnection(name = "redis")
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);

  @Autowired DevFixtureLoader loader;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordHasher passwords;
  @Autowired BuyerAddressService addresses;
  @Autowired StringRedisTemplate redis;
  @Autowired TestRestTemplate http;
  @Autowired OrderRepository orders;
  @Autowired AddressCipher cipher;

  @BeforeAll
  void startupActuallyLoadsFixtures() {
    assertThat(count("buyer_accounts")).isEqualTo(8);
    assertThat(count("seller_accounts")).isEqualTo(5);
    assertThat(count("products")).isEqualTo(28);
    assertThat(count("buyer_addresses")).isEqualTo(29);
    assertThat(count("orders")).isEqualTo(4);
    assertThat(count("order_addresses")).isEqualTo(4);
  }

  @BeforeEach
  void cleanIsolatedDatabase() {
    jdbc.execute(
        "TRUNCATE commerce.orders, commerce.order_addresses, commerce.buyer_addresses, commerce.buyer_accounts, commerce.seller_accounts, "
            + "commerce.products, commerce.product_inventory, commerce.inventory_movements, commerce.inventory_reservations, commerce.idempotency_records");
  }

  @Test
  @DisplayName("적재한 구매자·판매자가 실제 로그인하고 Gateway 검증을 통과해 상품·주소·샘플 주문을 조회한다")
  void logsInAndQueriesSeededData() throws Exception {
    loader.load(PASSWORD);

    for (String role : List.of("buyer", "seller")) {
      var login =
          http.postForEntity(
              "/api/" + role + "-sessions",
              Map.of("email", "fixture-" + role + "-a@example.test", "password", PASSWORD),
              JsonNode.class);
      assertThat(login.getStatusCode().value()).isEqualTo(200);
      JsonNode session = login.getBody();
      String hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(session.get("token").asText().getBytes(StandardCharsets.UTF_8)));
      assertThat(
              redis.execute(
                  VERIFY, List.of("commerce-settle:auth:session:" + EPOCH + ":" + hash), EPOCH))
          .isEqualTo(
              "AUTHENTICATED:"
                  + role.toUpperCase()
                  + ":"
                  + session.get("account").get("id").asText());
    }
    assertThat(get("/api/products", null).get("totalElements").asInt()).isEqualTo(25);
    JsonNode page = get("/api/buyers/me/addresses", id(1));
    assertThat(page.get("totalElements").asInt()).isEqualTo(23);
    assertThat(page.get("items").get(0).get("addressLine1").asText()).isEqualTo("가상시 테스트구 샘플로 1");
    assertThat(get("/api/orders", id(1)).get("totalElements").asInt()).isEqualTo(10);
    assertThat(
            jdbc.queryForObject(
                "SELECT available_qty FROM commerce.product_inventory WHERE product_id=?",
                Long.class,
                id(3)))
        .isEqualTo(100);
    assertThat(count("inventory_movements")).isEqualTo(38);
    String storedHash =
        jdbc.queryForObject(
            "SELECT password_hash FROM commerce.buyer_accounts WHERE id=?", String.class, id(1));
    assertThat(storedHash).startsWith("$argon2id$");
    byte[] ciphertext =
        jdbc.queryForObject(
            "SELECT address_ciphertext FROM commerce.buyer_addresses WHERE id=?",
            byte[].class,
            id(101));
    assertThat(new String(ciphertext, StandardCharsets.UTF_8)).doesNotContain("가상시");
  }

  @Test
  @DisplayName("다시 적재해도 비밀번호·가격·재고·주소·버전과 이력을 덮어쓰지 않는다")
  void preservesChangesOnRepeatedLoad() {
    loader.load(PASSWORD);
    String changedHash = passwords.hash("changed-fixture-password");
    jdbc.update(
        "UPDATE commerce.buyer_accounts SET password_hash=?, auth_version=1, version=1 WHERE id=?",
        changedHash,
        id(1));
    jdbc.update("UPDATE commerce.products SET unit_price=27000, version=1 WHERE id=?", id(3));
    jdbc.update(
        "UPDATE commerce.product_inventory SET available_qty=37, version=1 WHERE product_id=?",
        id(3));
    ShippingAddress changed =
        new ShippingAddress("실험 수정", "000-0000-0001", "00002", "가상시 수정구 2", "");
    addresses.change(id(1), id(101), "fixture-change-address", changed, true, 0);
    byte[] ciphertext =
        jdbc.queryForObject(
            "SELECT address_ciphertext FROM commerce.buyer_addresses WHERE id=?",
            byte[].class,
            id(101));

    UUID orderId = fixtureId("request-order-0");
    jdbc.update(
        "UPDATE commerce.orders SET product_name='보존할 주문 상품명',version=1 WHERE id=?", orderId);
    var orderAddress = orders.findAddressByBuyerAndOrderId(id(11), orderId).orElseThrow();
    byte[] orderCiphertext = orderAddress.getSealed().address();
    loader.load("another-fixture-password");
    assertThat(orders.findByBuyerAndId(id(11), orderId).orElseThrow().getProductName())
        .isEqualTo("보존할 주문 상품명");
    assertThat(
            orders
                .findAddressByBuyerAndOrderId(id(11), orderId)
                .orElseThrow()
                .getSealed()
                .address())
        .isEqualTo(orderCiphertext);

    assertThat(
            jdbc.queryForObject(
                "SELECT password_hash FROM commerce.buyer_accounts WHERE id=?",
                String.class,
                id(1)))
        .isEqualTo(changedHash);
    assertThat(
            jdbc.queryForObject(
                "SELECT unit_price FROM commerce.products WHERE id=?", Long.class, id(3)))
        .isEqualTo(27000);
    assertThat(
            jdbc.queryForObject(
                "SELECT available_qty FROM commerce.product_inventory WHERE product_id=?",
                Long.class,
                id(3)))
        .isEqualTo(37);
    assertThat(
            addresses.addresses(id(1), 0, 100).items().stream()
                .filter(a -> a.id().equals(id(101)))
                .findFirst()
                .orElseThrow()
                .shipping())
        .isEqualTo(changed);
    assertThat(
            jdbc.queryForObject(
                "SELECT address_ciphertext FROM commerce.buyer_addresses WHERE id=?",
                byte[].class,
                id(101)))
        .isEqualTo(ciphertext);
    assertThat(count("buyer_accounts")).isEqualTo(8);
    assertThat(count("inventory_movements")).isEqualTo(38);
  }

  @Test
  @DisplayName("논리삭제한 계정·상품·주소를 재기동 적재로 복구하지 않는다")
  void preservesLogicalDeletions() {
    loader.load(PASSWORD);
    jdbc.update(
        "UPDATE commerce.buyer_accounts SET status='DELETED',deleted_at=now() WHERE id=?", id(11));
    jdbc.update(
        "UPDATE commerce.seller_accounts SET status='DELETED',deleted_at=now() WHERE id=?", id(22));
    jdbc.update(
        "UPDATE commerce.products SET status='DELETED',deleted_at=now() WHERE id=?", id(23));
    addresses.delete(id(1), id(101), "fixture-delete-address", 0);

    loader.load(PASSWORD);

    assertThat(get("/api/products", null).get("totalElements").asInt()).isEqualTo(24);
    assertThat(addresses.addresses(id(1), 0, 100).items())
        .hasSize(22)
        .noneMatch(a -> a.id().equals(id(101)));
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM commerce.buyer_accounts WHERE id=?", String.class, id(11)))
        .isEqualTo("DELETED");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM commerce.seller_accounts WHERE id=?", String.class, id(22)))
        .isEqualTo("DELETED");
    assertThat(count("buyer_addresses")).isEqualTo(29);
  }

  @Test
  @DisplayName("계정 충돌로 적재가 실패하면 앞서 추가한 계정도 롤백하고 기존 행은 보존한다")
  void rollsBackPartialLoadOnConflict() {
    UUID existing = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO commerce.buyer_accounts
          (id,email_normalized,password_hash,display_name,status,auth_version,created_at,updated_at,version)
        VALUES (?,'fixture-buyer-b@example.test','existing-hash','기존 계정','ACTIVE',0,now(),now(),0)
        """,
        existing);

    assertThatThrownBy(() -> loader.load(PASSWORD))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(count("buyer_accounts")).isEqualTo(1);
    assertThat(count("seller_accounts")).isZero();
    assertThat(count("products")).isZero();
    assertThat(jdbc.queryForObject("SELECT id FROM commerce.buyer_accounts", UUID.class))
        .isEqualTo(existing);
  }

  @Test
  @DisplayName("동시에 두 번 적재해도 계정·주소·재고 이력이 중복되지 않는다")
  void concurrentStartupIsIdempotent() throws Exception {
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> loader.load(PASSWORD));
      var second = executor.submit(() -> loader.load(PASSWORD));
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    }
    assertThat(count("buyer_accounts")).isEqualTo(8);
    assertThat(count("buyer_addresses")).isEqualTo(29);
    assertThat(count("inventory_movements")).isEqualTo(38);
  }

  @Test
  @DisplayName("기본값에서는 적재기를 만들지 않으며 켰는데 비밀번호가 없으면 기동을 거절한다")
  void defaultsOffAndRequiresPasswordWhenEnabled() {
    ApplicationContextRunner context =
        new ApplicationContextRunner()
            .withUserConfiguration(DevFixtureConfiguration.class)
            .withBean(JdbcOperations.class, () -> mock(JdbcOperations.class))
            .withBean(PasswordHasher.class, () -> mock(PasswordHasher.class))
            .withBean(AddressCipher.class, () -> mock(AddressCipher.class))
            .withBean(
                InventoryReservationService.class, () -> mock(InventoryReservationService.class));
    context.run(c -> assertThat(c).doesNotHaveBean(DevFixtureLoader.class));
    context.withPropertyValues("app.dev-fixtures.enabled=true").run(c -> assertThat(c).hasFailed());
  }

  @Test
  @DisplayName("공개·판매자·주소 페이지와 빈 결과·권한·상태 및 재고 원장을 실제 API와 DB로 확인한다")
  void coversPagesOwnershipAndInventoryStates() {
    loader.load(PASSWORD);
    assertThat(get("/api/products?page=1", null).get("items").size()).isEqualTo(5);
    HttpHeaders seller = new HttpHeaders();
    seller.set("X-Authenticated-Actor-Kind", "SELLER");
    seller.set("X-Authenticated-Actor-Id", id(2).toString());
    var products =
        http.exchange(
            "/api/sellers/me/products?page=1",
            HttpMethod.GET,
            new HttpEntity<>(seller),
            JsonNode.class);
    assertThat(products.getStatusCode().value()).isEqualTo(200);
    assertThat(products.getBody().get("items").size()).isPositive();
    assertThat(get("/api/buyers/me/addresses?page=1", id(1)).get("items").size()).isEqualTo(3);
    assertThat(addresses.addresses(id(41), 0, 20).items()).isEmpty();
    assertThat(addresses.addresses(id(61), 0, 20).items()).hasSize(2).noneMatch(a -> a.isDefault());
    assertThat(addresses.addresses(id(51), 0, 20).items()).hasSize(2);
    HttpHeaders buyer = new HttpHeaders();
    buyer.set("X-Authenticated-Actor-Kind", "BUYER");
    buyer.set("X-Authenticated-Actor-Id", id(11).toString());
    buyer.set("Idempotency-Key", "fixture-other-owner");
    var change =
        Map.of(
            "recipient",
            "실험",
            "phone",
            "000-0000-0000",
            "postalCode",
            "00001",
            "addressLine1",
            "가상시 테스트구",
            "addressLine2",
            "",
            "isDefault",
            false,
            "expectedVersion",
            0);
    assertThat(
            http.exchange(
                    "/api/buyers/me/addresses/" + id(101),
                    HttpMethod.PUT,
                    new HttpEntity<>(change, buyer),
                    JsonNode.class)
                .getStatusCode()
                .value())
        .isEqualTo(404);
    for (int account : new int[] {71, 81}) {
      assertThat(
              http.postForEntity(
                      "/api/buyer-sessions",
                      Map.of(
                          "email",
                          "fixture-buyer-" + account + "@example.test",
                          "password",
                          PASSWORD),
                      JsonNode.class)
                  .getStatusCode()
                  .value())
          .isEqualTo(401);
    }
    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT status FROM commerce.inventory_reservations", String.class))
        .containsExactlyInAnyOrder("RESERVED", "CONSUMED", "RELEASED", "RESTORED");
    assertThat(
            jdbc.queryForObject(
                """
        SELECT count(*) FROM commerce.product_inventory i WHERE
        (i.available_qty,i.reserved_qty,i.sold_qty) <> (
          SELECT coalesce(sum(available_delta),0),coalesce(sum(reserved_delta),0),coalesce(sum(sold_delta),0)
          FROM commerce.inventory_movements m WHERE m.product_id=i.product_id)
        """,
                Integer.class))
        .isZero();
  }

  @Test
  @DisplayName("DB 주문 요청 사본의 소유권·페이지·금액·기한·암호화 주소를 확인한다")
  void queriesPersistentOrderSnapshots() {
    loader.load(PASSWORD);
    assertThat(orders.findByBuyer(id(1), 0, 1).totalElements()).isEqualTo(2);
    assertThat(orders.findByBuyer(id(1), 1, 1).items()).hasSize(1);
    assertThat(orders.findByBuyer(id(51), 0, 20).items()).isEmpty();
    UUID orderId = fixtureId("request-order-1");
    var order = orders.findByBuyerAndId(id(1), orderId).orElseThrow();
    assertThat(order.getAmounts().mileageAmount()).isEqualTo(1_000);
    assertThat(order.getAmounts().externalAmount()).isEqualTo(22_000);
    assertThat(order.getPaymentCycleId()).isEqualTo(fixtureId("request-cycle-1"));
    assertThat(order.getPaymentWaitUntil())
        .isEqualTo(java.time.Instant.parse("2026-09-01T00:10:00Z"));
    assertThat(orders.findByBuyerAndId(id(11), orderId)).isEmpty();
    var address = orders.findAddressByBuyerAndOrderId(id(1), orderId).orElseThrow();
    assertThat(cipher.open(address.getId(), address.getSealed()).addressLine1())
        .isEqualTo("가상시 테스트구 샘플로 1");
    assertThat(orders.findAddressByBuyerAndOrderId(id(11), orderId)).isEmpty();
  }

  private static UUID fixtureId(String name) {
    return UUID.nameUUIDFromBytes(("dev-fixture-v1:" + name).getBytes(StandardCharsets.UTF_8));
  }

  private JsonNode get(String path, UUID buyer) {
    HttpHeaders headers = new HttpHeaders();
    if (buyer != null) {
      headers.set("X-Authenticated-Actor-Kind", "BUYER");
      headers.set("X-Authenticated-Actor-Id", buyer.toString());
    }
    var response = http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    return response.getBody();
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM commerce." + table, Integer.class);
  }

  private static UUID id(int suffix) {
    return UUID.fromString("00000000-0000-4000-8000-" + String.format("%012d", suffix));
  }
}
