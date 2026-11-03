package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.application.event.PaymentAcceptedPayload;
import com.commercesettle.commerce.application.port.PaymentAcceptanceInbox;
import com.commercesettle.commerce.application.port.PaymentAcceptanceInbox.Source;
import com.commercesettle.commerce.domain.model.ProductStatus;
import com.commercesettle.commerce.domain.model.ShippingAddress;
import com.commercesettle.commerce.domain.repository.OrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.tracing.enabled=false",
      "app.auth.session-epoch=00000000-0000-4000-8000-000000000000",
      "app.auth.rate-limit.identifier-hmac-key=payment-accepted-test-hmac-key-32bytes",
      "app.auth.password-reset.delivery-key=payment-accepted-test-delivery-key-32bytes",
      "app.address.encryption-key=payment-accepted-test-address-key-32bytes",
      "app.kafka.payment-accepted.enabled=true",
      "app.internal-auth.senders.gateway.current="
          + PaymentAcceptedIntegrationTest.GATEWAY_CREDENTIAL,
      "spring.kafka.consumer.auto-offset-reset=earliest"
    })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(90)
@Import(PaymentAcceptedIntegrationTest.TestBeans.class)
class PaymentAcceptedIntegrationTest {
  @MockitoBean OrderAdmissionService admission;
  static final String GATEWAY_CREDENTIAL = "test-gateway-credential-0123456789abcdef0123";
  private static final String TOPIC = "commerce-settle.payment.v1";
  private static final Instant NOW = Instant.parse("2026-11-02T01:00:00.123456Z");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer("apache/kafka:4.2.2").withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m");

  @DynamicPropertySource
  static void kafka(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }

  @Autowired PaymentAcceptedService service;
  @Autowired PaymentAcceptanceInbox inbox;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired TestRestTemplate http;
  @Autowired BuyerAddressService addresses;
  @Autowired ProductCatalogService products;
  @Autowired PlatformTransactionManager manager;
  @Autowired KafkaTemplate<String, byte[]> kafka;
  @Autowired KafkaListenerEndpointRegistry listeners;
  @Autowired TestClock clock;

  @BeforeEach
  void resetClock() {
    clock.time.set(NOW);
    jdbc.update("DELETE FROM commerce.inbox_conflicts");
    jdbc.update("DELETE FROM commerce.inbox");
  }

  @Test
  @DisplayName("결제 ID와 Inbox 완료를 원자 반영하고 재전달·재인계 뒤 주문·금액·재고는 유지한다")
  void linksOnceAndPreservesOrderSnapshots() throws Exception {
    var f = order();
    var event = event(f, UUID.randomUUID(), 0, f.cycle());
    accept(event, 0);
    byte[] original = mapper.writeValueAsBytes(event);
    clock.time.set(NOW.plusSeconds(1));

    assertThat(service.processEvent(event.eventId())).isTrue();

    var linked = get(f);
    assertThat(linked.path("paymentId").asText()).isEqualTo(event.payload().paymentId().toString());
    assertThat(linked.path("status").asText()).isEqualTo("PAYMENT_PENDING");
    assertThat(linked.path("version").asLong()).isEqualTo(1);
    assertThat(linked.path("paymentConfirmedAt").isNull()).isTrue();
    for (String field :
        List.of(
            "totalAmount",
            "mileageAmount",
            "externalAmount",
            "createdAt",
            "deliveryBusinessDays",
            "deliveryCalendarVersion")) {
      assertThat(linked.path(field)).isEqualTo(f.original().path(field));
    }
    var persisted =
        jdbc.queryForMap("select version,updated_at from commerce.orders where id=?", f.id());
    accept(event, 9);
    assertThat(service.processEvent(event.eventId())).isFalse();
    // 같은 업무를 다른 eventId로 재전달해도 @Version 증가 뒤의 기존 연결은 무변경이다.
    var repeated = event(f, event.payload().paymentId(), 0, f.cycle());
    accept(repeated, 1);
    clock.time.set(NOW.plusSeconds(30));
    assertThat(service.processEvent(repeated.eventId())).isTrue();
    assertThat(
            jdbc.queryForMap("select version,updated_at from commerce.orders where id=?", f.id()))
        .isEqualTo(persisted);
    assertThat(
            jdbc.queryForMap(
                "select status,source_offset,raw_body,result_ref from commerce.inbox where event_id=?",
                event.eventId()))
        .containsEntry("status", "APPLIED")
        .containsEntry("source_offset", 0L)
        .containsEntry("raw_body", original)
        .containsEntry("result_ref", f.id());
    UUID inboxId =
        jdbc.queryForObject(
            "select id from commerce.inbox where event_id=?", UUID.class, event.eventId());
    var applied =
        jdbc.queryForMap(
            "select status,attempts,version,last_error_code from commerce.inbox where id=?",
            inboxId);
    new TransactionTemplate(manager)
        .executeWithoutResult(status -> inbox.retry(inboxId, "STALE_RETRY", clock.instant()));
    assertThat(
            jdbc.queryForMap(
                "select status,attempts,version,last_error_code from commerce.inbox where id=?",
                inboxId))
        .isEqualTo(applied);
    assertInventory(f);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from commerce.outbox where aggregate_id=?", Long.class, f.id()))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("같은 이벤트 ID의 다른 원문은 원래 처리·출처를 유지하고 충돌로 보존한다")
  void preservesConflictingBodyWithoutChangingOriginal() throws Exception {
    var f = order();
    var event = event(f, UUID.randomUUID(), 0, f.cycle());
    accept(event, 0);
    UUID otherPayment = UUID.randomUUID();
    var conflict =
        new EventEnvelope<>(
            event.eventId(),
            event.eventType(),
            1,
            event.occurredAt(),
            otherPayment,
            0,
            new PaymentAcceptedPayload(otherPayment, f.id(), 0, f.cycle()));
    accept(conflict, 1);
    accept(conflict, 1);

    assertThat(
            jdbc.queryForObject(
                "select count(*) from commerce.inbox_conflicts where event_id=?",
                Long.class,
                event.eventId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select raw_body from commerce.inbox where event_id=?",
                byte[].class,
                event.eventId()))
        .containsExactly(mapper.writeValueAsBytes(event));
    service.processEvent(event.eventId());
    assertThat(get(f).path("paymentId").asText()).isEqualTo(event.payload().paymentId().toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"cycle", "version", "payment", "state"})
  @DisplayName("이전 회차·버전·기존 ID·상태 충돌은 주문을 덮어쓰지 않고 사유를 보존한다")
  void rejectsIncompatibleLink(String kind) throws Exception {
    var f = order();
    if ("payment".equals(kind)) {
      var first = event(f, UUID.randomUUID(), 0, f.cycle());
      accept(first, 0);
      service.processEvent(first.eventId());
    }
    if ("state".equals(kind))
      jdbc.update("update commerce.orders set status='DECLINED' where id=?", f.id());
    var before =
        jdbc.queryForMap(
            "select payment_id,status,version,updated_at from commerce.orders where id=?", f.id());
    var event =
        event(
            f,
            UUID.randomUUID(),
            "version".equals(kind) ? 99 : 0,
            "cycle".equals(kind) ? UUID.randomUUID() : f.cycle());
    accept(event, 2);

    service.processEvent(event.eventId());

    assertThat(
            jdbc.queryForMap(
                "select payment_id,status,version,updated_at from commerce.orders where id=?",
                f.id()))
        .isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "select status from commerce.inbox where event_id=?",
                String.class,
                event.eventId()))
        .isEqualTo("REVIEW_REQUIRED");
    assertThat(
            jdbc.queryForObject(
                "select last_error_code from commerce.inbox where event_id=?",
                String.class,
                event.eventId()))
        .startsWith("ORDER_");
    assertInventory(f);
  }

  @Test
  @DisplayName("없는 주문은 1~60초 지수 대기·10회 소진으로 보존하고 다른 도래 건을 막지 않는다")
  void boundsMissingOrderRetriesAndAllowsOtherWork() throws Exception {
    var missing = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null);
    var event = event(missing, UUID.randomUUID(), 0, missing.cycle());
    accept(event, 0);
    long[] delays = {1, 2, 4, 8, 16, 32, 60, 60, 60};
    for (int attempt = 1; attempt <= 10; attempt++) {
      assertThat(service.processEvent(event.eventId())).isTrue();
      var row =
          jdbc.queryForMap(
              "select status,attempts,last_error_code,next_run_at from commerce.inbox where event_id=?",
              event.eventId());
      assertThat(row)
          .containsEntry("attempts", attempt)
          .containsEntry("last_error_code", "ORDER_NOT_FOUND");
      if (attempt == 10) {
        assertThat(row)
            .containsEntry("status", "REVIEW_REQUIRED")
            .containsEntry("next_run_at", null);
      } else {
        Instant next = clock.instant().plusSeconds(delays[attempt - 1]);
        assertThat(((java.sql.Timestamp) row.get("next_run_at")).toInstant()).isEqualTo(next);
        if (attempt == 1) {
          var other = order();
          var ready = event(other, UUID.randomUUID(), 0, other.cycle());
          accept(ready, 0);
          assertThat(service.processNext()).isTrue();
          assertThat(get(other).path("paymentId").asText())
              .isEqualTo(ready.payload().paymentId().toString());
        }
        clock.time.set(next);
      }
    }
    assertThat(service.processEvent(event.eventId())).isFalse();
  }

  @Test
  @DisplayName("Inbox 완료 저장 실패는 주문 ID 연결을 롤백하고 별도 재시도 뒤 같은 원문으로 재개한다")
  void rollsBackLinkWhenInboxCompletionFails() throws Exception {
    var f = order();
    var event = event(f, UUID.randomUUID(), 0, f.cycle());
    accept(event, 0);
    jdbc.execute(
        "CREATE FUNCTION commerce.reject_inbox_apply() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='APPLIED' THEN RAISE EXCEPTION 'injected'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_inbox_apply BEFORE UPDATE ON commerce.inbox FOR EACH ROW EXECUTE FUNCTION commerce.reject_inbox_apply()");
    try {
      service.processEvent(event.eventId());
      assertThat(get(f).path("paymentId").isNull()).isTrue();
      assertThat(get(f).path("version").asLong()).isZero();
      assertThat(
              jdbc.queryForMap(
                  "select status,attempts,last_error_code from commerce.inbox where event_id=?",
                  event.eventId()))
          .containsEntry("status", "WAITING")
          .containsEntry("attempts", 1)
          .containsEntry("last_error_code", "PAYMENT_LINK_APPLY_FAILED");
    } finally {
      jdbc.execute("DROP TRIGGER reject_inbox_apply ON commerce.inbox");
      jdbc.execute("DROP FUNCTION commerce.reject_inbox_apply()");
    }
    clock.time.set(NOW.plusSeconds(1));
    assertThat(service.processEvent(event.eventId())).isTrue();
    assertThat(get(f).path("paymentId").asText()).isEqualTo(event.payload().paymentId().toString());
    assertInventory(f);
  }

  @Test
  @DisplayName("다른 트랜잭션의 주문 잠금은 유한 대기 뒤 연결을 롤백하고 재개한다")
  void boundsOrderLockWait() throws Exception {
    var f = order();
    var event = event(f, UUID.randomUUID(), 0, f.cycle());
    accept(event, 0);
    try (var holder = jdbc.getDataSource().getConnection()) {
      holder.setAutoCommit(false);
      try (var statement =
          holder.prepareStatement("select id from commerce.orders where id=? for update")) {
        statement.setObject(1, f.id());
        statement.executeQuery().close();
      }
      Instant started = Instant.now();
      service.processEvent(event.eventId());
      assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(6));
      assertThat(get(f).path("paymentId").isNull()).isTrue();
      holder.rollback();
    }
    clock.time.set(NOW.plusSeconds(1));
    service.processEvent(event.eventId());
    assertThat(get(f).path("paymentId").asText()).isEqualTo(event.payload().paymentId().toString());
  }

  @Test
  @DisplayName("잠긴 Inbox는 건너뛰어 다음 도래 건을 처리한다")
  void skipsLockedInbox() throws Exception {
    var first = order();
    var a = event(first, UUID.randomUUID(), 0, first.cycle());
    accept(a, 0);
    var second = order();
    var b = event(second, UUID.randomUUID(), 0, second.cycle());
    accept(b, 1);
    try (var holder = jdbc.getDataSource().getConnection()) {
      holder.setAutoCommit(false);
      try (var statement =
          holder.prepareStatement("select id from commerce.inbox where event_id=? for update")) {
        statement.setObject(1, a.eventId());
        statement.executeQuery().close();
      }
      assertThat(service.processEvent(a.eventId())).isFalse();
      assertThat(service.processEvent(b.eventId())).isTrue();
      holder.rollback();
    }
    assertThat(get(first).path("paymentId").isNull()).isTrue();
    service.processEvent(a.eventId());
  }

  @Test
  @DisplayName("같은 주문의 동시 반영은 한 번 연결하고 다른 paymentId의 중복 주문 연결도 거절한다")
  void serializesConcurrentLinksAndPreventsPaymentReuse() throws Exception {
    var f = order();
    UUID payment = UUID.randomUUID();
    var a = event(f, payment, 0, f.cycle());
    var b = event(f, payment, 0, f.cycle());
    accept(a, 0);
    accept(b, 1);
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures =
          List.of(a, b).stream()
              .map(
                  e ->
                      pool.submit(
                          () -> {
                            start.await();
                            return service.processEvent(e.eventId());
                          }))
              .toList();
      start.countDown();
      for (var future : futures) assertThat(future.get(10, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(get(f).path("version").asLong()).isEqualTo(1);
    var other = order();
    var reused = event(other, payment, 0, other.cycle());
    accept(reused, 2);
    service.processEvent(reused.eventId());
    assertThat(get(other).path("paymentId").isNull()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select last_error_code from commerce.inbox where event_id=?",
                String.class,
                reused.eventId()))
        .isEqualTo("ORDER_PAYMENT_ID_CONFLICT");
  }

  @Test
  @DisplayName("실제 Kafka의 인계 실패는 offset을 보존하고 Consumer 재시작 뒤 같은 원문을 재개한다")
  void replaysKafkaAfterHandoffFailureAndConsumerRestart() throws Exception {
    var f = order();
    var event = event(f, UUID.randomUUID(), 0, f.cycle());
    var container = listeners.getListenerContainer("paymentAccepted");
    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                container.getAssignedPartitions() != null
                    && container.getAssignedPartitions().size() == 3);
    jdbc.execute(
        "CREATE FUNCTION commerce.reject_inbox_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_inbox_insert BEFORE INSERT ON commerce.inbox FOR EACH ROW EXECUTE FUNCTION commerce.reject_inbox_insert()");
    var sent =
        kafka
            .send(TOPIC, event.payload().paymentId().toString(), mapper.writeValueAsBytes(event))
            .get(10, TimeUnit.SECONDS)
            .getRecordMetadata();
    try (var admin =
        Admin.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
      await().atMost(Duration.ofSeconds(20)).until(() -> !container.isRunning());
      OffsetAndMetadata offset =
          admin
              .listConsumerGroupOffsets("commerce.v1")
              .partitionsToOffsetAndMetadata()
              .get(10, TimeUnit.SECONDS)
              .get(new TopicPartition(TOPIC, sent.partition()));
      assertThat(offset == null || offset.offset() <= sent.offset()).isTrue();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from commerce.inbox where event_id=?",
                  Long.class,
                  event.eventId()))
          .isZero();
    } finally {
      jdbc.execute("DROP TRIGGER reject_inbox_insert ON commerce.inbox");
      jdbc.execute("DROP FUNCTION commerce.reject_inbox_insert()");
      container.start();
    }
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForObject(
                            "select count(*) from commerce.inbox where event_id=?",
                            Long.class,
                            event.eventId()))
                    .isEqualTo(1));
    assertThat(
            jdbc.queryForMap(
                "select source_partition,source_offset,raw_body from commerce.inbox where event_id=?",
                event.eventId()))
        .containsEntry("source_partition", sent.partition())
        .containsEntry("source_offset", sent.offset())
        .containsEntry("raw_body", mapper.writeValueAsBytes(event));
    try (var admin =
        Admin.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () -> {
                var offset =
                    admin
                        .listConsumerGroupOffsets("commerce.v1")
                        .partitionsToOffsetAndMetadata()
                        .get(10, TimeUnit.SECONDS)
                        .get(new TopicPartition(TOPIC, sent.partition()));
                assertThat(offset).isNotNull();
                assertThat(offset.offset()).isGreaterThan(sent.offset());
              });
    }
    // 재기동한 처리기 객체도 DB에 커밋된 대기 행에서 이어간다.
    var restarted = new PaymentAcceptedService(inbox, orders, clock, manager);
    assertThat(restarted.processEvent(event.eventId())).isTrue();
    assertThat(get(f).path("paymentId").asText()).isEqualTo(event.payload().paymentId().toString());
    assertInventory(f);
  }

  @Autowired OrderResultService results;

  @Test
  @DisplayName("같은 Kafka 소비 경계가 결제 확정 결과를 영속 인계하고 주문·재고에 반영한다")
  void appliesConfirmedResultFromRealKafka() throws Exception {
    var f = order();
    var payment = UUID.randomUUID();
    var p = mapper.createObjectNode();
    for (var field :
        List.of(
            "buyerId",
            "sellerId",
            "productId",
            "grossAmount",
            "couponAmount",
            "totalAmount",
            "mileageAmount",
            "externalAmount",
            "sellerCouponAmount",
            "platformCouponAmount",
            "settlementBaseAmount")) p.set(field, f.original().required(field));
    p.put("orderId", f.id().toString())
        .put("paymentId", payment.toString())
        .put("paymentCycleId", f.cycle().toString())
        .put("orderVersion", 0)
        .put("policyVersion", "order-regular-v1")
        .put("confirmedAt", NOW.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime().toString())
        .put("feeAmount", 1500)
        .put("sellerNetAmount", 8500);
    var event =
        new EventEnvelope<JsonNode>(
            UUID.randomUUID(),
            "PaymentConfirmed",
            1,
            NOW.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),
            payment,
            1,
            p);
    kafka
        .send(TOPIC, payment.toString(), mapper.writeValueAsBytes(event))
        .get(10, TimeUnit.SECONDS);
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForObject(
                            "select count(*) from commerce.inbox where event_id=?",
                            Long.class,
                            event.eventId()))
                    .isEqualTo(1));
    assertThat(results.processEvent(event.eventId())).isTrue();
    assertThat(get(f).path("status").asText()).isEqualTo("PAID");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM commerce.notification_items WHERE source_event_id=? AND kind='SALE_COMPLETED'",
                Long.class,
                event.eventId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select status from commerce.inventory_reservations where order_id=?",
                String.class,
                f.id()))
        .isEqualTo("CONSUMED");
  }

  @Autowired OrderRepository orders;

  private Fixture order() {
    UUID buyer = UUID.randomUUID();
    jdbc.update(
        "insert into commerce.buyer_accounts(id,email_normalized,password_hash,display_name,status,created_at,updated_at,version) values (?,?,'hash','구매자','ACTIVE',now(),now(),0)",
        buyer,
        buyer + "@example.com");
    UUID address =
        addresses
            .register(
                buyer,
                UUID.randomUUID().toString(),
                new ShippingAddress("수령인", "000-0000-0000", "00001", "시험로 1", ""),
                true)
            .id();
    UUID seller = UUID.randomUUID();
    jdbc.update(
        "insert into commerce.seller_accounts(id,email_normalized,password_hash,display_name,status,payout_account_ref,created_at,updated_at,version) values (?,?,'hash','판매자','ACTIVE','test-account',now(),now(),0)",
        seller,
        seller + "@example.com");
    var product =
        products.register(
            seller, UUID.randomUUID().toString(), "접수 시험 상품", 10_000, ProductStatus.ON_SALE);
    products.adjustAvailable(seller, product.getId(), UUID.randomUUID().toString(), 2, 0, "시험 입고");
    var body =
        mapper
            .createObjectNode()
            .put("productId", product.getId().toString())
            .put("quantity", 1)
            .putNull("eventId")
            .putNull("buyerCouponId")
            .put("mileageAmount", 0)
            .put("addressId", address.toString())
            .put("expectedProductVersion", 0)
            .putNull("expectedEventVersion")
            .put("expectedTotalAmount", 10_000)
            .put("externalMethod", "CREDIT_CARD")
            .put("methodAccountRef", "test-card");
    var headers = headers(buyer);
    headers.set("Idempotency-Key", UUID.randomUUID().toString());
    var response =
        http.exchange(
            "/api/orders", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    assertThat(response.getStatusCode().value()).isEqualTo(201);
    UUID id = UUID.fromString(response.getBody().path("id").asText());
    UUID cycle =
        jdbc.queryForObject(
            "select payment_cycle_id from commerce.orders where id=?", UUID.class, id);
    return new Fixture(id, buyer, cycle, response.getBody());
  }

  private EventEnvelope<PaymentAcceptedPayload> event(
      Fixture f, UUID payment, long version, UUID cycle) {
    return new EventEnvelope<>(
        UUID.randomUUID(),
        "PaymentAccepted",
        1,
        NOW.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),
        payment,
        42,
        new PaymentAcceptedPayload(payment, f.id(), version, cycle));
  }

  private void accept(EventEnvelope<PaymentAcceptedPayload> event, long offset) throws Exception {
    service.accept(event, new Source(TOPIC, 0, offset, mapper.writeValueAsBytes(event)));
  }

  private JsonNode get(Fixture f) {
    return http.exchange(
            "/api/orders/" + f.id(),
            HttpMethod.GET,
            new HttpEntity<>(headers(f.buyer())),
            JsonNode.class)
        .getBody();
  }

  private static HttpHeaders headers(UUID buyer) {
    var h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    h.setBearerAuth(GATEWAY_CREDENTIAL);
    h.set("X-Authenticated-Actor-Kind", "BUYER");
    h.set("X-Authenticated-Actor-Id", buyer.toString());
    return h;
  }

  private void assertInventory(Fixture f) {
    assertThat(
            jdbc.queryForMap(
                "select available_qty,reserved_qty,sold_qty from commerce.product_inventory where product_id=(select product_id from commerce.orders where id=?)",
                f.id()))
        .containsEntry("available_qty", 1L)
        .containsEntry("reserved_qty", 1L)
        .containsEntry("sold_qty", 0L);
  }

  private record Fixture(UUID id, UUID buyer, UUID cycle, JsonNode original) {}

  @TestConfiguration
  static class TestBeans {
    @Bean
    @Primary
    TestClock acceptanceClock() {
      return new TestClock();
    }

    @Bean
    NewTopic paymentEvents() {
      return new NewTopic(TOPIC, 3, (short) 1);
    }
  }

  static class TestClock extends Clock {
    final AtomicReference<Instant> time = new AtomicReference<>(NOW);

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant(), zone);
    }

    @Override
    public Instant instant() {
      return time.get();
    }
  }
}
