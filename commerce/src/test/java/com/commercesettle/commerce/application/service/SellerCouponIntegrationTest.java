package com.commercesettle.commerce.application.service;

import static com.commercesettle.commerce.support.ConcurrentTransactions.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.commerce.application.event.EventEnvelope;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL과 HTTP 경로로 판매자 쿠폰 캠페인의 등록·조회·발급·종료와 판매자 부담 쿠폰 주문을 확인한다. */
// 인증 설정은 필수 기동 값이다. 만료는 이 시험 대상이 아니므로 스케줄러를 끈다.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.tracing.enabled=false",
      "app.coupons.expiry-scheduler-enabled=false",
      "app.auth.session-epoch=00000000-0000-4000-8000-000000000000",
      "app.auth.rate-limit.identifier-hmac-key=seller-coupon-test-hmac-key-32bytes-long",
      "app.auth.password-reset.delivery-key=seller-coupon-test-delivery-key-32bytes",
      "app.address.encryption-key=seller-coupon-test-address-key-32bytes-long",
      "app.internal-auth.senders.gateway.current=" + SellerCouponIntegrationTest.GATEWAY,
      "app.internal-auth.senders[service\\:payment].current=" + SellerCouponIntegrationTest.PAYMENT
    })
@Testcontainers
class SellerCouponIntegrationTest {
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
  @Autowired OrderResultService results;
  @Autowired PaymentAcceptedService accepted;

  // 금융 관측 기반 접수 제한은 이 시험의 대상이 아니므로 항상 접수한다.
  @MockitoBean OrderAdmissionService admission;

  @Test
  @DisplayName("캠페인 등록·목록·단건 응답이 계약 스키마와 맞고, 같은 키 재요청은 기존 캠페인을 돌려주며 타인 캠페인은 없는 것으로 본다")
  void registersAndListsCampaigns() throws Exception {
    Shop shop = shop();
    String key = key();
    // 재요청은 같은 본문이어야 하므로 시각이 든 등록 조건을 한 번만 만든다.
    ObjectNode terms = terms(shop, 10, 30_000);

    JsonNode created = seller(shop.seller(), "/api/sellers/me/coupon-templates", terms, 201, key);
    JsonNode replay = seller(shop.seller(), "/api/sellers/me/coupon-templates", terms, 201, key);
    JsonNode list = seller(shop.seller(), "/api/sellers/me/coupon-templates", null, 200, key());
    String path = "/api/sellers/me/coupon-templates/" + created.path("id").asText();

    matches("CouponTemplate", created);
    matches("CouponTemplatePage", list);
    assertThat(replay.path("id")).isEqualTo(created.path("id"));
    assertThat(created.path("kind").asText()).isEqualTo("SELLER");
    assertThat(created.path("funder").asText()).isEqualTo("SELLER");
    assertThat(list.path("totalElements").asLong()).isEqualTo(1);
    assertThat(seller(shop.seller(), path, null, 200, key()).path("id"))
        .isEqualTo(created.path("id"));
    assertThat(seller(UUID.randomUUID(), path, null, 404, key()).path("code").asText())
        .isEqualTo("COMMON_RESOURCE_NOT_FOUND");
  }

  @Test
  @DisplayName("타인 상품은 404, 할인 후 상품대금 20,000원 미만은 해당 필드의 입력 오류로 거절한다")
  void rejectsInvalidCampaigns() {
    Shop shop = shop();
    Shop other = shop();

    seller(shop.seller(), "/api/sellers/me/coupon-templates", terms(other, 10, 30_000), 404, key());
    JsonNode invalid =
        seller(
            shop.seller(),
            "/api/sellers/me/coupon-templates",
            terms(shop, 10, 30_000).put("minOrderAmount", 22_999),
            400,
            key());

    assertThat(invalid.path("code").asText()).isEqualTo("COMMON_VALIDATION_FAILED");
    assertThat(invalid.toString()).contains("minOrderAmount");
    assertThat(count("coupon_templates where seller_id = ?", shop.seller())).isZero();
  }

  @Test
  @DisplayName(
      "발급은 판매자 부담 쿠폰 한 장을 만들고 같은 구매자 새 요청은 COUPON_ALREADY_ISSUED, 같은 키 재요청은 기존 쿠폰, 없는 구매자는 404다")
  void issuesOneCouponPerBuyer() throws Exception {
    Shop shop = shop();
    UUID template = campaign(shop, 10, 30_000);
    Buyer buyer = buyer();
    String key = key();

    JsonNode coupon = issue(shop, template, buyer.id(), 201, key);
    JsonNode replay = issue(shop, template, buyer.id(), 201, key);
    JsonNode again = issue(shop, template, buyer.id(), 409, key());

    matches("BuyerCoupon", coupon);
    assertThat(replay.path("id")).isEqualTo(coupon.path("id"));
    assertThat(coupon.path("funder").asText()).isEqualTo("SELLER");
    assertThat(coupon.path("sellerId").asText()).isEqualTo(shop.seller().toString());
    assertThat(again.path("code").asText()).isEqualTo("COUPON_ALREADY_ISSUED");
    assertThat(again.path("detail").asText()).isEqualTo("이미 이 캠페인의 쿠폰을 받은 구매자입니다.");
    issue(shop, template, UUID.randomUUID(), 404, key());
    assertThat(totals(template))
        .containsEntry("issued_count", 1)
        .containsEntry("committed_budget", DISCOUNT);
  }

  @Test
  @DisplayName("발급 수 한도로 동시에 발급하면 한도만큼만 성공하고 나머지는 COUPON_BUDGET_EXHAUSTED다")
  void doesNotIssueBeyondLimitUnderConcurrency() throws Exception {
    Shop shop = shop();
    UUID template = campaign(shop, 3, 30_000);
    List<UUID> buyers =
        List.of(buyer().id(), buyer().id(), buyer().id(), buyer().id(), buyer().id(), buyer().id());
    var next = new java.util.concurrent.atomic.AtomicInteger();

    List<Object> codes =
        runConcurrently(
            6,
            () -> {
              UUID buyer = buyers.get(next.getAndIncrement());
              JsonNode body =
                  call("SELLER", shop.seller(), path(template) + "/issues", issueBody(buyer), key())
                      .getBody();
              return body.path("code").asText("CREATED");
            });

    assertThat(codes).filteredOn("CREATED"::equals).hasSize(3);
    assertThat(codes).filteredOn("COUPON_BUDGET_EXHAUSTED"::equals).hasSize(3);
    assertThat(totals(template))
        .containsEntry("issued_count", 3)
        .containsEntry("committed_budget", 3 * DISCOUNT);
  }

  @Test
  @DisplayName("예산이 할인액 한 장분만 남으면 그 뒤 발급은 COUPON_BUDGET_EXHAUSTED다")
  void stopsAtBudget() {
    Shop shop = shop();
    UUID template = campaign(shop, 10, 2 * DISCOUNT + 1);

    issue(shop, template, buyer().id(), 201, key());
    issue(shop, template, buyer().id(), 201, key());

    assertThat(issue(shop, template, buyer().id(), 409, key()).path("code").asText())
        .isEqualTo("COUPON_BUDGET_EXHAUSTED");
  }

  @Test
  @DisplayName(
      "발급 종료는 새 발급만 막고(COUPON_CAMPAIGN_NOT_ISSUABLE), 버전이 다르면 버전 충돌이며 다시 종료해도 발급 불가로 거절하고 이미 발급한 쿠폰은 그대로다")
  void closesCampaignForNewIssues() {
    Shop shop = shop();
    UUID template = campaign(shop, 10, 30_000);
    JsonNode coupon = issue(shop, template, buyer().id(), 201, key());

    JsonNode stale = seller(shop.seller(), path(template) + "/closure", version(1), 409, key());
    JsonNode closed = seller(shop.seller(), path(template) + "/closure", version(0), 200, key());
    JsonNode closedAgain =
        seller(shop.seller(), path(template) + "/closure", version(1), 409, key());
    JsonNode rejected = issue(shop, template, buyer().id(), 409, key());

    assertThat(stale.path("code").asText()).isEqualTo("COMMON_VERSION_CONFLICT");
    assertThat(closed.path("status").asText()).isEqualTo("CLOSED");
    assertThat(closedAgain.path("code").asText()).isEqualTo("COUPON_CAMPAIGN_NOT_ISSUABLE");
    assertThat(rejected.path("code").asText()).isEqualTo("COUPON_CAMPAIGN_NOT_ISSUABLE");
    assertThat(rejected.path("detail").asText()).isEqualTo("신규 발급을 종료한 캠페인입니다.");
    assertThat(couponStatus(UUID.fromString(coupon.path("id").asText()))).isEqualTo("ISSUED");
  }

  @Test
  @DisplayName("발급 기간 전 캠페인의 발급은 COUPON_CAMPAIGN_NOT_ISSUABLE과 사유로 거절하고 아무것도 발급하지 않는다")
  void rejectsIssueBeforeCampaignStarts() {
    Shop shop = shop();
    Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofHours(1));
    UUID template =
        UUID.fromString(
            seller(
                    shop.seller(),
                    "/api/sellers/me/coupon-templates",
                    terms(shop, 10, 30_000).put("validFrom", start.toString()),
                    201,
                    key())
                .path("id")
                .asText());

    JsonNode rejected = issue(shop, template, buyer().id(), 409, key());

    assertThat(rejected.path("code").asText()).isEqualTo("COUPON_CAMPAIGN_NOT_ISSUABLE");
    assertThat(rejected.path("detail").asText()).isEqualTo("아직 발급 기간이 아닌 캠페인입니다.");
    assertThat(totals(template)).containsEntry("issued_count", 0);
  }

  @Test
  @DisplayName("판매자 쿠폰 주문은 판매자 부담 할인으로 정산 기준액을 줄이고, 다른 상품에는 쓸 수 없으며 결제 확정이 캠페인 약속액을 사용액으로 옮긴다")
  void ordersWithSellerCoupon() throws Exception {
    Shop shop = shop();
    UUID template = campaign(shop, 10, 30_000);
    Buyer buyer = buyer();
    UUID coupon =
        UUID.fromString(issue(shop, template, buyer.id(), 201, key()).path("id").asText());
    UUID otherProduct = product(shop.seller());

    assertThat(order(buyer, otherProduct, coupon, 409).path("code").asText())
        .isEqualTo("COUPON_NOT_APPLICABLE");
    JsonNode order = order(buyer, shop.product(), coupon, 201);

    assertThat(order.path("sellerCouponAmount").asLong()).isEqualTo(DISCOUNT);
    assertThat(order.path("platformCouponAmount").asLong()).isZero();
    assertThat(order.path("settlementBaseAmount").asLong()).isEqualTo(PRICE - DISCOUNT);
    confirm(order);
    assertThat(couponStatus(coupon)).isEqualTo("USED");
    assertThat(totals(template))
        .containsEntry("committed_budget", 0L)
        .containsEntry("consumed_budget", DISCOUNT);
  }

  @Test
  @DisplayName("발급 종료 뒤 전액 환불도 원쿠폰을 약속액으로 되돌리고, 기한이 지난 뒤 환불은 EXPIRED로 두고 사용액만 상쇄해 미사용 예산으로 돌린다")
  void restoresSellerCouponsAfterClosureAndDeadline() throws Exception {
    Shop shop = shop();
    UUID template = campaign(shop, 10, 30_000);
    Buyer keptBuyer = buyer();
    Buyer lateBuyer = buyer();
    UUID kept =
        UUID.fromString(issue(shop, template, keptBuyer.id(), 201, key()).path("id").asText());
    UUID late =
        UUID.fromString(issue(shop, template, lateBuyer.id(), 201, key()).path("id").asText());
    JsonNode keptOrder = order(keptBuyer, shop.product(), kept, 201);
    JsonNode lateOrder = order(lateBuyer, shop.product(), late, 201);
    UUID keptPayment = confirm(keptOrder);
    UUID latePayment = confirm(lateOrder);
    seller(shop.seller(), path(template) + "/closure", version(0), 200, key());
    jdbc.update(
        "update commerce.buyer_coupons set expires_at = now() - interval '1 second' where id = ?",
        late);

    refund(keptBuyer, keptOrder, keptPayment);
    refund(lateBuyer, lateOrder, latePayment);

    assertThat(couponStatus(kept)).isEqualTo("ISSUED");
    assertThat(couponStatus(late)).isEqualTo("EXPIRED");
    assertThat(totals(template))
        .containsEntry("issued_count", 2)
        .containsEntry("committed_budget", DISCOUNT)
        .containsEntry("consumed_budget", 0L);
  }

  private UUID campaign(Shop shop, int limit, long budget) {
    return UUID.fromString(
        seller(
                shop.seller(),
                "/api/sellers/me/coupon-templates",
                terms(shop, limit, budget),
                201,
                key())
            .path("id")
            .asText());
  }

  private ObjectNode terms(Shop shop, int limit, long budget) {
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    ObjectNode body =
        mapper
            .createObjectNode()
            .put("name", "단골 감사")
            .put("productId", shop.product().toString())
            .put("discountAmount", DISCOUNT)
            .put("minOrderAmount", 23_000)
            .put("issuanceLimit", limit)
            .put("validFrom", now.minus(Duration.ofHours(1)).toString())
            .put("validUntil", now.plus(Duration.ofDays(7)).toString())
            .put("totalBudget", budget)
            .put("minSellerNetAmount", 0);
    body.putArray("allowedMethods").add("CREDIT_CARD").add("EASY_PAY");
    return body;
  }

  private JsonNode issue(Shop shop, UUID template, UUID buyer, int expected, String key) {
    return seller(shop.seller(), path(template) + "/issues", issueBody(buyer), expected, key);
  }

  private ObjectNode issueBody(UUID buyer) {
    return mapper.createObjectNode().put("buyerId", buyer.toString());
  }

  private static String path(UUID template) {
    return "/api/sellers/me/coupon-templates/" + template;
  }

  private ObjectNode version(long version) {
    return mapper.createObjectNode().put("expectedVersion", version);
  }

  private JsonNode order(Buyer buyer, UUID product, UUID coupon, int expected) {
    ObjectNode body =
        mapper
            .createObjectNode()
            .put("productId", product.toString())
            .put("quantity", 1)
            .putNull("eventId")
            .put("buyerCouponId", coupon.toString())
            .put("mileageAmount", 0)
            .put("addressId", buyer.address().toString())
            .put("expectedProductVersion", 0)
            .putNull("expectedEventVersion")
            .put("expectedTotalAmount", PRICE - DISCOUNT)
            .put("externalMethod", "CREDIT_CARD")
            .put("methodAccountRef", "test-card");
    return send("BUYER", buyer.id(), "/api/orders", body, expected, key());
  }

  /** 결제 확정 결과를 수신 경로로 넣고 바로 반영한다. 결제 ID를 돌려준다. */
  private UUID confirm(JsonNode order) throws Exception {
    UUID id = UUID.fromString(order.path("id").asText());
    HttpHeaders internal = new HttpHeaders();
    internal.setBearerAuth(PAYMENT);
    JsonNode context =
        exchange("/internal/orders/" + id + "/payment-context", null, internal).getBody();
    UUID payment = UUID.randomUUID();
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
    receive("PaymentConfirmed", p, payment);
    return payment;
  }

  /** 구매자 취소 요청과 Payment의 환불 접수·완료를 차례로 반영한다. */
  private void refund(Buyer buyer, JsonNode order, UUID payment) throws Exception {
    String id = order.path("id").asText();
    JsonNode current = send("BUYER", buyer.id(), "/api/orders/" + id, null, 200, key());
    JsonNode request =
        send(
            "BUYER",
            buyer.id(),
            "/api/orders/" + id + "/refund-requests",
            version(current.path("version").asLong()).put("kind", "CANCEL").put("reason", "시험 취소"),
            201,
            key());
    UUID refundId = UUID.randomUUID();
    ObjectNode refund =
        mapper
            .createObjectNode()
            .put("orderId", id)
            .put("paymentId", payment.toString())
            .put("refundId", refundId.toString());
    receive(
        "RefundAccepted",
        refund.deepCopy().put("requestId", request.path("id").asText()),
        refundId);
    receive("RefundCompleted", refund, refundId);
  }

  private void receive(String type, ObjectNode payload, UUID aggregate) throws Exception {
    UUID payment = UUID.fromString(payload.path("paymentId").asText());
    var event =
        new EventEnvelope<JsonNode>(
            UUID.randomUUID(),
            type,
            1,
            Instant.now().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),
            aggregate,
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

  private Shop shop() {
    UUID seller = UUID.randomUUID();
    jdbc.update(
        "insert into commerce.seller_accounts(id,email_normalized,password_hash,display_name,status,"
            + "payout_account_ref,created_at,updated_at,version)"
            + " values (?,?,'hash','seller','ACTIVE','test-account',now(),now(),0)",
        seller,
        seller + "@example.com");
    return new Shop(seller, product(seller));
  }

  private UUID product(UUID seller) {
    UUID product = products.register(seller, key(), "시험 상품", PRICE, ProductStatus.ON_SALE).getId();
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

  private JsonNode seller(UUID seller, String path, ObjectNode body, int expected, String key) {
    return send("SELLER", seller, path, body, expected, key);
  }

  private JsonNode send(
      String kind, UUID actor, String path, ObjectNode body, int expected, String key) {
    ResponseEntity<JsonNode> response = call(kind, actor, path, body, key);
    assertThat(response.getStatusCode().value())
        .as("%s: %s", path, response.getBody())
        .isEqualTo(expected);
    return response.getBody();
  }

  private ResponseEntity<JsonNode> call(
      String kind, UUID actor, String path, ObjectNode body, String key) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(GATEWAY);
    headers.set("X-Authenticated-Actor-Kind", kind);
    headers.set("X-Authenticated-Actor-Id", actor.toString());
    headers.set("Idempotency-Key", key);
    return exchange(path, body, headers);
  }

  private ResponseEntity<JsonNode> exchange(String path, ObjectNode body, HttpHeaders headers) {
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (!headers.containsKey("Idempotency-Key")) {
      headers.set("Idempotency-Key", key());
    }
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
                SchemaLocation.of("urn:test:seller-coupon:" + name),
                root.toString(),
                InputFormat.JSON);
    assertThat(schema.validate(node.toString(), InputFormat.JSON)).isEmpty();
  }

  private Map<String, Object> totals(UUID template) {
    return jdbc.queryForMap(
        "select issued_count, committed_budget, consumed_budget from commerce.coupon_templates"
            + " where id = ?",
        template);
  }

  private String couponStatus(UUID coupon) {
    return jdbc.queryForObject(
        "select status from commerce.buyer_coupons where id = ?", String.class, coupon);
  }

  private long count(String fromWhere, Object... args) {
    return jdbc.queryForObject("select count(*) from commerce." + fromWhere, Long.class, args);
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private record Shop(UUID seller, UUID product) {}

  private record Buyer(UUID id, UUID address) {}
}
