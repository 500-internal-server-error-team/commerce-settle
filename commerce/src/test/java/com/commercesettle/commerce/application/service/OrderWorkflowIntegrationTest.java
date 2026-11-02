package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.application.port.OrderBenefits;
import com.commercesettle.commerce.application.port.PaymentAcceptanceInbox;
import com.commercesettle.commerce.application.port.RefundClosureEvidence;
import com.commercesettle.commerce.domain.model.Order;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.tracing.enabled=false",
      "app.auth.session-epoch=00000000-0000-4000-8000-000000000000",
      "app.auth.rate-limit.identifier-hmac-key=order-workflow-test-hmac-key-32bytes",
      "app.auth.password-reset.delivery-key=order-workflow-test-delivery-key-32bytes",
      "app.address.encryption-key=order-workflow-test-address-key-32bytes",
      "app.internal-auth.senders.gateway.current=" + OrderWorkflowIntegrationTest.GATEWAY,
      "app.internal-auth.senders[service\\:payment].current="
          + OrderWorkflowIntegrationTest.PAYMENT,
      "app.internal-auth.senders[operator\\:test].current=" + OrderWorkflowIntegrationTest.OPERATOR
    })
@Testcontainers
@Import(OrderWorkflowIntegrationTest.Beans.class)
class OrderWorkflowIntegrationTest {
  @MockitoBean OrderAdmissionService admission;
  static final String GATEWAY = "test-gateway-credential-0123456789abcdef0123";
  static final String PAYMENT = "test-payment-credential-0123456789abcdef0123";
  static final String OPERATOR = "test-operator-credential-0123456789abcdef0123";
  static final Instant NOW = Instant.parse("2026-11-02T01:00:00.123456Z");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  @Autowired TestRestTemplate http;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired BuyerAddressService addresses;
  @Autowired ProductCatalogService products;
  @Autowired OrderWorkflowService workflow;
  @Autowired OrderResultService results;
  @Autowired PaymentAcceptedService accepted;
  @Autowired PaymentAcceptanceInbox inbox;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  @Autowired TestClock clock;
  @Autowired ClosureDouble closure;
  @Autowired NotificationService notifications;

  @BeforeEach
  void reset() {
    clock.now.set(NOW);
    closure.safe = false;
    for (String table :
        List.of(
            "notification_attempts",
            "notification_items",
            "notification_deliveries",
            "notification_mock_receipts",
            "notification_inbox_quarantines")) jdbc.update("DELETE FROM commerce." + table);
    jdbc.update("DELETE FROM commerce.inbox WHERE consumer='commerce-notifications.v1'");
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS commerce.test_benefit_calls (order_id uuid, operation varchar(20))");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  @DisplayName("롤백 뒤 새 트랜잭션의 Inbox 검토·완료 저장도 잠금 대기 상한을 지킨다")
  void boundsInboxFinalizationAfterRollback(boolean review) throws Exception {
    var f = fixture();
    var o = create(f);
    var id = uuid(o, "id");
    var event =
        new EventEnvelope<JsonNode>(
            UUID.randomUUID(),
            "PaymentDeclined",
            1,
            NOW.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),
            UUID.randomUUID(),
            0,
            result(id, UUID.randomUUID(), uuid(context(id), "paymentCycleId")));
    results.accept(
        event,
        new PaymentAcceptanceInbox.Source(
            "commerce-settle.payment.v1", 0, 0, mapper.writeValueAsBytes(event)));
    var inboxId =
        jdbc.queryForObject(
            "select id from commerce.inbox where event_id=?", UUID.class, event.eventId());
    var transactions = new org.springframework.transaction.support.TransactionTemplate(manager);
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    status -> {
                      assertThat(inbox.claimResult(event.eventId(), clock.instant())).isPresent();
                      throw new IllegalStateException("시험 롤백");
                    }))
        .isInstanceOf(IllegalStateException.class);

    try (var holder = jdbc.getDataSource().getConnection();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      holder.setAutoCommit(false);
      try (var statement =
          holder.prepareStatement("select id from commerce.inbox where id=? for update")) {
        statement.setObject(1, inboxId);
        statement.executeQuery().close();
      }
      var work =
          executor.submit(
              () ->
                  transactions.executeWithoutResult(
                      status -> {
                        if (review) inbox.review(inboxId, "ORDER_RESULT_CONFLICT", clock.instant());
                        else inbox.applied(inboxId, id, clock.instant());
                      }));
      try {
        var failure =
            org.assertj.core.api.Assertions.catchThrowable(
                () -> work.get(3, java.util.concurrent.TimeUnit.SECONDS));
        assertThat(failure)
            .isInstanceOf(java.util.concurrent.ExecutionException.class)
            .hasCauseInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(
                ((org.springframework.dao.DataAccessException) failure.getCause())
                    .getMostSpecificCause())
            .isInstanceOfSatisfying(
                java.sql.SQLException.class,
                error -> assertThat(error.getSQLState()).isEqualTo("55P03"));
      } finally {
        holder.rollback();
      }
    }
    assertThat(
            jdbc.queryForMap(
                "select status,attempts,result_ref from commerce.inbox where id=?", inboxId))
        .containsEntry("status", "WAITING")
        .containsEntry("attempts", 0)
        .containsEntry("result_ref", null);
  }

  @Test
  @DisplayName("결제 확정 감사는 원 수신 이벤트 ID를 보존하고 재수신으로 중복되지 않는다")
  void preservesPaymentAuditEventId() throws Exception {
    var f = fixture();
    var o = create(f);
    var event = receive("PaymentConfirmed", confirmed(o, context(uuid(o, "id"))), true);

    assertEventAuditAndReplay(event, 1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"RefundCompleted", "RefundFailed"})
  @DisplayName("환불 접수·완료·실패 감사도 원 수신 이벤트 ID를 보존한다")
  void preservesRefundAuditEventIds(String type) throws Exception {
    var f = fixture();
    var o = create(f);
    var id = uuid(o, "id");
    var payment = UUID.randomUUID();
    receive(
        "PaymentConfirmed", confirmed(o, context(id)).put("paymentId", payment.toString()), true);
    var r = refund(f, id, "CANCEL");
    var payload =
        mapper
            .createObjectNode()
            .put("orderId", id.toString())
            .put("paymentId", payment.toString())
            .put("refundId", UUID.randomUUID().toString());
    var acceptedEvent =
        receive("RefundAccepted", payload.deepCopy().put("requestId", r.path("id").asText()), true);
    assertEventAuditAndReplay(acceptedEvent, 1);
    if (type.equals("RefundFailed"))
      payload.put("reasonCode", "DECLINED").put("reservationReleased", true);
    var completedEvent = receive(type, payload, true);

    assertEventAuditAndReplay(completedEvent, type.equals("RefundCompleted") ? 2 : 1);
  }

  private void assertEventAuditAndReplay(UUID event, int count) {
    assertThat(number("select count(*) from commerce.audit_logs where event_id=?", event))
        .isEqualTo(count);
    byte[] body =
        jdbc.queryForObject(
            "select raw_body from commerce.inbox where event_id=?", byte[].class, event);
    var ack = new java.util.concurrent.atomic.AtomicBoolean();
    var payment =
        jdbc.queryForObject(
            "select payload->'payload'->>'paymentId' from commerce.inbox where event_id=?",
            String.class,
            event);
    new PaymentAcceptedListener(mapper, accepted, results)
        .receive(
            new ConsumerRecord<>("commerce-settle.payment.v1", 0, 1, payment, body),
            () -> ack.set(true));
    assertThat(ack).isTrue();
    assertThat(results.processEvent(event)).isFalse();
    assertThat(number("select count(*) from commerce.audit_logs where event_id=?", event))
        .isEqualTo(count);
  }

  @Test
  @DisplayName("실제 주문·결제 확정·판매자 조회·발송·배송·자동 구매확정을 한 흐름으로 실행한다")
  void runsBuyerAndSellerFlowAgainstRealDatabase() throws Exception {
    var f = fixture();
    var quote = orderBody(f);
    quote.remove(
        List.of(
            "addressId",
            "expectedProductVersion",
            "expectedEventVersion",
            "expectedTotalAmount",
            "methodAccountRef"));
    var q = call("BUYER", f.buyer(), "/api/order-quotes", quote, 200);
    matches("OrderQuote", q);
    assertThat(
            number(
                "select count(*) from commerce.inventory_reservations where product_id=?",
                f.product()))
        .isZero();
    var order = create(f);
    var id = uuid(order, "id");
    var context = internal("/internal/orders/" + id + "/payment-context", PAYMENT, null, 200);
    matches("PaymentContext", context);
    internal("/internal/orders/" + id + "/payment-context", GATEWAY, null, 403);
    assertThat(seller(f, id).path("deliveryAddress").isNull()).isTrue();
    receive("PaymentConfirmed", confirmed(order, context), true);
    var paid = buyer(f, id);
    assertThat(paid.path("status").asText()).isEqualTo("PAID");
    assertStock(f, 1, 0, 1);
    var seller = seller(f, id);
    matches("SellerOrder", seller);
    assertThat(seller.path("deliveryAddress").path("recipient").asText()).isEqualTo("시험 수령인");
    call("SELLER", UUID.randomUUID(), "/api/sellers/me/orders/" + id, null, 401);
    var shipment =
        mapper
            .createObjectNode()
            .put("expectedVersion", paid.path("version").asLong())
            .put("shipmentReference", "test-parcel");
    String key = UUID.randomUUID().toString();
    var shipped =
        call(
            "SELLER",
            f.seller(),
            "/api/sellers/me/orders/" + id + "/shipments",
            shipment,
            200,
            key);
    assertThat(
            call(
                "SELLER",
                f.seller(),
                "/api/sellers/me/orders/" + id + "/shipments",
                shipment,
                200,
                key))
        .isEqualTo(shipped);
    var due = java.time.OffsetDateTime.parse(shipped.path("deliveryDueAt").asText()).toInstant();
    clock.now.set(due.plusSeconds(100));
    workflow.advance(id);
    var delivered = buyer(f, id);
    assertThat(delivered.path("deliveredAt")).isEqualTo(shipped.path("deliveryDueAt"));
    var deadline =
        java.time.OffsetDateTime.parse(delivered.path("refundDeadlineAt").asText()).toInstant();
    clock.now.set(deadline);
    workflow.advance(id);
    assertThat(buyer(f, id).path("status").asText()).isEqualTo("PAID");
    clock.now.set(deadline.plusSeconds(1));
    workflow.advance(id);
    workflow.advance(id);
    assertThat(buyer(f, id).path("status").asText()).isEqualTo("PURCHASE_CONFIRMED");
    assertThat(
            number(
                "select count(*) from commerce.outbox where aggregate_id=? and event_type='PurchaseConfirmed'",
                id))
        .isEqualTo(1);
    for (String kind : List.of("SALE_COMPLETED", "SHIPMENT_STARTED", "PURCHASE_CONFIRMED"))
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM commerce.notification_items WHERE kind=? AND business_ref=?",
                  Long.class,
                  kind,
                  id))
          .isEqualTo(1);
  }

  @Test
  @DisplayName("결제 notice는 주문 반영을 기다리고 뒤늦은 알림도 원래 잔액 사본과 발생 시각을 보존한다")
  void waitsForPaymentAndPreservesHistoricalSnapshot() throws Exception {
    var f = fixture();
    var order =
        call("BUYER", f.buyer(), "/api/orders", orderBody(f).put("mileageAmount", 1000), 201);
    UUID id = uuid(order, "id"), payment = UUID.randomUUID();
    var ctx = context(id);
    var notice = paymentNotice(order, ctx, payment, "PAYMENT_COMPLETED", null);
    UUID event = receiveNotice(notice);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM commerce.inbox WHERE event_id=?", String.class, event))
        .isEqualTo("WAITING");
    assertThat(
            number(
                "SELECT count(*) FROM commerce.notification_items WHERE source_event_id=?", event))
        .isZero();
    receive("PaymentConfirmed", confirmed(order, ctx).put("paymentId", payment.toString()), true);
    var shipped = ship(f, id);
    clock.now.set(
        java.time.OffsetDateTime.parse(shipped.path("deliveryDueAt").asText())
            .toInstant()
            .plusSeconds(1));
    workflow.advance(id);
    clock.now.set(
        java.time.OffsetDateTime.parse(buyer(f, id).path("refundDeadlineAt").asText())
            .toInstant()
            .plusSeconds(1));
    workflow.advance(id);
    assertThat(buyer(f, id).path("status").asText()).isEqualTo("PURCHASE_CONFIRMED");
    assertThat(notifications.processNextNotice()).isTrue();
    var row =
        jdbc.queryForMap(
            "SELECT d.body,i.payload::text AS payload,d.first_occurred_at FROM commerce.notification_items i JOIN commerce.notification_deliveries d ON d.id=i.delivery_id WHERE i.source_event_id=?",
            event);
    assertThat(row.get("body").toString()).contains("4321원");
    assertThat(mapper.readTree(row.get("payload").toString()).path("walletVersion").asLong())
        .isEqualTo(2);
    assertThat(((java.sql.Timestamp) row.get("first_occurred_at")).toInstant()).isEqualTo(NOW);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM commerce.notification_items WHERE kind='SALE_COMPLETED' AND business_ref=? AND (jsonb_exists(payload, 'mileageBalanceAfter') OR jsonb_exists(payload, 'walletVersion'))",
                Long.class,
                id))
        .isZero();
    receiveNotice(notice);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM commerce.notification_items WHERE kind='PAYMENT_COMPLETED' AND business_ref=?",
                Long.class,
                payment))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("발송 알림 저장 실패는 주문 이행·감사·멱등 기록까지 롤백하고 같은 키로 재개한다")
  void rollsBackShipmentWhenNotificationCannotBeStored() throws Exception {
    var f = fixture();
    var order = create(f);
    UUID id = uuid(order, "id");
    receive("PaymentConfirmed", confirmed(order, context(id)), true);
    var before = buyer(f, id);
    long audit = number("SELECT count(*) FROM commerce.audit_logs WHERE aggregate_id=?", id);
    String key = UUID.randomUUID().toString();
    var body = version(before).put("shipmentReference", "test-notice-rollback");
    String path = "/api/sellers/me/orders/" + id + "/shipments";
    jdbc.execute(
        "CREATE FUNCTION commerce.reject_shipment_notice() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.kind='SHIPMENT_STARTED' THEN RAISE EXCEPTION 'injected'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_shipment_notice BEFORE INSERT ON commerce.notification_items FOR EACH ROW EXECUTE FUNCTION commerce.reject_shipment_notice()");
    try {
      call("SELLER", f.seller(), path, body, 500, key);
    } finally {
      jdbc.execute("DROP TRIGGER reject_shipment_notice ON commerce.notification_items");
      jdbc.execute("DROP FUNCTION commerce.reject_shipment_notice()");
    }
    assertThat(buyer(f, id)).isEqualTo(before);
    assertThat(number("SELECT count(*) FROM commerce.audit_logs WHERE aggregate_id=?", id))
        .isEqualTo(audit);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM commerce.notification_items WHERE kind='SHIPMENT_STARTED' AND business_ref=?",
                Long.class,
                id))
        .isZero();
    call("SELLER", f.seller(), path, body, 200, key);
    assertThat(buyer(f, id).path("fulfillmentStatus").asText()).isEqualTo("SHIPPED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM commerce.notification_items WHERE kind='SHIPMENT_STARTED' AND business_ref=?",
                Long.class,
                id))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("거절 후 재예약·이전 회차 무시·미결제 취소의 승인 차단 대기를 보존한다")
  void preservesReservationsUntilPaymentCancellationIsConfirmed() throws Exception {
    var f = fixture();
    var order = create(f);
    var id = uuid(order, "id");
    var context = context(id);
    var payment = UUID.randomUUID();
    receive("PaymentDeclined", result(id, payment, uuid(context, "paymentCycleId")), true);
    assertStock(f, 2, 0, 0);
    var declined = buyer(f, id);
    var retry =
        call("BUYER", f.buyer(), "/api/orders/" + id + "/payment-retries", version(declined), 202);
    assertStock(f, 1, 1, 0);
    receive("PaymentDeclined", result(id, payment, uuid(context, "paymentCycleId")), true);
    assertStock(f, 1, 1, 0);
    var cancelled =
        call(
            "BUYER",
            f.buyer(),
            "/api/orders/" + id + "/cancellation",
            version(retry).put("reason", "시험 취소"),
            202);
    assertThat(cancelled.path("status").asText()).isEqualTo("CANCEL_PENDING");
    assertStock(f, 1, 1, 0);
    receive(
        "PaymentCancellationRejected",
        result(id, payment, uuid(context(id), "paymentCycleId")),
        true);
    assertStock(f, 1, 1, 0);
    receive("PaymentCancelled", result(id, payment, uuid(context(id), "paymentCycleId")), true);
    assertStock(f, 2, 0, 0);
    assertThat(buyer(f, id).path("status").asText()).isEqualTo("CANCELLED");
  }

  @Test
  @DisplayName("접수 중단은 새 결제 회차만 막고 키 재시도·기존 결과·미결제 취소를 보존한다")
  void gatesOnlyNewPaymentRetry() throws Exception {
    var f = fixture();
    var created = create(f);
    UUID id = uuid(created, "id");
    var ctx = context(id);
    receive("PaymentDeclined", result(id, UUID.randomUUID(), uuid(ctx, "paymentCycleId")), true);
    var declined = buyer(f, id);
    String path = "/api/orders/" + id + "/payment-retries";
    String retryKey = UUID.randomUUID().toString();
    long outbox = number("select count(*) from commerce.outbox where aggregate_id=?", id);
    long audit = number("select count(*) from commerce.audit_logs where aggregate_id=?", id);
    org.mockito.Mockito.doThrow(
            new com.commercesettle.commerce.application.exception.OrderCapacityExceededException())
        .when(admission)
        .requireAdmission();

    assertThat(
            call("BUYER", f.buyer(), path, version(declined), 503, retryKey).path("code").asText())
        .isEqualTo("COMMON_CAPACITY_EXCEEDED");
    assertThat(buyer(f, id)).isEqualTo(declined);
    assertStock(f, 2, 0, 0);
    assertThat(number("select count(*) from commerce.outbox where aggregate_id=?", id))
        .isEqualTo(outbox);
    assertThat(number("select count(*) from commerce.audit_logs where aggregate_id=?", id))
        .isEqualTo(audit);
    org.mockito.Mockito.doNothing().when(admission).requireAdmission();
    var retry = call("BUYER", f.buyer(), path, version(declined), 202, retryKey);
    org.mockito.Mockito.doThrow(
            new com.commercesettle.commerce.application.exception.OrderCapacityExceededException())
        .when(admission)
        .requireAdmission();
    assertThat(call("BUYER", f.buyer(), path, version(declined), 202, retryKey)).isEqualTo(retry);
    call("BUYER", f.buyer(), path, version(retry), 409, retryKey);
    assertStock(f, 1, 1, 0);
    call(
        "BUYER",
        f.buyer(),
        "/api/orders/" + id + "/cancellation",
        version(retry).put("reason", "시험 취소"),
        202);
  }

  @Test
  @DisplayName("미결제 기한 작업은 취소 요청만 저장하고 중복 실행으로 예약을 해제하지 않는다")
  void expiresByRequestingCancellationOnce() {
    var f = fixture();
    var id = uuid(create(f), "id");
    clock.now.set(NOW.plusSeconds(601));
    assertThat(workflow.due(100, null)).contains(id);
    workflow.advance(id);
    workflow.advance(id);
    assertThat(buyer(f, id).path("status").asText()).isEqualTo("CANCEL_PENDING");
    assertStock(f, 1, 1, 0);
    assertThat(
            number(
                "select count(*) from commerce.outbox where aggregate_id=? and event_type='PaymentCancellationRequested'",
                id))
        .isEqualTo(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {0, 2000})
  @DisplayName("미발송 환불 복구는 선행 접수를 기다리고 재전달에도 재고와 확인 이벤트를 한 번만 저장한다")
  void restoresUnshippedRefundAfterAcceptedPredecessor(int mileage) throws Exception {
    var f = fixture();
    var order =
        call("BUYER", f.buyer(), "/api/orders", orderBody(f).put("mileageAmount", mileage), 201);
    var id = uuid(order, "id");
    var ctx = context(id);
    var payment = UUID.randomUUID();
    receive("PaymentConfirmed", confirmed(order, ctx).put("paymentId", payment.toString()), true);
    var request = refund(f, id, "CANCEL");
    assertThat(request.path("status").asText()).isEqualTo("APPROVED");
    var refundId = UUID.randomUUID();
    var complete =
        mapper
            .createObjectNode()
            .put("orderId", id.toString())
            .put("paymentId", payment.toString())
            .put("refundId", refundId.toString());
    var early = receive("RefundCompleted", complete, false);
    var notice = paymentNotice(order, ctx, payment, "REFUND_COMPLETED", refundId);
    UUID noticeId = receiveNotice(notice);
    assertThat(
            number(
                "SELECT count(*) FROM commerce.notification_items WHERE source_event_id=?",
                noticeId))
        .isZero();
    assertStock(f, 1, 0, 1);
    receive(
        "RefundAccepted", complete.deepCopy().put("requestId", request.path("id").asText()), true);
    clock.now.set(NOW.plusSeconds(2));
    assertThat(results.processEvent(early)).isTrue();
    receive("RefundCompleted", complete, true);
    assertStock(f, 2, 0, 0);
    assertThat(buyer(f, id).path("status").asText()).isEqualTo("REFUNDED");
    assertThat(notifications.processNextNotice()).isTrue();
    String buyerBody =
        jdbc.queryForObject(
            "SELECT d.body FROM commerce.notification_items i JOIN commerce.notification_deliveries d ON d.id=i.delivery_id WHERE i.source_event_id=? AND i.recipient_kind='BUYER'",
            String.class,
            noticeId);
    assertThat(buyerBody).contains("외부 환불 " + (10000 - mileage) + "원", "복원 마일리지 " + mileage + "원");
    if (mileage > 0) assertThat(buyerBody).contains("잔액은 4321원");
    else assertThat(buyerBody).doesNotContain("잔액");
    assertThat(
            jdbc.queryForObject(
                "SELECT d.body FROM commerce.notification_items i JOIN commerce.notification_deliveries d ON d.id=i.delivery_id WHERE i.source_event_id=? AND i.recipient_kind='SELLER'",
                String.class,
                noticeId))
        .doesNotContain("마일리지", "4321원");
    assertThat(
            number(
                "SELECT count(*) FROM commerce.notification_items WHERE source_event_id=?",
                noticeId))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM commerce.notification_items WHERE source_event_id=? AND recipient_kind='SELLER' AND (jsonb_exists(payload, 'mileageBalanceAfter') OR jsonb_exists(payload, 'walletVersion'))",
                Long.class,
                noticeId))
        .isZero();
    assertThat(
            number(
                "select count(*) from commerce.outbox where aggregate_id=? and event_type='RefundOrderRestored'",
                id))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("판매자 승인 전 철회와 반품 입고를 분리하고 금융 실패만으로 주문을 재개하지 않는다")
  void receivesReturnIndependentlyOfFinancialFailure() throws Exception {
    var f = fixture();
    var order = create(f);
    var id = uuid(order, "id");
    var payment = UUID.randomUUID();
    receive(
        "PaymentConfirmed",
        confirmed(order, context(id)).put("paymentId", payment.toString()),
        true);
    ship(f, id);
    var first = refund(f, id, "RETURN");
    call(
        "BUYER",
        f.buyer(),
        "/api/refund-requests/" + first.path("id").asText() + "/withdrawal",
        version(first),
        200);
    assertThat(buyer(f, id).path("status").asText()).isEqualTo("PAID");
    var r = refund(f, id, "RETURN");
    var rid = uuid(r, "id");
    var decision =
        call(
            "SELLER",
            f.seller(),
            "/api/sellers/me/refund-requests/" + rid + "/decision",
            version(r).put("decision", "APPROVE").put("reason", "확인"),
            202);
    var refundId = UUID.randomUUID();
    var payload =
        mapper
            .createObjectNode()
            .put("orderId", id.toString())
            .put("paymentId", payment.toString())
            .put("refundId", refundId.toString());
    receive("RefundAccepted", payload.deepCopy().put("requestId", rid.toString()), true);
    r = call("BUYER", f.buyer(), "/api/refund-requests/" + rid, null, 200);
    matches("OrderRefundRequest", r);
    var received =
        call(
            "SELLER",
            f.seller(),
            "/api/sellers/me/refund-requests/" + rid + "/receipt",
            version(r),
            200);
    assertStock(f, 2, 0, 0);
    receive(
        "RefundFailed",
        payload.deepCopy().put("reasonCode", "DECLINED").put("reservationReleased", true),
        true);
    assertStock(f, 2, 0, 0);
    assertThat(buyer(f, id).path("status").asText()).isEqualTo("REFUND_PENDING");
    r = call("BUYER", f.buyer(), "/api/refund-requests/" + rid, null, 200);
    closure.safe = true;
    internal(
        "/internal/refund-requests/" + rid + "/closure",
        OPERATOR,
        version(r).put("reason", "종결").put("customerConfirmationRef", "confirmation"),
        409);
    assertThat(received.path("receivedAt").isTextual()).isTrue();
  }

  @Test
  @DisplayName("금융 확정 실패의 안전한 종결은 네 근거를 모두 요구하며 외부 조회 중 트랜잭션을 잡지 않는다")
  void closesOnlyVerifiedFailureAndReplaysWithoutNewExternalLookup() throws Exception {
    var f = fixture();
    var order = create(f);
    var id = uuid(order, "id");
    var payment = UUID.randomUUID();
    receive(
        "PaymentConfirmed",
        confirmed(order, context(id)).put("paymentId", payment.toString()),
        true);
    var r = refund(f, id, "CANCEL");
    var rid = uuid(r, "id");
    var refundId = UUID.randomUUID();
    var p =
        mapper
            .createObjectNode()
            .put("orderId", id.toString())
            .put("paymentId", payment.toString())
            .put("refundId", refundId.toString());
    receive("RefundAccepted", p.deepCopy().put("requestId", rid.toString()), true);
    receive(
        "RefundFailed",
        p.deepCopy().put("reasonCode", "DECLINED").put("reservationReleased", true),
        true);
    r = call("BUYER", f.buyer(), "/api/refund-requests/" + rid, null, 200);
    var body =
        version(r)
            .put("reason", "고객 확인 종결")
            .put("customerConfirmationRef", "confirmed-test-customer");
    internal("/internal/refund-requests/" + rid + "/closure", OPERATOR, body, 409);
    closure.safe = true;
    String key = UUID.randomUUID().toString();
    var closed =
        internal("/internal/refund-requests/" + rid + "/closure", OPERATOR, body, 200, key);
    closure.safe = false;
    assertThat(internal("/internal/refund-requests/" + rid + "/closure", OPERATOR, body, 200, key))
        .isEqualTo(closed);
    assertThat(buyer(f, id).path("status").asText()).isEqualTo("PAID");
    assertStock(f, 1, 0, 1);
  }

  @Test
  @DisplayName("쿠폰·이벤트 계약 대역은 금액 사본과 같은 트랜잭션에 참여하고 실패 시 함께 롤백한다")
  void bindsBenefitContractDoubleToLocalTransaction() {
    var f = fixture();
    var body =
        orderBody(f)
            .put("buyerCouponId", UUID.randomUUID().toString())
            .put("eventId", UUID.randomUUID().toString())
            .put("expectedEventVersion", 7)
            .put("expectedTotalAmount", 8000);
    jdbc.execute(
        "ALTER TABLE commerce.outbox ADD CONSTRAINT test_reject_benefits CHECK(event_type <> 'OrderPaymentRequested') NOT VALID");
    try {
      call("BUYER", f.buyer(), "/api/orders", body, 500);
    } finally {
      jdbc.execute("ALTER TABLE commerce.outbox DROP CONSTRAINT test_reject_benefits");
    }
    assertThat(
            number(
                "select count(*) from commerce.test_benefit_calls b join commerce.orders o on o.id=b.order_id where o.buyer_id=?",
                f.buyer()))
        .isZero();
    var o = call("BUYER", f.buyer(), "/api/orders", body, 201);
    assertThat(o.path("grossAmount").asLong()).isEqualTo(9000);
    assertThat(o.path("platformCouponAmount").asLong()).isEqualTo(1000);
    assertThat(
            number(
                "select count(*) from commerce.test_benefit_calls where order_id=?", uuid(o, "id")))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("주문 잠금 대기 상한은 취소 멱등 기록·감사·Outbox를 모두 롤백한다")
  void rollsBackCancellationOnBoundedOrderLockWait() throws Exception {
    var f = fixture();
    var o = create(f);
    var id = uuid(o, "id");
    long outbox = number("select count(*) from commerce.outbox where aggregate_id=?", id);
    try (var connection = jdbc.getDataSource().getConnection()) {
      connection.setAutoCommit(false);
      try (var statement =
          connection.prepareStatement("select id from commerce.orders where id=? for update")) {
        statement.setObject(1, id);
        statement.executeQuery().close();
      }
      call(
          "BUYER",
          f.buyer(),
          "/api/orders/" + id + "/cancellation",
          version(o).put("reason", "취소"),
          503);
      assertThat(number("select count(*) from commerce.outbox where aggregate_id=?", id))
          .isEqualTo(outbox);
      assertThat(buyer(f, id).path("status").asText()).isEqualTo("PAYMENT_PENDING");
      connection.rollback();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(longs = {0, 1})
  @DisplayName("환불 접수와 자동 구매확정이 경합해도 마감 정각·초과 기준을 보존한다")
  void serializesRefundAgainstAutomaticConfirmation(long afterDeadline) throws Exception {
    var f = fixture();
    var o = create(f);
    var id = uuid(o, "id");
    receive("PaymentConfirmed", confirmed(o, context(id)), true);
    var shipped = ship(f, id);
    var due = java.time.OffsetDateTime.parse(shipped.path("deliveryDueAt").asText()).toInstant();
    clock.now.set(due);
    workflow.advance(id);
    var latest = buyer(f, id);
    clock.now.set(due.plusSeconds(168 * 3600 + afterDeadline));
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var confirmation =
          executor.submit(
              () -> {
                start.await();
                workflow.advance(id);
                return true;
              });
      var refund =
          executor.submit(
              () -> {
                start.await();
                return call(
                    "BUYER",
                    f.buyer(),
                    "/api/orders/" + id + "/refund-requests",
                    version(latest).put("kind", "RETURN").put("reason", "마감 시험"),
                    afterDeadline == 0 ? 201 : 409);
              });
      start.countDown();
      confirmation.get(10, java.util.concurrent.TimeUnit.SECONDS);
      refund.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertThat(buyer(f, id).path("status").asText())
        .isEqualTo(afterDeadline == 0 ? "REFUND_PENDING" : "PURCHASE_CONFIRMED");
    assertThat(number("select count(*) from commerce.order_refund_requests where order_id=?", id))
        .isEqualTo(afterDeadline == 0 ? 1 : 0);
  }

  @Test
  @DisplayName("취소 대기 중 결제 확정이 먼저 오면 판매를 반영하고 모순 취소는 검토 대상으로 보존한다")
  void preservesConfirmedSaleAgainstLateCancellation() throws Exception {
    var f = fixture();
    var o = create(f);
    var id = uuid(o, "id");
    var ctx = context(id);
    var payment = UUID.randomUUID();
    call(
        "BUYER",
        f.buyer(),
        "/api/orders/" + id + "/cancellation",
        version(o).put("reason", "취소"),
        202);
    receive("PaymentConfirmed", confirmed(o, ctx).put("paymentId", payment.toString()), true);
    assertStock(f, 1, 0, 1);
    var p = result(id, payment, uuid(ctx, "paymentCycleId"));
    var e =
        new EventEnvelope<JsonNode>(
            UUID.randomUUID(),
            "PaymentCancelled",
            1,
            NOW.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),
            payment,
            1,
            p);
    results.accept(
        e,
        new com.commercesettle.commerce.application.port.PaymentAcceptanceInbox.Source(
            "commerce-settle.payment.v1", 0, 0, mapper.writeValueAsBytes(e)));
    results.processEvent(e.eventId());
    assertThat(
            jdbc.queryForObject(
                "select status from commerce.inbox where event_id=?", String.class, e.eventId()))
        .isEqualTo("REVIEW_REQUIRED");
    assertThat(buyer(f, id).path("status").asText()).isEqualTo("PAID");
    assertStock(f, 1, 0, 1);
  }

  @Test
  @DisplayName("신규 주문 결과의 모든 발신 payload는 같은 v1 OpenAPI 계약을 따른다")
  void preservesOutgoingEventSchemas() throws Exception {
    var f = fixture();
    var o = create(f);
    var id = uuid(o, "id");
    var ctx = context(id);
    var payment = UUID.randomUUID();
    receive("PaymentConfirmed", confirmed(o, ctx).put("paymentId", payment.toString()), true);
    var request = refund(f, id, "CANCEL");
    var refundId = UUID.randomUUID();
    var p =
        mapper
            .createObjectNode()
            .put("orderId", id.toString())
            .put("paymentId", payment.toString())
            .put("refundId", refundId.toString());
    receive("RefundAccepted", p.deepCopy().put("requestId", request.path("id").asText()), true);
    receive("RefundCompleted", p, true);
    for (var body :
        jdbc.queryForList(
            "select payload::text from commerce.outbox where aggregate_id=?", String.class, id)) {
      var event = mapper.readTree(body);
      matches(event.required("eventType").asText() + "Payload", event.required("payload"));
    }
  }

  @Test
  @DisplayName("런타임 Swagger도 견적의 필수 nullable 입력과 응답 필수 필드를 보존한다")
  void generatedSchemasPreserveRequiredNullableContracts() throws Exception {
    var f = fixture();
    var input = orderBody(f);
    input.remove(
        List.of(
            "addressId",
            "expectedProductVersion",
            "expectedEventVersion",
            "expectedTotalAmount",
            "methodAccountRef"));
    var api = http.getForObject("/v3/api-docs", JsonNode.class);
    var root = mapper.createObjectNode();
    root.set("components", api.required("components"));
    root.set(
        "$ref",
        api.path("paths")
            .path("/api/order-quotes")
            .path("post")
            .path("requestBody")
            .path("content")
            .path("application/json")
            .path("schema")
            .required("$ref"));
    var schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(
                SchemaLocation.of("urn:test:generated-quote"), root.toString(), InputFormat.JSON);
    assertThat(schema.validate(input.toString(), InputFormat.JSON)).isEmpty();
    input.put("mileageAmount", 10000).putNull("externalMethod");
    assertThat(schema.validate(input.toString(), InputFormat.JSON)).isEmpty();
    assertThat(api.path("paths").has("/internal/orders/{orderId}/payment-context")).isFalse();
    var canonical = mapper.readTree(getClass().getResourceAsStream("/contracts/openapi.json"));
    for (var name : List.of("OrderQuote", "SellerOrder", "OrderRefundRequest")) {
      assertThat(
              java.util.stream.StreamSupport.stream(
                      api.path("components")
                          .path("schemas")
                          .path(name)
                          .path("required")
                          .spliterator(),
                      false)
                  .map(JsonNode::asText)
                  .toList())
          .containsExactlyInAnyOrderElementsOf(
              java.util.stream.StreamSupport.stream(
                      canonical
                          .path("components")
                          .path("schemas")
                          .path(name)
                          .path("required")
                          .spliterator(),
                      false)
                  .map(JsonNode::asText)
                  .toList());
    }
  }

  @Test
  @DisplayName("취소 거절·결제 거절이 도착해도 취소 의도와 예약을 유지하고 승인 차단 확정에서 한 번만 해제한다")
  void keepsCancellationPendingUntilApprovalIsBlocked() throws Exception {
    var f = fixture();
    var o = create(f);
    var id = uuid(o, "id");
    var ctx = context(id);
    var payment = UUID.randomUUID();
    var pending =
        call(
            "BUYER",
            f.buyer(),
            "/api/orders/" + id + "/cancellation",
            version(o).put("reason", "취소"),
            202);
    var payload = result(id, payment, uuid(ctx, "paymentCycleId"));
    long auditBefore = number("select count(*) from commerce.audit_logs where aggregate_id=?", id);
    receive("PaymentCancellationRejected", payload, true);
    receive("PaymentDeclined", payload, true);
    receive("PaymentDeclined", payload, true);
    assertThat(buyer(f, id)).isEqualTo(pending);
    assertStock(f, 1, 1, 0);
    assertThat(number("select count(*) from commerce.audit_logs where aggregate_id=?", id))
        .isEqualTo(auditBefore);
    call("BUYER", f.buyer(), "/api/orders/" + id + "/payment-retries", version(pending), 409);
    receive("PaymentCancelled", payload, true);
    var cancelled = buyer(f, id);
    receive("PaymentCancelled", payload, true);
    assertThat(buyer(f, id)).isEqualTo(cancelled);
    assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
    assertStock(f, 2, 0, 0);
    assertThat(number("select count(*) from commerce.audit_logs where aggregate_id=?", id))
        .isEqualTo(auditBefore + 1);
  }

  private Fixture fixture() {
    UUID buyer = UUID.randomUUID(), seller = UUID.randomUUID();
    jdbc.update(
        "insert into commerce.buyer_accounts(id,email_normalized,password_hash,display_name,status,created_at,updated_at,version) values (?,?,'hash','buyer','ACTIVE',now(),now(),0)",
        buyer,
        buyer + "@example.com");
    jdbc.update(
        "insert into commerce.seller_accounts(id,email_normalized,password_hash,display_name,status,payout_account_ref,created_at,updated_at,version) values (?,?,'hash','seller','ACTIVE','test-account',now(),now(),0)",
        seller,
        seller + "@example.com");
    UUID address =
        addresses
            .register(
                buyer,
                UUID.randomUUID().toString(),
                new ShippingAddress("시험 수령인", "000-0000", "00001", "시험로 1", ""),
                true)
            .id();
    var product =
        products.register(
            seller, UUID.randomUUID().toString(), "흐름 시험 상품", 10000, ProductStatus.ON_SALE);
    products.adjustAvailable(seller, product.getId(), UUID.randomUUID().toString(), 2, 0, "시험 입고");
    return new Fixture(buyer, seller, address, product.getId());
  }

  private ObjectNode orderBody(Fixture f) {
    return mapper
        .createObjectNode()
        .put("productId", f.product().toString())
        .put("quantity", 1)
        .putNull("eventId")
        .putNull("buyerCouponId")
        .put("mileageAmount", 0)
        .put("addressId", f.address().toString())
        .put("expectedProductVersion", 0)
        .putNull("expectedEventVersion")
        .put("expectedTotalAmount", 10000)
        .put("externalMethod", "CREDIT_CARD")
        .put("methodAccountRef", "test-card");
  }

  private JsonNode create(Fixture f) {
    return call("BUYER", f.buyer(), "/api/orders", orderBody(f), 201);
  }

  private JsonNode context(UUID id) {
    return internal("/internal/orders/" + id + "/payment-context", PAYMENT, null, 200);
  }

  private JsonNode buyer(Fixture f, UUID id) {
    return call("BUYER", f.buyer(), "/api/orders/" + id, null, 200);
  }

  private JsonNode seller(Fixture f, UUID id) {
    return call("SELLER", f.seller(), "/api/sellers/me/orders/" + id, null, 200);
  }

  private JsonNode refund(Fixture f, UUID id, String kind) {
    return call(
        "BUYER",
        f.buyer(),
        "/api/orders/" + id + "/refund-requests",
        version(buyer(f, id)).put("kind", kind).put("reason", "시험 취소/반품"),
        201);
  }

  private JsonNode ship(Fixture f, UUID id) {
    return call(
        "SELLER",
        f.seller(),
        "/api/sellers/me/orders/" + id + "/shipments",
        version(buyer(f, id)).put("shipmentReference", "test-parcel"),
        200);
  }

  private ObjectNode version(JsonNode n) {
    return mapper.createObjectNode().put("expectedVersion", n.path("version").asLong());
  }

  private JsonNode call(String kind, UUID actor, String path, ObjectNode body, int expected) {
    return call(kind, actor, path, body, expected, UUID.randomUUID().toString());
  }

  private JsonNode call(
      String kind, UUID actor, String path, ObjectNode body, int expected, String key) {
    var h = new HttpHeaders();
    h.setBearerAuth(GATEWAY);
    h.set("X-Authenticated-Actor-Kind", kind);
    h.set("X-Authenticated-Actor-Id", actor.toString());
    return exchange(path, body, expected, key, h);
  }

  private JsonNode internal(String path, String credential, ObjectNode body, int expected) {
    return internal(path, credential, body, expected, UUID.randomUUID().toString());
  }

  private JsonNode internal(
      String path, String credential, ObjectNode body, int expected, String key) {
    var h = new HttpHeaders();
    h.setBearerAuth(credential);
    return exchange(path, body, expected, key, h);
  }

  private JsonNode exchange(String path, ObjectNode body, int expected, String key, HttpHeaders h) {
    h.setContentType(MediaType.APPLICATION_JSON);
    h.set("Idempotency-Key", key);
    var r =
        http.exchange(
            path,
            body == null ? HttpMethod.GET : HttpMethod.POST,
            new HttpEntity<>(body, h),
            JsonNode.class);
    assertThat(r.getStatusCode().value()).as("%s: %s", path, r.getBody()).isEqualTo(expected);
    return r.getBody();
  }

  private ObjectNode result(UUID order, UUID payment, UUID cycle) {
    return mapper
        .createObjectNode()
        .put("orderId", order.toString())
        .put("paymentId", payment.toString())
        .put("paymentCycleId", cycle.toString())
        .put("reasonCode", "TEST_RESULT");
  }

  private ObjectNode confirmed(JsonNode o, JsonNode ctx) {
    var p = mapper.createObjectNode();
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
            "settlementBaseAmount")) p.set(k, o.required(k));
    return p.put("orderId", o.path("id").asText())
        .put("paymentId", UUID.randomUUID().toString())
        .put("paymentCycleId", ctx.path("paymentCycleId").asText())
        .put("orderVersion", ctx.path("orderVersion").asLong())
        .put("policyVersion", ctx.path("policyVersion").asText())
        .put("feeAmount", o.path("settlementBaseAmount").asLong() * 15 / 100)
        .put("sellerNetAmount", o.path("settlementBaseAmount").asLong() * 85 / 100)
        .put("confirmedAt", NOW.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime().toString());
  }

  private UUID receive(String type, ObjectNode payload, boolean applied) throws Exception {
    var payment = uuid(payload, "paymentId");
    var e =
        new EventEnvelope<JsonNode>(
            UUID.randomUUID(),
            type,
            1,
            clock.instant().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),
            type.startsWith("Refund") ? uuid(payload, "refundId") : payment,
            0,
            payload);
    var listener = new PaymentAcceptedListener(mapper, accepted, results);
    var ack = new java.util.concurrent.atomic.AtomicBoolean();
    listener.receive(
        new ConsumerRecord<>(
            "commerce-settle.payment.v1", 0, 0, payment.toString(), mapper.writeValueAsBytes(e)),
        () -> ack.set(true));
    assertThat(ack).isTrue();
    assertThat(results.processEvent(e.eventId())).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select status from commerce.inbox where event_id=?", String.class, e.eventId()))
        .isEqualTo(applied ? "APPLIED" : "WAITING");
    return e.eventId();
  }

  private ObjectNode paymentNotice(
      JsonNode order, JsonNode ctx, UUID payment, String kind, UUID refund) {
    var p =
        mapper
            .createObjectNode()
            .put("kind", kind)
            .put("businessRef", (refund == null ? payment : refund).toString())
            .put("paymentId", payment.toString())
            .put("paymentCycleId", ctx.path("paymentCycleId").asText())
            .put("orderId", order.path("id").asText())
            .put("buyerId", order.path("buyerId").asText())
            .put("sellerId", order.path("sellerId").asText())
            .put("totalAmount", order.path("totalAmount").asLong())
            .put("externalAmount", order.path("externalAmount").asLong())
            .put("mileageAmount", order.path("mileageAmount").asLong())
            .put("processedAt", NOW.atOffset(java.time.ZoneOffset.UTC).toString());
    if (refund == null) p.putNull("refundId");
    else p.put("refundId", refund.toString());
    if (order.path("mileageAmount").asLong() > 0)
      p.put("mileageBalanceAfter", 4321).put("walletVersion", 2);
    else p.putNull("mileageBalanceAfter").putNull("walletVersion");
    return p;
  }

  private UUID receiveNotice(ObjectNode payload) throws Exception {
    var event =
        new EventEnvelope<JsonNode>(
            UUID.randomUUID(),
            "PaymentNoticeRequested",
            1,
            clock.instant().atOffset(java.time.ZoneOffset.UTC),
            UUID.randomUUID(),
            0,
            payload);
    var ack = new java.util.concurrent.atomic.AtomicBoolean();
    new com.commercesettle.commerce.infrastructure.messaging.NotificationListener(
            mapper, notifications)
        .receive(
            new ConsumerRecord<>(
                "commerce-settle.payment.v1",
                0,
                System.nanoTime(),
                payload.path("paymentId").asText(),
                mapper.writeValueAsBytes(event)),
            () -> ack.set(true));
    assertThat(ack).isTrue();
    return event.eventId();
  }

  private void assertStock(Fixture f, long available, long reserved, long sold) {
    var row =
        jdbc.queryForMap(
            "select available_qty,reserved_qty,sold_qty from commerce.product_inventory where product_id=?",
            f.product());
    assertThat(((Number) row.get("available_qty")).longValue()).isEqualTo(available);
    assertThat(((Number) row.get("reserved_qty")).longValue()).isEqualTo(reserved);
    assertThat(((Number) row.get("sold_qty")).longValue()).isEqualTo(sold);
  }

  private long number(String sql, UUID id) {
    return jdbc.queryForObject(sql, Long.class, id);
  }

  private static UUID uuid(JsonNode n, String field) {
    return UUID.fromString(n.required(field).asText());
  }

  private void matches(String name, JsonNode n) throws Exception {
    var canonical = mapper.readTree(getClass().getResourceAsStream("/contracts/openapi.json"));
    var root = mapper.createObjectNode();
    root.set("components", canonical.path("components"));
    root.put("$ref", "#/components/schemas/" + name);
    var schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(
                SchemaLocation.of("urn:test:workflow:" + name), root.toString(), InputFormat.JSON);
    assertThat(schema.validate(n.toString(), InputFormat.JSON)).isEmpty();
  }

  record Fixture(UUID buyer, UUID seller, UUID address, UUID product) {}

  @TestConfiguration
  static class Beans {
    @Bean
    @Primary
    TestClock testClock() {
      return new TestClock();
    }

    @Bean
    @Primary
    ClosureDouble closureDouble(javax.sql.DataSource source) {
      return new ClosureDouble(source);
    }

    @Bean
    @Primary
    OrderBenefits benefits(JdbcTemplate jdbc) {
      return new BenefitsDouble(jdbc);
    }
  }

  static class TestClock extends Clock {
    final AtomicReference<Instant> now = new AtomicReference<>(NOW);

    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now.get();
    }
  }

  static class ClosureDouble implements RefundClosureEvidence {
    private final javax.sql.DataSource source;

    ClosureDouble(javax.sql.DataSource source) {
      this.source = source;
    }

    volatile boolean safe;

    public Evidence verify(UUID id, String customer) {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse();
      assertThat(
              ((com.zaxxer.hikari.HikariDataSource) source)
                  .getHikariPoolMXBean()
                  .getActiveConnections())
          .isZero();
      return new Evidence(id, safe, safe, safe, safe, "verified-test-evidence");
    }
  }

  static class BenefitsDouble implements OrderBenefits {
    private final JdbcTemplate jdbc;

    BenefitsDouble(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    public Offer quote(
        UUID buyer,
        UUID product,
        UUID seller,
        long price,
        int quantity,
        UUID coupon,
        UUID event,
        Order.ExternalMethod method,
        Instant now) {
      return new Offer(
          event == null ? price : 9000, event == null ? null : 7L, 0, coupon == null ? 0 : 1000);
    }

    private void record(Order o, String op) {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      if (o.getBuyerCouponId() != null || o.getEventId() != null)
        jdbc.update("insert into commerce.test_benefit_calls values (?,?)", o.getId(), op);
    }

    public void reserve(Order o, Long version, Instant now) {
      record(o, "RESERVE");
    }

    public void retry(Order o, Instant now) {
      record(o, "RETRY");
    }

    public void consume(Order o, Instant now) {
      record(o, "CONSUME");
    }

    public void release(Order o, Instant now) {
      record(o, "RELEASE");
    }

    public void restore(Order o, UUID refund, Instant now) {
      record(o, "RESTORE");
    }

    public void receiveReturn(Order o, UUID request, Instant now) {
      record(o, "RETURN");
    }
  }
}
