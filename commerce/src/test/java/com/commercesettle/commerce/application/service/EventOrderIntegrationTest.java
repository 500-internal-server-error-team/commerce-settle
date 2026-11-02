package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.domain.model.ProductStatus;
import com.commercesettle.commerce.domain.model.SaleEvent;
import com.commercesettle.commerce.domain.model.ShippingAddress;
import com.commercesettle.commerce.infrastructure.messaging.PaymentAcceptedListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL과 HTTP 경로로 선착순 주문이 OrderBenefits를 거쳐 이벤트 수량·참여·재고를 바꾸는지 확인한다. */
// 인증 설정은 필수 기동 값이다. 기간 처리는 시험이 정한 시각으로만 일어나도록 이벤트 스케줄러를 끈다.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.tracing.enabled=false",
      "app.sale-events.scheduler-enabled=false",
      "app.auth.session-epoch=00000000-0000-4000-8000-000000000000",
      "app.auth.rate-limit.identifier-hmac-key=event-order-test-hmac-key-32bytes-long",
      "app.auth.password-reset.delivery-key=event-order-test-delivery-key-32bytes",
      "app.address.encryption-key=event-order-test-address-key-32bytes-long",
      "app.internal-auth.senders.gateway.current=" + EventOrderIntegrationTest.GATEWAY,
      "app.internal-auth.senders[service\\:payment].current=" + EventOrderIntegrationTest.PAYMENT
    })
@Testcontainers
class EventOrderIntegrationTest {
  static final String GATEWAY = "test-gateway-credential-0123456789abcdef0123";
  static final String PAYMENT = "test-payment-credential-0123456789abcdef0123";
  private static final long EVENT_PRICE = 7_000;

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  @Autowired TestRestTemplate http;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired BuyerAddressService addresses;
  @Autowired ProductCatalogService products;
  @Autowired SaleEventService events;
  @Autowired OrderResultService results;
  @Autowired PaymentAcceptedService accepted;

  @Test
  @DisplayName("선착순 주문은 이벤트 가격으로 R을 잡고, 참여·확정이 이벤트 버전을 바꾸지 않아 같은 버전으로 계속 주문하며 남은 수량이 없으면 거절한다")
  void ordersAtEventPriceUntilNoRemaining() throws Exception {
    Sale sale = openSale(2);
    Buyer first = buyer();

    JsonNode order = order(sale, first, 201);
    order(sale, buyer(), 201);
    JsonNode rejected = order(sale, buyer(), 409);

    assertThat(order.path("grossAmount").asLong()).isEqualTo(EVENT_PRICE);
    assertThat(order.path("eventId").asText()).isEqualTo(sale.eventId().toString());
    assertThat(rejected.path("code").asText()).isEqualTo("EVENT_ADMISSION_CLOSED");
    assertSlots(sale, 0, 2);
    assertStock(sale, 8, 2, 0);
    confirm(order);
    assertSlots(sale, 1, 1);
    assertStock(sale, 8, 1, 1);
    assertThat(participation(uuid(order, "id"))).isEqualTo("CONFIRMED");
    assertThat(publicEvent(sale).path("version").asLong()).isEqualTo(sale.version());
  }

  @Test
  @DisplayName("결제가 거절되면 수량을 풀고 재시도에서 다시 잡으며, 같은 구매자의 다른 주문은 참여 중이라 거절한다")
  void releasesOnDeclineAndRetakesOnRetry() throws Exception {
    Sale sale = openSale(5);
    Buyer buyer = buyer();
    JsonNode order = order(sale, buyer, 201);
    UUID id = uuid(order, "id");

    receive("PaymentDeclined", result(id, UUID.randomUUID(), cycle(id)));
    assertSlots(sale, 0, 0);
    assertThat(participation(id)).isEqualTo("RELEASED");
    exchange(buyer.id(), "/api/orders/" + id + "/payment-retries", version(read(buyer, id)), 202);
    assertSlots(sale, 0, 1);
    assertThat(participation(id)).isEqualTo("RESERVED");

    assertThat(order(sale, buyer, 409).path("detail").asText()).isEqualTo("이미 참여한 이벤트입니다.");
    assertSlots(sale, 0, 1);
    assertStock(sale, 5, 5, 0);
  }

  @Test
  @DisplayName("미발송 주문의 환불 복구는 판매 1개를 가용으로 돌리고 이벤트 판매량과 참여 한도는 유지한다")
  void restoresUnshippedRefundWithoutReopeningEvent() throws Exception {
    Sale sale = openSale(5);
    Buyer buyer = buyer();
    JsonNode order = order(sale, buyer, 201);
    UUID id = uuid(order, "id");
    UUID payment = confirm(order);
    JsonNode request =
        exchange(
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

    assertThat(read(buyer, id).path("status").asText()).isEqualTo("REFUNDED");
    assertStock(sale, 6, 4, 0);
    assertSlots(sale, 1, 0);
    assertThat(participation(id)).isEqualTo("REFUNDED");
    assertThat(order(sale, buyer, 409).path("code").asText()).isEqualTo("EVENT_ADMISSION_CLOSED");
  }

  @Test
  @DisplayName("발송한 선착순 주문은 재판매 가능 반품을 입고할 때 판매 1개를 가용으로 돌린다")
  void restoresShippedEventOrderOnReturnReceipt() throws Exception {
    Sale sale = openSale(5);
    Buyer buyer = buyer();
    JsonNode order = order(sale, buyer, 201);
    UUID id = uuid(order, "id");
    UUID payment = confirm(order);
    seller(
        sale,
        "/api/sellers/me/orders/" + id + "/shipments",
        version(read(buyer, id)).put("shipmentReference", "test-parcel"),
        200);
    JsonNode request =
        exchange(
            buyer.id(),
            "/api/orders/" + id + "/refund-requests",
            version(read(buyer, id)).put("kind", "RETURN").put("reason", "시험 반품"),
            201);
    UUID requestId = uuid(request, "id");
    seller(
        sale,
        "/api/sellers/me/refund-requests/" + requestId + "/decision",
        version(request).put("decision", "APPROVE").put("reason", "확인"),
        202);
    receive(
        "RefundAccepted",
        mapper
            .createObjectNode()
            .put("orderId", id.toString())
            .put("paymentId", payment.toString())
            .put("refundId", UUID.randomUUID().toString())
            .put("requestId", requestId.toString()));
    assertStock(sale, 5, 4, 1);

    seller(
        sale,
        "/api/sellers/me/refund-requests/" + requestId + "/receipt",
        version(exchange(buyer.id(), "/api/refund-requests/" + requestId, null, 200)),
        200);

    assertStock(sale, 6, 4, 0);
    assertSlots(sale, 1, 0);
    assertThat(participation(id)).isEqualTo("REFUNDED");
  }

  @Test
  @DisplayName("시작 전 이벤트는 신청 기간이 아니라고, 다른 상품의 이벤트는 없는 이벤트로 거절한다")
  void rejectsNotStartedOrMismatchedEvent() {
    Sale scheduled = sale(2, Duration.ofHours(1));
    Sale other = openSale(2);
    Buyer buyer = buyer();

    JsonNode notOpen = order(scheduled, buyer, 409);
    JsonNode mismatched =
        exchange(
            buyer.id(),
            "/api/orders",
            body(scheduled.productId(), other.eventId(), other.version(), buyer),
            404);

    assertThat(notOpen.path("detail").asText()).isEqualTo("신청 기간이 아닌 이벤트입니다.");
    assertThat(mismatched.path("code").asText()).isEqualTo("COMMON_RESOURCE_NOT_FOUND");
    assertSlots(scheduled, 0, 0);
    assertSlots(other, 0, 0);
  }

  private JsonNode order(Sale sale, Buyer buyer, int expected) {
    return exchange(
        buyer.id(),
        "/api/orders",
        body(sale.productId(), sale.eventId(), sale.version(), buyer),
        expected);
  }

  private ObjectNode body(UUID product, UUID event, long eventVersion, Buyer buyer) {
    return mapper
        .createObjectNode()
        .put("productId", product.toString())
        .put("quantity", 1)
        .put("eventId", event.toString())
        .putNull("buyerCouponId")
        .put("mileageAmount", 0)
        .put("addressId", buyer.address().toString())
        .put("expectedProductVersion", 0)
        .put("expectedEventVersion", eventVersion)
        .put("expectedTotalAmount", EVENT_PRICE)
        .put("externalMethod", "CREDIT_CARD")
        .put("methodAccountRef", "test-card");
  }

  /** 결제 확정 결과를 반영하고 결제 ID를 돌려준다. */
  private UUID confirm(JsonNode order) throws Exception {
    UUID id = uuid(order, "id");
    JsonNode context = internal("/internal/orders/" + id + "/payment-context");
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

  private Sale openSale(long quota) {
    return sale(quota, Duration.ofHours(-1));
  }

  /** 판매자·가용 10개 상품을 만들고 startsIn 뒤에 시작하는 이벤트를 공개한다. */
  private Sale sale(long quota, Duration startsIn) {
    UUID seller = UUID.randomUUID();
    jdbc.update(
        "insert into commerce.seller_accounts(id,email_normalized,password_hash,display_name,status,"
            + "payout_account_ref,created_at,updated_at,version)"
            + " values (?,?,'hash','seller','ACTIVE','test-account',now(),now(),0)",
        seller,
        seller + "@example.com");
    UUID product = products.register(seller, key(), "한정 상품", 10_000, ProductStatus.ON_SALE).getId();
    products.adjustAvailable(seller, product, key(), 10, 0, "입고");
    Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(startsIn);
    SaleEvent draft =
        events.register(
            seller,
            key(),
            product,
            "주말 특가",
            EVENT_PRICE,
            quota,
            start,
            start.plus(Duration.ofHours(3)));
    SaleEvent published = events.publish(seller, draft.getId(), key(), draft.getVersion());
    return new Sale(seller, product, published.getId(), published.getVersion());
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
    return exchange(buyer.id(), "/api/orders/" + orderId, null, 200);
  }

  private JsonNode publicEvent(Sale sale) {
    return exchange(UUID.randomUUID(), "/api/events/" + sale.eventId(), null, 200);
  }

  private ObjectNode version(JsonNode node) {
    return mapper.createObjectNode().put("expectedVersion", node.path("version").asLong());
  }

  private JsonNode exchange(UUID buyer, String path, ObjectNode body, int expected) {
    return actor("BUYER", buyer, path, body, expected);
  }

  private JsonNode seller(Sale sale, String path, ObjectNode body, int expected) {
    return actor("SELLER", sale.sellerId(), path, body, expected);
  }

  private JsonNode actor(String kind, UUID id, String path, ObjectNode body, int expected) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(GATEWAY);
    headers.set("X-Authenticated-Actor-Kind", kind);
    headers.set("X-Authenticated-Actor-Id", id.toString());
    return send(path, body, expected, headers);
  }

  private JsonNode internal(String path) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(PAYMENT);
    return send(path, null, 200, headers);
  }

  private JsonNode send(String path, ObjectNode body, int expected, HttpHeaders headers) {
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", key());
    var response =
        http.exchange(
            path,
            body == null ? HttpMethod.GET : HttpMethod.POST,
            new HttpEntity<>(body, headers),
            JsonNode.class);
    assertThat(response.getStatusCode().value())
        .as("%s: %s", path, response.getBody())
        .isEqualTo(expected);
    return response.getBody();
  }

  private void assertSlots(Sale sale, long sold, long reserved) {
    Map<String, Object> row =
        jdbc.queryForMap(
            "select sold_count, reserved_count from commerce.sale_events where id = ?",
            sale.eventId());
    assertThat(row).containsEntry("sold_count", sold).containsEntry("reserved_count", reserved);
  }

  private void assertStock(Sale sale, long available, long allocated, long sold) {
    assertThat(
            jdbc.queryForMap(
                "select available_qty, reserved_qty, event_allocated_qty, sold_qty"
                    + " from commerce.product_inventory where product_id = ?",
                sale.productId()))
        .containsEntry("available_qty", available)
        .containsEntry("reserved_qty", 0L)
        .containsEntry("event_allocated_qty", allocated)
        .containsEntry("sold_qty", sold);
  }

  private String participation(UUID orderId) {
    return jdbc.queryForObject(
        "select status from commerce.event_participations where order_id = ?",
        String.class,
        orderId);
  }

  private static UUID uuid(JsonNode node, String field) {
    return UUID.fromString(node.required(field).asText());
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private record Sale(UUID sellerId, UUID productId, UUID eventId, long version) {}

  private record Buyer(UUID id, UUID address) {}
}
