package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.commerce.application.port.AddressCipher;
import com.commercesettle.commerce.domain.model.ProductStatus;
import com.commercesettle.commerce.domain.model.ShippingAddress;
import com.commercesettle.commerce.domain.repository.OrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.tracing.enabled=false",
      "app.auth.session-epoch=00000000-0000-4000-8000-000000000000",
      "app.auth.rate-limit.identifier-hmac-key=regular-order-api-test-hmac-key-32bytes",
      "app.auth.password-reset.delivery-key=regular-order-api-test-delivery-key-32bytes",
      "app.address.encryption-key=regular-order-api-test-address-key-32bytes"
    })
@Testcontainers
class OrderIntegrationTest {
  private static final ShippingAddress SHIPPING =
      new ShippingAddress("시험 수령인", "000-0000-0000", "00001", "가상시 시험로 1", "101호");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired BuyerAddressService addresses;
  @Autowired ProductCatalogService catalog;
  @Autowired OrderRepository orders;
  @Autowired AddressCipher cipher;
  @Autowired PlatformTransactionManager transactions;

  @Test
  @DisplayName("재고 행 잠금 대기 상한은 주문 멱등 기록까지 롤백하고 잠금 해제 뒤 같은 키로 접수한다")
  void boundsInventoryLockWaitAndRollsBackIdempotency() throws Exception {
    var f = fixture(2);
    String key = key();
    long addressCount =
        jdbc.queryForObject("select count(*) from commerce.order_addresses", Long.class);
    long outboxCount = jdbc.queryForObject("select count(*) from commerce.outbox", Long.class);
    long movements = count("inventory_movements", "product_id", f.product());
    try (var holder = jdbc.getDataSource().getConnection()) {
      holder.setAutoCommit(false);
      try (var statement =
          holder.prepareStatement(
              "select id from commerce.product_inventory where product_id=? for update")) {
        statement.setObject(1, f.product());
        statement.executeQuery().close();
      }
      try {
        Instant started = Instant.now();
        problem(post(f.buyer(), key, body(f)), 503, "COMMON_TEMPORARILY_UNAVAILABLE");
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(6));
        assertThat(count("orders", "buyer_id", f.buyer())).isZero();
        assertThat(count("inventory_reservations", "product_id", f.product())).isZero();
        assertThat(count("inventory_movements", "product_id", f.product())).isEqualTo(movements);
        assertThat(jdbc.queryForObject("select count(*) from commerce.order_addresses", Long.class))
            .isEqualTo(addressCount);
        assertThat(jdbc.queryForObject("select count(*) from commerce.outbox", Long.class))
            .isEqualTo(outboxCount);
        assertThat(
                jdbc.queryForObject(
                    "select count(*) from commerce.idempotency_records where actor_id=? and operation='ORDER_CREATE'",
                    Long.class,
                    f.buyer().toString()))
            .isZero();
        assertInventory(f.product(), 2, 0);
      } finally {
        holder.rollback();
      }
    }
    assertThat(post(f.buyer(), key, body(f)).getStatusCode().value()).isEqualTo(201);
    assertInventory(f.product(), 1, 1);
  }

  @Test
  @DisplayName("주소 변경의 구매자 행 잠금이 풀린 뒤 새 배송지를 읽어 주문 사본을 만든다")
  void serializesAddressSnapshotWithBookChange() throws Exception {
    var f = fixture(2);
    var updated = new ShippingAddress("변경 수령인", "000-1111-2222", "00002", "가상시 새주소 2", "");
    CountDownLatch changed = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var change =
          pool.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .execute(
                          status -> {
                            addresses.change(f.buyer(), f.address(), key(), updated, true, 0);
                            changed.countDown();
                            try {
                              if (!release.await(10, TimeUnit.SECONDS))
                                throw new IllegalStateException("test release timed out");
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(e);
                            }
                            return true;
                          }));
      assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
      var create = pool.submit(() -> post(f.buyer(), key(), body(f)));
      try {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> create.get(150, TimeUnit.MILLISECONDS))
            .isInstanceOf(java.util.concurrent.TimeoutException.class);
      } finally {
        release.countDown();
      }
      assertThat(change.get(10, TimeUnit.SECONDS)).isTrue();
      var result = create.get(10, TimeUnit.SECONDS);
      assertThat(result.getStatusCode().value()).isEqualTo(201);
      UUID orderId = UUID.fromString(result.getBody().path("id").asText());
      var snapshot = orders.findAddressByBuyerAndOrderId(f.buyer(), orderId).orElseThrow();
      assertThat(cipher.open(snapshot.getId(), snapshot.getSealed())).isEqualTo(updated);
    } finally {
      release.countDown();
    }
  }

  @ParameterizedTest
  @CsvSource({"0,10000,CREDIT_CARD,test-card", "1000,9000,EASY_PAY,test-pay", "10000,0,NULL,NULL"})
  @DisplayName("실제 HTTP 주문·주소·재고·멱등 기록·Outbox를 한 번 저장하고 금액과 명세를 보존한다")
  void createsOrderWithAtomicPaymentRequest(long mileage, long external, String method, String ref)
      throws Exception {
    var fixture = fixture(3);
    var body = body(fixture).put("mileageAmount", mileage);
    if (external == 0) {
      body.putNull("externalMethod").putNull("methodAccountRef");
    } else {
      body.put("externalMethod", method).put("methodAccountRef", ref);
    }
    String key = key();
    var created = post(fixture.buyer(), key, body);
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    var order = created.getBody();
    assertThat(order.path("status").asText()).isEqualTo("PAYMENT_PENDING");
    assertThat(order.path("externalAmount").asLong()).isEqualTo(external);
    assertThat(order.path("createdAt").asText()).endsWith("+09:00");
    assertThat(created.getHeaders().getLocation().toString())
        .isEqualTo("/api/orders/" + order.path("id").asText());
    matches("Order", order);
    matches("OrderCreate", body);
    UUID orderId = UUID.fromString(order.path("id").asText());
    var stored = orders.findByBuyerAndId(fixture.buyer(), orderId).orElseThrow();
    var snapshot = orders.findAddressByBuyerAndOrderId(fixture.buyer(), orderId).orElseThrow();
    assertThat(snapshot.getId()).isNotEqualTo(fixture.address());
    assertThat(cipher.open(snapshot.getId(), snapshot.getSealed())).isEqualTo(SHIPPING);
    assertInventory(fixture.product(), 2, 1);
    var event =
        mapper.readTree(
            jdbc.queryForObject(
                "select raw_body from commerce.outbox where aggregate_id=?",
                byte[].class,
                orderId));
    matches("EventEnvelope", event);
    matches("OrderPaymentRequestedPayload", event.path("payload"));
    assertThat(event.path("eventType").asText()).isEqualTo("OrderPaymentRequested");
    assertThat(event.path("aggregateId").asText()).isEqualTo(orderId.toString());
    assertThat(event.path("aggregateVersion").asLong()).isEqualTo(stored.getVersion());
    assertThat(event.path("payload").path("orderVersion").asLong()).isEqualTo(stored.getVersion());
    assertThat(event.path("payload").path("paymentCycleId").asText())
        .isEqualTo(stored.getPaymentCycleId().toString());
    assertThat(event.path("payload").path("mileageAmount").asLong()).isEqualTo(mileage);
    assertThat(event.path("payload").path("externalAmount").asLong()).isEqualTo(external);
    assertThat(Instant.parse(event.path("payload").path("paymentWaitUntil").asText()))
        .isEqualTo(stored.getCreatedAt().plusSeconds(600));
    assertThat(
            jdbc.queryForMap(
                "select topic,message_key from commerce.outbox where aggregate_id=?", orderId))
        .containsEntry("topic", "commerce-settle.commerce.v1")
        .containsEntry("message_key", orderId.toString());
    assertThat(
            jdbc.queryForMap(
                "select expires_at from commerce.idempotency_records where resource_id=?", orderId))
        .containsEntry("expires_at", null);
    // 최초 성공 뒤 주소와 상품이 사라져도 같은 요청은 기존 주문으로 연결한다.
    addresses.delete(fixture.buyer(), fixture.address(), key(), 0);
    catalog.delete(fixture.seller(), fixture.product(), key(), 0);
    assertThat(post(fixture.buyer(), key, body).getBody()).isEqualTo(order);
    assertThat(get(fixture.buyer(), "/api/orders/" + orderId).getBody()).isEqualTo(order);
    assertThat(cipher.open(snapshot.getId(), snapshot.getSealed())).isEqualTo(SHIPPING);
    problem(
        post(fixture.buyer(), key, body.deepCopy().put("quantity", 2)),
        409,
        "COMMON_IDEMPOTENCY_CONFLICT");
    if (external == 0) {
      problem(
          post(fixture.buyer(), key, body.deepCopy().put("methodAccountRef", "null")),
          409,
          "COMMON_IDEMPOTENCY_CONFLICT");
    }
    assertThat(count("orders", "id", orderId)).isEqualTo(1);
    assertThat(count("inventory_reservations", "order_id", orderId)).isEqualTo(1);
    assertThat(count("outbox", "aggregate_id", orderId)).isEqualTo(1);
    assertInventory(fixture.product(), 2, 1);
  }

  @Test
  @DisplayName("Outbox의 실제 DB 저장 실패는 주문·주소·재고·멱등 기록을 롤백하고 같은 키로 재시도된다")
  void rollsBackEveryWriteIfOutboxFails() {
    var f = fixture(2);
    String key = key();
    long addressCount =
        jdbc.queryForObject("select count(*) from commerce.order_addresses", Long.class);
    long outboxCount = jdbc.queryForObject("select count(*) from commerce.outbox", Long.class);
    long movements = count("inventory_movements", "product_id", f.product());
    jdbc.execute(
        "CREATE FUNCTION commerce.reject_order_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected storage failure'; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_order_outbox BEFORE INSERT ON commerce.outbox FOR EACH ROW EXECUTE FUNCTION commerce.reject_order_outbox()");
    try {
      assertThat(post(f.buyer(), key, body(f)).getStatusCode().is5xxServerError()).isTrue();
      assertThat(count("orders", "buyer_id", f.buyer())).isZero();
      assertThat(count("inventory_reservations", "product_id", f.product())).isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from commerce.idempotency_records where actor_id=? and operation='ORDER_CREATE'",
                  Long.class,
                  f.buyer().toString()))
          .isZero();
      assertInventory(f.product(), 2, 0);
      assertThat(jdbc.queryForObject("select count(*) from commerce.order_addresses", Long.class))
          .isEqualTo(addressCount);
      assertThat(jdbc.queryForObject("select count(*) from commerce.outbox", Long.class))
          .isEqualTo(outboxCount);
      assertThat(count("inventory_movements", "product_id", f.product())).isEqualTo(movements);
    } finally {
      jdbc.execute("DROP TRIGGER reject_order_outbox ON commerce.outbox");
      jdbc.execute("DROP FUNCTION commerce.reject_order_outbox()");
    }
    assertThat(post(f.buyer(), key, body(f)).getStatusCode().value()).isEqualTo(201);
    assertInventory(f.product(), 1, 1);
  }

  @Test
  @DisplayName("서로 다른 구매자가 마지막 재고를 동시에 주문해도 한 건만 접수한다")
  void concurrentOrdersCannotOversell() throws Exception {
    var first = fixture(1);
    UUID other = buyer();
    UUID address = addresses.register(other, key(), SHIPPING, true).id();
    var second = new Fixture(other, address, first.seller(), first.product());
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures =
          List.of(first, second).stream()
              .map(
                  f ->
                      pool.submit(
                          () -> {
                            start.await();
                            return post(f.buyer(), key(), body(f));
                          }))
              .toList();
      start.countDown();
      var responses =
          List.of(
              futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS));
      assertThat(responses)
          .extracting(r -> r.getStatusCode().value())
          .containsExactlyInAnyOrder(201, 409);
      assertThat(
              responses.stream()
                  .filter(r -> r.getStatusCode().value() == 409)
                  .findFirst()
                  .orElseThrow()
                  .getBody()
                  .path("code")
                  .asText())
          .isEqualTo("INVENTORY_INSUFFICIENT");
    }
    assertInventory(first.product(), 0, 1);
    assertThat(count("orders", "product_id", first.product())).isEqualTo(1);
    assertThat(count("inventory_reservations", "product_id", first.product())).isEqualTo(1);
  }

  @Test
  @DisplayName("같은 키의 동시 생성은 같은 주문으로 모이고 예약·이벤트를 중복 저장하지 않는다")
  void concurrentSameKeyCreatesOnce() throws Exception {
    var f = fixture(3);
    String key = key();
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures =
          java.util.stream.IntStream.range(0, 2)
              .mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            start.await();
                            return post(f.buyer(), key, body(f));
                          }))
              .toList();
      start.countDown();
      for (var future : futures) {
        assertThat(future.get(15, TimeUnit.SECONDS).getStatusCode().value()).isIn(201, 409);
      }
    }
    assertThat(post(f.buyer(), key, body(f)).getStatusCode().value()).isEqualTo(201);
    assertThat(count("orders", "buyer_id", f.buyer())).isEqualTo(1);
    assertInventory(f.product(), 2, 1);
  }

  @Test
  @DisplayName("타인 조회는 404이고 목록은 본인 자료만 같은 시각의 ID 역순으로 페이지한다")
  void scopesAndPaginatesDbReads() {
    var f = fixture(4);
    var ids =
        java.util.stream.IntStream.range(0, 3)
            .mapToObj(i -> post(f.buyer(), key(), body(f)).getBody().path("id").asText())
            .sorted(Comparator.reverseOrder())
            .toList();
    jdbc.update(
        "update commerce.orders set created_at='2026-01-01T00:00:00Z' where buyer_id=?", f.buyer());
    var page = get(f.buyer(), "/api/orders?size=2").getBody();
    assertThat(page.path("totalElements").asInt()).isEqualTo(3);
    assertThat(page.path("totalPages").asInt()).isEqualTo(2);
    assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(ids.get(0));
    assertThat(page.path("items").get(1).path("id").asText()).isEqualTo(ids.get(1));
    assertThat(
            get(f.buyer(), "/api/orders?page=1&size=2")
                .getBody()
                .path("items")
                .get(0)
                .path("id")
                .asText())
        .isEqualTo(ids.get(2));
    UUID other = buyer();
    problem(get(other, "/api/orders/" + ids.get(0)), 404, "COMMON_RESOURCE_NOT_FOUND");
    assertThat(get(other, "/api/orders").getBody().path("totalElements").asInt()).isZero();
    problem(get(f.buyer(), "/api/orders?size=101"), 400, "COMMON_VALIDATION_FAILED");
    problem(get(f.buyer(), "/api/orders?page=-1"), 400, "COMMON_VALIDATION_FAILED");
  }

  @Test
  @DisplayName("가격·버전 변경과 판매 중단·타인/삭제 주소를 거절하고 예약을 남기지 않는다")
  void rejectsInvalidReferencesAndPrices() {
    var f = fixture(3);
    problem(
        post(f.buyer(), key(), body(f).put("expectedProductVersion", 1)),
        409,
        "COMMON_VERSION_CONFLICT");
    problem(
        post(f.buyer(), key(), body(f).put("expectedTotalAmount", 9999)),
        409,
        "COMMON_VERSION_CONFLICT");
    var other = fixture(1);
    problem(
        post(f.buyer(), key(), body(f).put("addressId", other.address().toString())),
        404,
        "COMMON_RESOURCE_NOT_FOUND");
    addresses.delete(f.buyer(), f.address(), key(), 0);
    problem(post(f.buyer(), key(), body(f)), 404, "COMMON_RESOURCE_NOT_FOUND");
    catalog.change(
        other.seller(), other.product(), key(), "중단 상품", 10000, ProductStatus.STOPPED, 0);
    problem(
        post(other.buyer(), key(), body(other).put("expectedProductVersion", 1)),
        404,
        "COMMON_RESOURCE_NOT_FOUND");
    assertInventory(f.product(), 3, 0);
    assertInventory(other.product(), 1, 0);
    assertThat(count("orders", "buyer_id", f.buyer())).isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "productId",
        "quantity",
        "eventId",
        "buyerCouponId",
        "mileageAmount",
        "addressId",
        "expectedProductVersion",
        "expectedEventVersion",
        "expectedTotalAmount",
        "externalMethod",
        "methodAccountRef"
      })
  @DisplayName("nullable 필드를 포함한 정식 입력 11개 키는 모두 필수다")
  void requiresEveryKey(String field) {
    var f = fixture(1);
    var body = body(f);
    body.remove(field);
    problem(post(f.buyer(), key(), body), 400, "COMMON_VALIDATION_FAILED");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "eventId",
        "buyerCouponId",
        "expectedEventVersion",
        "mileageAmount",
        "externalMethod",
        "methodAccountRef",
        "quantity",
        "unknown"
      })
  @DisplayName("미지원 기능·잘못된 금액/수단·추가 필드를 입력 오류로 거절한다")
  void rejectsUnsupportedAndInvalidInput(String field) {
    var f = fixture(1);
    var body = body(f);
    switch (field) {
      case "eventId", "buyerCouponId" -> body.put(field, UUID.randomUUID().toString());
      case "expectedEventVersion" -> body.put(field, 0);
      case "mileageAmount" -> body.put(field, 10001);
      case "externalMethod" -> body.putNull(field);
      case "methodAccountRef" -> body.put(field, "   ");
      case "quantity" -> body.put(field, 1.5);
      default -> body.put(field, true);
    }
    problem(post(f.buyer(), key(), body), 400, "COMMON_VALIDATION_FAILED");
    assertInventory(f.product(), 1, 0);
  }

  @Test
  @DisplayName("기존 키 재조회도 정지 구매자는 거절하며 새 주문을 만들지 않는다")
  void rejectsSuspendedBuyerAndWrongRole() {
    var f = fixture(2);
    String key = key();
    assertThat(post(f.buyer(), key, body(f)).getStatusCode().value()).isEqualTo(201);
    jdbc.update("update commerce.buyer_accounts set status='SUSPENDED' where id=?", f.buyer());
    problem(post(f.buyer(), key, body(f)), 401, "COMMON_AUTHENTICATION_REQUIRED");
    problem(get(f.buyer(), "/api/orders"), 401, "COMMON_AUTHENTICATION_REQUIRED");
    var headers = headers(f.buyer());
    headers.set("X-Authenticated-Actor-Kind", "SELLER");
    problem(
        http.exchange("/api/orders", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class),
        403,
        "COMMON_ROLE_FORBIDDEN");
    assertInventory(f.product(), 1, 1);
  }

  @Test
  @DisplayName("생성·조회·목록의 런타임 명세는 기본 DB API와 OrderCreate 11필드를 제공한다")
  void servesRealApiDocumentation() {
    var api = http.getForObject("/v3/api-docs", JsonNode.class);
    assertThat(api.path("paths").path("/api/orders").path("post").path("summary").asText())
        .isEqualTo("일반 주문 생성");
    var schema = api.path("components").path("schemas").path("OrderCreate");
    assertThat(schema.path("required").size()).isEqualTo(11);
    assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
  }

  private Fixture fixture(long stock) {
    UUID buyer = buyer();
    UUID address = addresses.register(buyer, key(), SHIPPING, true).id();
    UUID seller = UUID.randomUUID();
    UUID product =
        catalog.register(seller, key(), "실제 주문 시험 상품", 10000, ProductStatus.ON_SALE).getId();
    catalog.adjustAvailable(seller, product, key(), stock, 0, "시험 입고");
    return new Fixture(buyer, address, seller, product);
  }

  private UUID buyer() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into commerce.buyer_accounts (id,email_normalized,password_hash,display_name,status,created_at,updated_at,version) values (?,?,'hash','구매자','ACTIVE',now(),now(),0)",
        id,
        id + "@example.com");
    return id;
  }

  private ObjectNode body(Fixture f) {
    var body =
        mapper
            .createObjectNode()
            .put("productId", f.product().toString())
            .put("quantity", 1)
            .put("mileageAmount", 0)
            .put("addressId", f.address().toString())
            .put("expectedProductVersion", 0)
            .put("expectedTotalAmount", 10000)
            .put("externalMethod", "CREDIT_CARD")
            .put("methodAccountRef", "test-card");
    body.putNull("eventId").putNull("buyerCouponId").putNull("expectedEventVersion");
    return body;
  }

  private ResponseEntity<JsonNode> post(UUID buyer, String key, ObjectNode body) {
    var headers = headers(buyer);
    headers.set("Idempotency-Key", key);
    return http.exchange(
        "/api/orders", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
  }

  private ResponseEntity<JsonNode> get(UUID buyer, String url) {
    return http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers(buyer)), JsonNode.class);
  }

  private static HttpHeaders headers(UUID buyer) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Authenticated-Actor-Kind", "BUYER");
    headers.set("X-Authenticated-Actor-Id", buyer.toString());
    return headers;
  }

  private static void problem(ResponseEntity<JsonNode> response, int status, String code) {
    assertThat(response.getStatusCode().value()).isEqualTo(status);
    assertThat(response.getBody().path("code").asText()).isEqualTo(code);
  }

  private void matches(String name, JsonNode node) throws Exception {
    var canonical = mapper.readTree(getClass().getResourceAsStream("/contracts/openapi.json"));
    var root = mapper.createObjectNode();
    root.set("components", canonical.path("components"));
    root.put("$ref", "#/components/schemas/" + name);
    var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    var schema =
        registry.getSchema(
            SchemaLocation.of("urn:test:order:" + name), root.toString(), InputFormat.JSON);
    assertThat(schema.validate(node.toString(), InputFormat.JSON)).isEmpty();
  }

  private long count(String table, String field, UUID id) {
    return jdbc.queryForObject(
        "select count(*) from commerce." + table + " where " + field + "=?", Long.class, id);
  }

  private void assertInventory(UUID product, long available, long reserved) {
    assertThat(
            jdbc.queryForMap(
                "select available_qty,reserved_qty,sold_qty from commerce.product_inventory where product_id=?",
                product))
        .containsEntry("available_qty", available)
        .containsEntry("reserved_qty", reserved)
        .containsEntry("sold_qty", 0L);
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private record Fixture(UUID buyer, UUID address, UUID seller, UUID product) {}
}
