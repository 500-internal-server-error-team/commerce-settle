package com.commercesettle.commerce.application.service;

import static com.commercesettle.commerce.support.ConcurrentTransactions.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.domain.model.BuyerCoupon;
import com.commercesettle.commerce.domain.model.ProductStatus;
import com.commercesettle.commerce.domain.model.ShippingAddress;
import com.commercesettle.commerce.infrastructure.messaging.PaymentAcceptedListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL과 HTTP 경로로 웰컴 쿠폰의 발급·조회와 주문의 예약·사용·해제·복구, 템플릿 예산 집계를 확인한다. */
// 인증 설정은 필수 기동 값이다. 만료는 시각을 넘겨 직접 호출하므로 스케줄러를 끈다.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.tracing.enabled=false",
      "app.coupons.expiry-scheduler-enabled=false",
      "app.auth.session-epoch=00000000-0000-4000-8000-000000000000",
      "app.auth.rate-limit.identifier-hmac-key=coupon-order-test-hmac-key-32bytes-long",
      "app.auth.password-reset.delivery-key=coupon-order-test-delivery-key-32bytes",
      "app.address.encryption-key=coupon-order-test-address-key-32bytes-long",
      "app.internal-auth.senders.gateway.current=" + CouponOrderIntegrationTest.GATEWAY,
      "app.internal-auth.senders[service\\:payment].current=" + CouponOrderIntegrationTest.PAYMENT
    })
@Testcontainers
class CouponOrderIntegrationTest {
  static final String GATEWAY = "test-gateway-credential-0123456789abcdef0123";
  static final String PAYMENT = "test-payment-credential-0123456789abcdef0123";
  private static final long PRICE = 25_000;
  private static final long DISCOUNT = 3_000;

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  @Autowired TestRestTemplate http;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired BuyerAddressService addresses;
  @Autowired ProductCatalogService products;
  @Autowired CouponService coupons;
  @Autowired OrderResultService results;
  @Autowired PaymentAcceptedService accepted;

  // 금융 관측 기반 접수 제한은 이 시험의 대상이 아니므로 항상 접수한다.
  @MockitoBean OrderAdmissionService admission;

  @Test
  @DisplayName("웰컴 쿠폰은 같은 구매자의 동시·반복 발급에도 한 장이며, 활성 웰컴 캠페인은 하나에 발급 수·약속액이 쌓인다")
  void issuesOneWelcomePerBuyer() throws Exception {
    coupons.issueWelcome(buyer().id(), Instant.now());
    UUID buyer = buyer().id();
    Map<String, Object> before = welcomeTotals();

    List<Object> results =
        runConcurrently(6, () -> coupons.issueWelcome(buyer, Instant.now()).getId());
    UUID replay = coupons.issueWelcome(buyer, Instant.now()).getId();

    // 실제 발급은 가입 트랜잭션 안이라 경합하지 않는다. 경합해도 유일 인덱스가 한 장만 남기고 진 쪽은 집계 없이 롤백된다.
    assertThat(results).filteredOn(UUID.class::isInstance).isNotEmpty().containsOnly(replay);
    assertThat(results)
        .filteredOn(RuntimeException.class::isInstance)
        .allMatch(DataIntegrityViolationException.class::isInstance);
    assertThat(count("buyer_coupons where buyer_id = ?", buyer)).isEqualTo(1);
    assertThat(count("coupon_templates where kind = 'WELCOME' and status = 'ACTIVE'")).isEqualTo(1);
    Map<String, Object> after = welcomeTotals();
    assertThat((Integer) after.get("issued_count") - (Integer) before.get("issued_count"))
        .isEqualTo(1);
    assertThat((Long) after.get("committed_budget") - (Long) before.get("committed_budget"))
        .isEqualTo(DISCOUNT);
  }

  @Test
  @DisplayName("본인 쿠폰 목록은 계약 스키마대로 웰컴 쿠폰을 보여 주고 다른 구매자의 쿠폰은 보이지 않는다")
  void listsOwnCoupons() throws Exception {
    Buyer buyer = buyer();
    BuyerCoupon coupon = coupons.issueWelcome(buyer.id(), Instant.now());
    coupons.issueWelcome(buyer().id(), Instant.now());

    JsonNode page = send(buyer.id(), "/api/buyers/me/coupons", null, 200);

    matches("BuyerCouponPage", page);
    assertThat(page.path("totalElements").asLong()).isEqualTo(1);
    JsonNode item = page.path("items").get(0);
    assertThat(item.path("id").asText()).isEqualTo(coupon.getId().toString());
    assertThat(item.path("kind").asText()).isEqualTo("WELCOME");
    assertThat(item.path("funder").asText()).isEqualTo("PLATFORM");
    assertThat(item.path("status").asText()).isEqualTo("ISSUED");
    assertThat(item.path("discountAmount").asLong()).isEqualTo(DISCOUNT);
  }

  @Test
  @DisplayName("쿠폰 주문은 플랫폼 할인액을 반영해 예약하고, 결제 확정으로 사용·환불로 복구하며 약속액·사용액을 옮긴다")
  void reservesUsesAndRestoresCoupon() throws Exception {
    Buyer buyer = buyer();
    UUID product = product();
    UUID coupon = coupons.issueWelcome(buyer.id(), Instant.now()).getId();
    Map<String, Object> issued = welcomeTotals();

    JsonNode order = order(buyer, product, coupon, 201);
    UUID id = uuid(order, "id");
    assertThat(order.path("platformCouponAmount").asLong()).isEqualTo(DISCOUNT);
    assertThat(order.path("sellerCouponAmount").asLong()).isZero();
    assertThat(order.path("totalAmount").asLong()).isEqualTo(PRICE - DISCOUNT);
    assertThat(couponStatus(coupon)).isEqualTo("RESERVED");

    UUID payment = confirm(order);
    assertThat(couponStatus(coupon)).isEqualTo("USED");
    assertTotalsChanged(issued, -DISCOUNT, DISCOUNT);

    JsonNode request =
        send(
            buyer.id(),
            "/api/orders/" + id + "/refund-requests",
            version(read(buyer, id)).put("kind", "CANCEL").put("reason", "시험 취소"),
            201);
    ObjectNode refund =
        mapper
            .createObjectNode()
            .put("orderId", id.toString())
            .put("paymentId", payment.toString())
            .put("refundId", UUID.randomUUID().toString());
    receive("RefundAccepted", refund.deepCopy().put("requestId", request.path("id").asText()));
    receive("RefundCompleted", refund);

    assertThat(couponStatus(coupon)).isEqualTo("ISSUED");
    assertTotalsChanged(issued, 0, 0);
    assertThat(history(coupon)).containsExactly("ISSUE", "RESERVE", "USE", "RESTORE");
  }

  @Test
  @DisplayName("결제 거절은 쿠폰 예약을 유지해, 그사이 기한이 지나도 재시도가 원래 할인으로 결제 확정까지 이어진다")
  void keepsReservationAcrossDeclineAndRetryAfterDeadline() throws Exception {
    Buyer buyer = buyer();
    UUID product = product();
    UUID coupon = coupons.issueWelcome(buyer.id(), Instant.now()).getId();
    Map<String, Object> issued = welcomeTotals();
    UUID id = uuid(order(buyer, product, coupon, 201), "id");
    UUID payment = UUID.randomUUID();

    receive("PaymentDeclined", result(id, payment, cycle(id)));
    assertThat(couponStatus(coupon)).isEqualTo("RESERVED");

    expire(coupon);
    JsonNode retried =
        send(buyer.id(), "/api/orders/" + id + "/payment-retries", version(read(buyer, id)), 202);
    assertThat(retried.path("platformCouponAmount").asLong()).isEqualTo(DISCOUNT);
    assertThat(couponStatus(coupon)).isEqualTo("RESERVED");

    confirm(read(buyer, id), payment);
    assertThat(couponStatus(coupon)).isEqualTo("USED");
    assertTotalsChanged(issued, -DISCOUNT, DISCOUNT);
    assertThat(history(coupon)).containsExactly("ISSUE", "RESERVE", "USE");
  }

  @Test
  @DisplayName("거절 뒤 승인 차단 취소가 확정되면 그때 예약을 풀고, 기한이 지났으면 EXPIRED로 두며 약속액을 상쇄한다")
  void releasesOnCancellationAfterDecline() throws Exception {
    Buyer buyer = buyer();
    UUID product = product();
    UUID coupon = coupons.issueWelcome(buyer.id(), Instant.now()).getId();
    Map<String, Object> issued = welcomeTotals();
    UUID id = uuid(order(buyer, product, coupon, 201), "id");
    UUID payment = UUID.randomUUID();

    receive("PaymentDeclined", result(id, payment, cycle(id)));
    expire(coupon);
    send(
        buyer.id(),
        "/api/orders/" + id + "/cancellation",
        version(read(buyer, id)).put("reason", "시험 취소"),
        202);
    assertThat(couponStatus(coupon)).isEqualTo("RESERVED");
    receive("PaymentCancelled", result(id, payment, cycle(id)));

    assertThat(couponStatus(coupon)).isEqualTo("EXPIRED");
    assertTotalsChanged(issued, -DISCOUNT, 0);
    assertThat(history(coupon)).containsExactly("ISSUE", "RESERVE", "RELEASE");
  }

  @Test
  @DisplayName("만료 작업은 기한이 지난 미사용 쿠폰만 한 번 만료해 약속액을 상쇄하고 예약 중인 쿠폰은 그대로 둔다")
  void expiresDueUnusedCouponsOnce() {
    Buyer reservedBuyer = buyer();
    UUID unused = coupons.issueWelcome(buyer().id(), Instant.now()).getId();
    UUID reserved = coupons.issueWelcome(reservedBuyer.id(), Instant.now()).getId();
    order(reservedBuyer, product(), reserved, 201);
    expire(unused);
    expire(reserved);
    Map<String, Object> before = welcomeTotals();

    List<UUID> due = coupons.expiredCouponIds(Instant.now(), 1_000);
    boolean first = coupons.expireIfDue(unused, Instant.now());
    boolean again = coupons.expireIfDue(unused, Instant.now());

    assertThat(due).contains(unused).doesNotContain(reserved);
    assertThat(first).isTrue();
    assertThat(again).isFalse();
    assertThat(couponStatus(unused)).isEqualTo("EXPIRED");
    assertThat(couponStatus(reserved)).isEqualTo("RESERVED");
    assertTotalsChanged(before, -DISCOUNT, 0);
    assertThat(count("coupon_history where buyer_coupon_id = ? and operation = 'EXPIRE'", unused))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("같은 쿠폰으로 동시에 주문하면 하나만 예약하고 나머지는 쓸 수 없는 쿠폰으로 거절한다")
  void reservesCouponForOneOfConcurrentOrders() throws Exception {
    Buyer buyer = buyer();
    UUID product = product();
    UUID coupon = coupons.issueWelcome(buyer.id(), Instant.now()).getId();

    List<Object> codes =
        runConcurrently(
            5,
            () -> {
              JsonNode body = exchange(buyer.id(), "/api/orders", body(buyer, product, coupon));
              return body.path("code").asText("CREATED");
            });

    assertThat(codes).filteredOn("CREATED"::equals).hasSize(1);
    assertThat(codes).filteredOn("COUPON_NOT_APPLICABLE"::equals).hasSize(4);
    assertThat(count("buyer_coupons where id = ? and status = 'RESERVED'", coupon)).isEqualTo(1);
  }

  @Test
  @DisplayName("기한 지난 쿠폰은 COUPON_EXPIRED, 최소 금액 미달·다른 구매자의 쿠폰은 COUPON_NOT_APPLICABLE로 거절한다")
  void rejectsInapplicableCoupons() {
    Buyer buyer = buyer();
    UUID product = product();
    UUID cheap = product(10_000);
    UUID coupon = coupons.issueWelcome(buyer.id(), Instant.now()).getId();
    UUID othersCoupon = coupons.issueWelcome(buyer().id(), Instant.now()).getId();

    assertThat(
            send(buyer.id(), "/api/orders", body(buyer, cheap, coupon, 10_000 - DISCOUNT), 409)
                .path("detail")
                .asText())
        .isEqualTo("최소 주문금액보다 상품대금이 작습니다.");
    assertThat(order(buyer, product, othersCoupon, 409).path("code").asText())
        .isEqualTo("COUPON_NOT_APPLICABLE");
    expire(coupon);
    assertThat(order(buyer, product, coupon, 409).path("code").asText())
        .isEqualTo("COUPON_EXPIRED");
    assertThat(couponStatus(coupon)).isEqualTo("ISSUED");
  }

  private JsonNode order(Buyer buyer, UUID product, UUID coupon, int expected) {
    return send(buyer.id(), "/api/orders", body(buyer, product, coupon), expected);
  }

  private ObjectNode body(Buyer buyer, UUID product, UUID coupon) {
    return body(buyer, product, coupon, PRICE - DISCOUNT);
  }

  private ObjectNode body(Buyer buyer, UUID product, UUID coupon, long expectedTotal) {
    return mapper
        .createObjectNode()
        .put("productId", product.toString())
        .put("quantity", 1)
        .putNull("eventId")
        .put("buyerCouponId", coupon.toString())
        .put("mileageAmount", 0)
        .put("addressId", buyer.address().toString())
        .put("expectedProductVersion", 0)
        .putNull("expectedEventVersion")
        .put("expectedTotalAmount", expectedTotal)
        .put("externalMethod", "CREDIT_CARD")
        .put("methodAccountRef", "test-card");
  }

  /** 시험을 위해 쿠폰 기한을 지금보다 앞으로 옮긴다. */
  private void expire(UUID coupon) {
    jdbc.update(
        "update commerce.buyer_coupons set valid_from = now() - interval '2 days',"
            + " expires_at = now() - interval '1 second' where id = ?",
        coupon);
  }

  /** 결제 확정 결과를 반영하고 결제 ID를 돌려준다. */
  private UUID confirm(JsonNode order) throws Exception {
    return confirm(order, UUID.randomUUID());
  }

  /** 재시도는 같은 결제의 새 회차이므로 거절된 결제 ID로 확정한다. */
  private UUID confirm(JsonNode order, UUID payment) throws Exception {
    UUID id = uuid(order, "id");
    JsonNode context = internal("/internal/orders/" + id + "/payment-context");
    ObjectNode p = mapper.createObjectNode();
    for (String k :
        List.of(
            "grossAmount",
            "couponAmount",
            "totalAmount",
            "mileageAmount",
            "externalAmount",
            "buyerId",
            "sellerId",
            "productId",
            "sellerCouponAmount",
            "platformCouponAmount",
            "settlementBaseAmount")) p.set(k, order.required(k));
    p.put("orderId", id.toString())
        .put("paymentId", payment.toString())
        .put("paymentCycleId", context.path("paymentCycleId").asText())
        .put("orderVersion", context.path("orderVersion").asLong())
        .put("policyVersion", context.path("policyVersion").asText())
        .put("feeAmount", order.path("settlementBaseAmount").asLong() * 15 / 100)
        .put("sellerNetAmount", order.path("settlementBaseAmount").asLong() * 85 / 100)
        .put(
            "confirmedAt",
            Instant.now().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime().toString());
    receive("PaymentConfirmed", p);
    return payment;
  }

  private UUID cycle(UUID orderId) {
    return uuid(internal("/internal/orders/" + orderId + "/payment-context"), "paymentCycleId");
  }

  private ObjectNode result(UUID order, UUID payment, UUID cycle) {
    return mapper
        .createObjectNode()
        .put("orderId", order.toString())
        .put("paymentId", payment.toString())
        .put("paymentCycleId", cycle.toString())
        .put("reasonCode", "TEST_RESULT");
  }

  /** Payment 결과를 수신 경로로 넣고 바로 반영한다. */
  private void receive(String type, ObjectNode payload) throws Exception {
    UUID payment = uuid(payload, "paymentId");
    var event =
        new EventEnvelope<JsonNode>(
            UUID.randomUUID(),
            type,
            1,
            Instant.now().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),
            type.startsWith("Refund") ? uuid(payload, "refundId") : payment,
            0,
            payload);
    AtomicBoolean ack = new AtomicBoolean();
    new PaymentAcceptedListener(mapper, accepted, results)
        .receive(
            new ConsumerRecord<>(
                "commerce-settle.payment.v1",
                0,
                0,
                payment.toString(),
                mapper.writeValueAsBytes(event)),
            () -> ack.set(true));
    assertThat(ack).isTrue();
    assertThat(results.processEvent(event.eventId())).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select status from commerce.inbox where event_id = ?",
                String.class,
                event.eventId()))
        .isEqualTo("APPLIED");
  }

  private UUID product() {
    return product(PRICE);
  }

  private UUID product(long price) {
    UUID seller = UUID.randomUUID();
    jdbc.update(
        "insert into commerce.seller_accounts(id,email_normalized,password_hash,display_name,status,"
            + "payout_account_ref,created_at,updated_at,version)"
            + " values (?,?,'hash','seller','ACTIVE','test-account',now(),now(),0)",
        seller,
        seller + "@example.com");
    UUID product = products.register(seller, key(), "시험 상품", price, ProductStatus.ON_SALE).getId();
    products.adjustAvailable(seller, product, key(), 10, 0, "입고");
    return product;
  }

  private Buyer buyer() {
    UUID buyer = UUID.randomUUID();
    jdbc.update(
        "insert into commerce.buyer_accounts(id,email_normalized,password_hash,display_name,status,"
            + "created_at,updated_at,version) values (?,?,'hash','buyer','ACTIVE',now(),now(),0)",
        buyer,
        buyer + "@example.com");
    UUID address =
        addresses
            .register(
                buyer, key(), new ShippingAddress("시험 수령인", "000-0000", "00001", "시험로 1", ""), true)
            .id();
    return new Buyer(buyer, address);
  }

  private JsonNode read(Buyer buyer, UUID orderId) {
    return send(buyer.id(), "/api/orders/" + orderId, null, 200);
  }

  private ObjectNode version(JsonNode node) {
    return mapper.createObjectNode().put("expectedVersion", node.path("version").asLong());
  }

  private JsonNode send(UUID buyer, String path, ObjectNode body, int expected) {
    var response = call(buyer, path, body);
    assertThat(response.getStatusCode().value())
        .as("%s: %s", path, response.getBody())
        .isEqualTo(expected);
    return response.getBody();
  }

  /** 상태 코드를 단정하지 않고 본문만 돌려준다. 동시 요청 결과를 모을 때 쓴다. */
  private JsonNode exchange(UUID buyer, String path, ObjectNode body) {
    return call(buyer, path, body).getBody();
  }

  private org.springframework.http.ResponseEntity<JsonNode> call(
      UUID buyer, String path, ObjectNode body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(GATEWAY);
    headers.set("X-Authenticated-Actor-Kind", "BUYER");
    headers.set("X-Authenticated-Actor-Id", buyer.toString());
    return http(path, body, headers);
  }

  private JsonNode internal(String path) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(PAYMENT);
    var response = http(path, null, headers);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    return response.getBody();
  }

  private org.springframework.http.ResponseEntity<JsonNode> http(
      String path, ObjectNode body, HttpHeaders headers) {
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", key());
    return http.exchange(
        path,
        body == null ? HttpMethod.GET : HttpMethod.POST,
        new HttpEntity<>(body, headers),
        JsonNode.class);
  }

  private void matches(String name, JsonNode node) throws Exception {
    var canonical = mapper.readTree(getClass().getResourceAsStream("/contracts/openapi.json"));
    var root = mapper.createObjectNode();
    root.set("components", canonical.path("components"));
    root.put("$ref", "#/components/schemas/" + name);
    var schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(
                SchemaLocation.of("urn:test:coupon:" + name), root.toString(), InputFormat.JSON);
    assertThat(schema.validate(node.toString(), InputFormat.JSON)).isEmpty();
  }

  private Map<String, Object> welcomeTotals() {
    return jdbc.queryForMap(
        "select issued_count, committed_budget, consumed_budget from commerce.coupon_templates"
            + " where kind = 'WELCOME' and status = 'ACTIVE'");
  }

  private void assertTotalsChanged(Map<String, Object> before, long committed, long consumed) {
    Map<String, Object> now = welcomeTotals();
    assertThat((Long) now.get("committed_budget") - (Long) before.get("committed_budget"))
        .isEqualTo(committed);
    assertThat((Long) now.get("consumed_budget") - (Long) before.get("consumed_budget"))
        .isEqualTo(consumed);
  }

  private List<String> history(UUID coupon) {
    return jdbc.queryForList(
        "select operation from commerce.coupon_history where buyer_coupon_id = ?"
            + " order by created_at, id",
        String.class,
        coupon);
  }

  private String couponStatus(UUID coupon) {
    return jdbc.queryForObject(
        "select status from commerce.buyer_coupons where id = ?", String.class, coupon);
  }

  private long count(String fromWhere, Object... args) {
    return jdbc.queryForObject("select count(*) from commerce." + fromWhere, Long.class, args);
  }

  private static UUID uuid(JsonNode node, String field) {
    return UUID.fromString(node.required(field).asText());
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private record Buyer(UUID id, UUID address) {}
}
