package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.application.port.NotificationGateway;
import com.commercesettle.commerce.application.port.NotificationStore;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.Notification;
import com.commercesettle.commerce.infrastructure.client.kakao.KakaoSelfNotificationGateway;
import com.commercesettle.commerce.infrastructure.config.KakaoSelfProperties;
import com.commercesettle.commerce.infrastructure.mock.MockNotificationGateway;
import com.commercesettle.commerce.infrastructure.observability.NotificationMetrics;
import com.commercesettle.commerce.infrastructure.persistence.notification.NotificationReceiptStore;
import com.commercesettle.commerce.support.KakaoDemoFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
      "app.auth.rate-limit.identifier-hmac-key=notification-test-hmac-key-32bytes-long",
      "app.auth.password-reset.delivery-key=notification-test-delivery-key-32bytes",
      "app.address.encryption-key=notification-test-address-key-32bytes-long",
      "app.internal-auth.senders.gateway.current=" + NotificationIntegrationTest.GATEWAY,
      "app.internal-auth.senders[operator\\:test].current=" + NotificationIntegrationTest.OPERATOR
    })
@Testcontainers
class NotificationIntegrationTest {
  static final String GATEWAY = "test-gateway-credential-0123456789abcdef0123";
  static final String OPERATOR = "test-operator-credential-0123456789abcdef0123";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired NotificationStore store;
  @Autowired NotificationGateway gateway;
  @Autowired NotificationService service;
  @Autowired NotificationReceiptStore receipts;
  @Autowired PlatformTransactionManager manager;
  @Autowired DataSource dataSource;
  @TempDir Path demoDirectory;

  @Autowired
  @Qualifier("notificationCalls")
  ExecutorService calls;

  TransactionTemplate tx;

  @BeforeEach
  void reset() {
    tx = new TransactionTemplate(manager);
    for (String table :
        List.of(
            "notification_attempts",
            "notification_items",
            "notification_deliveries",
            "notification_mock_receipts",
            "notification_inbox_quarantines")) jdbc.update("DELETE FROM commerce." + table);
    jdbc.update("DELETE FROM commerce.inbox WHERE consumer='commerce-notifications.v1'");
  }

  @Test
  @DisplayName("인증된 구매자에게 본인 알림의 빈 목록을 제공한다")
  void listsOwnNotifications() {
    var response = get(buyer(), "/api/notifications");

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody().path("items").isArray()).isTrue();
    assertThat(response.getBody().path("items").size()).isZero();
  }

  @Test
  @DisplayName("알림을 추가해도 기존 주문 목록 조회를 유지한다")
  void preservesOrderReads() {
    assertThat(get(buyer(), "/api/orders").getStatusCode().value()).isEqualTo(200);
  }

  @Test
  @DisplayName("계정 종류와 ID로 알림을 격리하고 사용자·운영 응답을 정식 명세에 맞춘다")
  void isolatesRecipientKindsAndMatchesApiContracts() throws Exception {
    UUID buyer = buyer();
    seller(buyer);
    UUID id = append(buyer, Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID());
    var own = get(buyer, "/api/notifications/" + id);
    assertThat(own.getStatusCode().value()).isEqualTo(200);
    matches("Notification", own.getBody());
    assertThat(own.getBody().path("isMock").asBoolean()).isTrue();
    assertThat(own.getBody().path("occurredAt").asText()).endsWith("+09:00");
    assertThat(get(buyer(), "/api/notifications/" + id).getStatusCode().value()).isEqualTo(404);
    var sellerHeaders = headers(buyer);
    sellerHeaders.set("X-Authenticated-Actor-Kind", "SELLER");
    var otherKind =
        http.exchange(
            "/api/notifications/" + id,
            HttpMethod.GET,
            new HttpEntity<>(sellerHeaders),
            JsonNode.class);
    assertThat(otherKind.getStatusCode().value()).isEqualTo(404);
    var page = get(buyer, "/api/notifications?page=2147483647&size=100");
    assertThat(page.getStatusCode().value()).isEqualTo(200);
    assertThat(page.getBody().path("items").isEmpty()).isTrue();
    matches("NotificationPage", page.getBody());
    var internal = internal("/internal/notification-deliveries/" + id, null, null);
    assertThat(internal.getStatusCode().value()).isEqualTo(200);
    matches("NotificationDelivery", internal.getBody());
    var forbidden =
        http.exchange(
            "/internal/notification-deliveries/" + id,
            HttpMethod.GET,
            new HttpEntity<>(headers(buyer)),
            JsonNode.class);
    assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
    assertThat(
            internal("/internal/notification-mock/receipts/" + id, null, null)
                .getStatusCode()
                .value())
        .isEqualTo(404);
  }

  @Test
  @DisplayName("동일 업무 항목의 동시 접수는 발송·집계 하나만 만들고 본문 충돌은 보존본을 바꾸지 않는다")
  void deduplicatesConcurrentBusinessItems() throws Exception {
    UUID buyer = buyer(), business = UUID.randomUUID();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var a =
          pool.submit(
              () -> {
                start.await();
                return append(buyer, Notification.Kind.SHIPMENT_STARTED, business);
              });
      var b =
          pool.submit(
              () -> {
                start.await();
                return append(buyer, Notification.Kind.SHIPMENT_STARTED, business);
              });
      start.countDown();
      assertThat(a.get(10, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo(b.get(10, java.util.concurrent.TimeUnit.SECONDS));
    }
    assertThat(number("notification_items")).isEqualTo(1);
    assertThat(number("notification_deliveries")).isEqualTo(1);
    var draft =
        new NotificationStore.Draft(
            "commerce",
            UUID.randomUUID(),
            Notification.Kind.SHIPMENT_STARTED,
            business,
            ActorKind.BUYER,
            buyer,
            Instant.now(),
            mapper.createObjectNode().put("orderId", business.toString()).put("changed", true));
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> store.append(draft)))
        .isInstanceOf(Notification.ContentConflictException.class);
    assertThat(number("notification_items")).isEqualTo(1);
  }

  @Test
  @DisplayName("알림 항목·발송 생성은 같은 Commerce 업무 변경 실패와 함께 롤백한다")
  void rollsBackNotificationWithBusinessChange() {
    UUID buyer = buyer();
    var draft = draft(buyer, Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID());
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      jdbc.update(
                          "UPDATE commerce.buyer_accounts SET display_name='변경' WHERE id=?", buyer);
                      store.append(draft);
                      throw new IllegalStateException("business failed");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT display_name FROM commerce.buyer_accounts WHERE id=?", String.class, buyer))
        .isEqualTo("구매자");
    assertThat(number("notification_items")).isZero();
    assertThat(number("notification_deliveries")).isZero();
  }

  @Test
  @DisplayName("응답 유실 뒤 원 deliveryId를 조회해 발송을 완료하며 수신 기록은 하나다")
  void reconcilesLostResponseBeforeAnyResend() throws Exception {
    UUID id = append(buyer(), Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID());
    var mock = mock(0, 0, 1000);
    Instant now = Instant.now().plusSeconds(1);
    assertThat(worker(mock, now).processNext()).isTrue();
    var unknown = store.find(id).orElseThrow();
    assertThat(unknown.status()).isEqualTo(Notification.Status.UNKNOWN);
    assertThat(number("notification_mock_receipts")).isEqualTo(1);
    var receipt = receipts.find(id);
    assertThat(worker(mock, unknown.nextRunAt()).processNext()).isTrue();
    var sent = store.find(id).orElseThrow();
    assertThat(sent.status()).isEqualTo(Notification.Status.SENT);
    assertThat(sent.sentAt()).isEqualTo(receipt.processedAt());
    assertThat(store.attempts(id, 0, 20).items())
        .extracting(NotificationStore.Attempt::operation)
        .containsExactly("LOOKUP", "SEND");
    assertThat(number("notification_mock_receipts")).isEqualTo(1);
    matches(
        "MockNotificationReceipt",
        internal("/internal/notification-mock/receipts/" + id, null, null).getBody());
    matches(
        "NotificationAttemptPage",
        internal("/internal/notification-deliveries/" + id + "/attempts", null, null).getBody());
    var replay =
        mapper.createObjectNode().put("expectedVersion", sent.version()).put("reason", "원결과 확인");
    var conflict =
        internal(
            "/internal/notification-deliveries/" + id + "/replays",
            replay,
            UUID.randomUUID().toString());
    assertThat(conflict.getStatusCode().value()).isEqualTo(409);
    assertThat(conflict.getBody().path("code").asText()).isEqualTo("NOTIFICATION_REPLAY_CONFLICT");
  }

  @Test
  @DisplayName("확정 거절은 설정 변경·운영 재처리 뒤에도 같은 원결과와 본문을 보존한다")
  void preservesRejectedReceiptAcrossReplayAndConfigChange() {
    UUID id = append(buyer(), Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID());
    worker(mock(0, 1000, 0), Instant.now().plusSeconds(1)).processNext();
    var rejected = store.find(id).orElseThrow();
    assertThat(rejected.status()).isEqualTo(Notification.Status.REVIEW_REQUIRED);
    var original = receipts.find(id);
    String key = UUID.randomUUID().toString();
    var body =
        mapper
            .createObjectNode()
            .put("expectedVersion", rejected.version())
            .put("reason", "원키 재확인");
    var replay = internal("/internal/notification-deliveries/" + id + "/replays", body, key);
    assertThat(replay.getStatusCode().value()).isEqualTo(202);
    assertThat(
            internal("/internal/notification-deliveries/" + id + "/replays", body, key)
                .getStatusCode()
                .value())
        .isEqualTo(202);
    assertThat(
            internal(
                    "/internal/notification-deliveries/" + id + "/replays",
                    body.deepCopy().put("reason", "다른 사유"),
                    key)
                .getStatusCode()
                .value())
        .isEqualTo(409);
    worker(mock(0, 0, 0), Instant.now().plusSeconds(3)).processNext();
    var after = store.find(id).orElseThrow();
    assertThat(after.status()).isEqualTo(Notification.Status.REVIEW_REQUIRED);
    assertThat(after.body()).isEqualTo(rejected.body());
    assertThat(after.attemptCount()).isEqualTo(3);
    assertThat(after.retryCount()).isEqualTo(1);
    assertThat(receipts.find(id)).isEqualTo(original);
    assertThat(store.attempts(id, 0, 20).items())
        .extracting(NotificationStore.Attempt::operation)
        .containsExactly("SEND", "REPLAY", "SEND");
  }

  @Test
  @DisplayName("일시적 발송 실패를 라운드 10회와 1초부터 60초의 재시도 상한에서 중단한다")
  void capsAutomaticSendAttempts() {
    UUID id = append(buyer(), Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID());
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusSeconds(1);
    var mock = mock(1000, 0, 0);
    long[] delays = {1, 2, 4, 8, 16, 32, 60, 60, 60, 60};
    for (int index = 0; index < 10; index++) {
      assertThat(worker(mock, now).processNext()).isTrue();
      var current = store.find(id).orElseThrow();
      assertThat(current.retryCount()).isEqualTo(index + 1);
      assertThat(current.nextRunAt()).isEqualTo(now.plusSeconds(delays[index]));
      now = current.nextRunAt();
    }
    assertThat(store.find(id).orElseThrow().status())
        .isEqualTo(Notification.Status.REVIEW_REQUIRED);
    assertThat(worker(mock, now.plusSeconds(60)).processNext()).isFalse();
    assertThat(number("notification_attempts")).isEqualTo(10);
    assertThat(number("notification_mock_receipts")).isZero();
  }

  @Test
  @DisplayName("만료된 임대의 늦은 결과를 차단하고 새 임대는 원수신 조회부터 재개한다")
  void fencesExpiredLeaseAndStartsLookup() {
    UUID id = append(buyer(), Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID());
    Instant now = Instant.now().plusSeconds(1);
    var first = tx.execute(status -> store.claim(now)).lease();
    var receipt = gateway.send(first.notification(), first.notification().attemptCount());
    var second = tx.execute(status -> store.claim(now.plusSeconds(31))).lease();
    assertThat(second.operation()).isEqualTo("LOOKUP");
    tx.executeWithoutResult(
        status ->
            store.finish(
                first,
                Notification.Status.SENT,
                null,
                receipt.processedAt(),
                "SUCCEEDED",
                now.plusSeconds(32)));
    assertThat(store.find(id).orElseThrow().leaseToken())
        .isEqualTo(second.notification().leaseToken());
    assertThat(store.find(id).orElseThrow().status()).isEqualTo(Notification.Status.SENDING);
    tx.executeWithoutResult(
        status ->
            store.finish(
                second,
                Notification.Status.SENT,
                null,
                receipt.processedAt(),
                "SUCCEEDED",
                now.plusSeconds(32)));
    assertThat(store.find(id).orElseThrow().status()).isEqualTo(Notification.Status.SENT);
    assertThat(store.attempts(id, 0, 20).items())
        .extracting(NotificationStore.Attempt::status)
        .containsExactly("SUCCEEDED", "UNKNOWN");
    assertThat(number("notification_mock_receipts")).isEqualTo(1);
  }

  @Test
  @DisplayName("잠긴 도래 발송을 건너뛰고 다른 발송을 선점한다")
  void skipsLockedDelivery() throws Exception {
    UUID first = append(buyer(), Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID());
    UUID second = append(buyer(), Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID());
    try (var holder = jdbc.getDataSource().getConnection()) {
      holder.setAutoCommit(false);
      try (var lock =
          holder.prepareStatement(
              "SELECT id FROM commerce.notification_deliveries WHERE id=? FOR UPDATE")) {
        lock.setObject(1, first);
        lock.executeQuery().close();
        var claimed = tx.execute(status -> store.claim(Instant.now().plusSeconds(1)));
        assertThat(claimed.lease().notification().id()).isEqualTo(second);
        assertThat(store.find(first).orElseThrow().status()).isEqualTo(Notification.Status.PENDING);
      } finally {
        holder.rollback();
      }
    }
  }

  @Test
  @DisplayName("운영 재처리의 행 잠금 대기 실패는 멱등 기록·시도 변경까지 롤백한다")
  void boundsReplayLockWaitAndReusesKey() throws Exception {
    UUID id = append(buyer(), Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID());
    jdbc.update("UPDATE commerce.notification_deliveries SET status='RETRY' WHERE id=?", id);
    var n = store.find(id).orElseThrow();
    var body =
        mapper.createObjectNode().put("expectedVersion", n.version()).put("reason", "원결과 재확인");
    String key = UUID.randomUUID().toString();
    try (var holder = jdbc.getDataSource().getConnection()) {
      holder.setAutoCommit(false);
      try (var lock =
          holder.prepareStatement(
              "SELECT id FROM commerce.notification_deliveries WHERE id=? FOR UPDATE")) {
        lock.setObject(1, id);
        lock.executeQuery().close();
        long start = System.nanoTime();
        assertThat(
                internal("/internal/notification-deliveries/" + id + "/replays", body, key)
                    .getStatusCode()
                    .value())
            .isEqualTo(503);
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
            .isLessThan(java.time.Duration.ofSeconds(6));
        assertThat(store.find(id).orElseThrow().attemptCount()).isZero();
      } finally {
        holder.rollback();
      }
    }
    assertThat(
            internal("/internal/notification-deliveries/" + id + "/replays", body, key)
                .getStatusCode()
                .value())
        .isEqualTo(202);
    assertThat(store.find(id).orElseThrow().attemptCount()).isEqualTo(1);
  }

  private UUID append(UUID buyer, Notification.Kind kind, UUID business) {
    tx.executeWithoutResult(status -> store.append(draft(buyer, kind, business)));
    return jdbc.queryForObject(
        "SELECT delivery_id FROM commerce.notification_items WHERE business_ref=? AND recipient_kind='BUYER' AND recipient_id=?",
        UUID.class,
        business,
        buyer);
  }

  @Test
  @DisplayName("미확정 조회도 10회에서 보존하고 운영 재처리의 첫 작업은 원키 조회다")
  void preservesLookupFirstWhenUnknownRoundIsExhausted() {
    UUID id = append(buyer(), Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID());
    NotificationGateway unknown =
        new NotificationGateway() {
          public Receipt send(Notification n, int attempt) {
            throw new Failure("NOTIFICATION_RESPONSE_UNKNOWN", false, true);
          }

          public Receipt lookup(UUID delivery, int attempt) {
            throw new Failure("NOTIFICATION_RESPONSE_UNKNOWN", false, true);
          }

          public String requestHash(Notification n) {
            return gateway.requestHash(n);
          }
        };
    Instant now = Instant.now().plusSeconds(1);
    for (int index = 0; index < 10; index++) {
      assertThat(worker(unknown, now).processNext()).isTrue();
      now = store.find(id).orElseThrow().nextRunAt();
    }
    var n = store.find(id).orElseThrow();
    assertThat(n.status()).isEqualTo(Notification.Status.REVIEW_REQUIRED);
    var body =
        mapper.createObjectNode().put("expectedVersion", n.version()).put("reason", "원수신 재조회");
    assertThat(
            internal(
                    "/internal/notification-deliveries/" + id + "/replays",
                    body,
                    UUID.randomUUID().toString())
                .getStatusCode()
                .value())
        .isEqualTo(202);
    var replay = store.find(id).orElseThrow();
    assertThat(replay.status()).isEqualTo(Notification.Status.UNKNOWN);
    assertThat(replay.retryCount()).isZero();
    assertThat(replay.attemptCount()).isEqualTo(11);
    worker(gateway, now.plusSeconds(1)).processNext();
    assertThat(store.attempts(id, 0, 20).items().getFirst().operation()).isEqualTo("LOOKUP");
    assertThat(number("notification_mock_receipts")).isZero();
    worker(gateway, store.find(id).orElseThrow().nextRunAt()).processNext();
    assertThat(store.find(id).orElseThrow().status()).isEqualTo(Notification.Status.SENT);
    assertThat(number("notification_mock_receipts")).isEqualTo(1);
  }

  @Test
  @DisplayName("판매자별 같은 접수 구간의 판매만 합산하고 닫힌 묶음은 늦은 이벤트로 바꾸지 않는다")
  void aggregatesSalesAndKeepsClosedBucketImmutable() throws Exception {
    UUID seller = UUID.randomUUID();
    seller(seller);
    UUID first = UUID.randomUUID();
    sale(seller, first, 10000);
    UUID closed =
        jdbc.queryForObject(
            "SELECT delivery_id FROM commerce.notification_items WHERE business_ref=?",
            UUID.class,
            first);
    Instant previous =
        Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).minusSeconds(60);
    jdbc.update(
        "UPDATE commerce.notification_deliveries SET bucket_start=?,bucket_end=?,dedup_key=?,next_run_at=?,created_at=? WHERE id=?",
        previous.atOffset(ZoneOffset.UTC),
        previous.plusSeconds(60).atOffset(ZoneOffset.UTC),
        previous.atZone(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime().toString(),
        previous.plusSeconds(60).atOffset(ZoneOffset.UTC),
        previous.plusSeconds(5).atOffset(ZoneOffset.UTC),
        closed);
    jdbc.update(
        "UPDATE commerce.notification_items SET created_at=? WHERE delivery_id=?",
        previous.plusSeconds(5).atOffset(ZoneOffset.UTC),
        closed);
    assertThat(worker(gateway, Instant.now().plusSeconds(1)).processNext()).isTrue();
    var old = store.find(closed).orElseThrow();
    assertThat(old.status()).isEqualTo(Notification.Status.SENT);
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(8))
        .until(() -> Instant.now().atOffset(ZoneOffset.UTC).getSecond() < 53);
    UUID second = UUID.randomUUID(), third = UUID.randomUUID();
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var a =
          executor.submit(
              () -> {
                start.await();
                sale(seller, second, 12000);
                return true;
              });
      var b =
          executor.submit(
              () -> {
                start.await();
                sale(seller, third, 17000);
                return true;
              });
      start.countDown();
      a.get(10, java.util.concurrent.TimeUnit.SECONDS);
      b.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    sale(seller, first, 10000);
    var all = store.page(ActorKind.SELLER, seller, null, 0, 20);
    assertThat(all.items()).hasSize(2);
    var open =
        all.items().stream()
            .filter(n -> n.status() == Notification.Status.COLLECTING)
            .findFirst()
            .orElseThrow();
    assertThat(open.itemCount()).isEqualTo(2);
    assertThat(open.totalAmount()).isEqualTo(29000);
    assertThat(open.body()).isEmpty();
    assertThat(store.find(closed).orElseThrow()).isEqualTo(old);
    assertThat(number("notification_items")).isEqualTo(3);
  }

  private void sale(UUID seller, UUID business, long amount) {
    var draft =
        new NotificationStore.Draft(
            "commerce",
            UUID.randomUUID(),
            Notification.Kind.SALE_COMPLETED,
            business,
            ActorKind.SELLER,
            seller,
            Instant.parse("2026-11-01T00:00:00Z"),
            mapper
                .createObjectNode()
                .put("orderId", business.toString())
                .put("totalAmount", amount));
    tx.executeWithoutResult(status -> store.append(draft));
  }

  @Test
  @DisplayName("한 회차의 선행 대기 100건을 재예약해도 다음 도래 notice의 처리 기회를 보존한다")
  void schedulesDueNoticeBeforeRescheduledPredecessorWaits() throws Exception {
    Instant now = Instant.parse("2026-11-02T00:00:00Z");
    UUID readyEvent = null;
    for (int index = 0; index <= 100; index++) {
      var event = settlementNotice();
      var source =
          new NotificationStore.Source(
              "commerce-settle.settlement.v1",
              0,
              index,
              event.payload().path("batchId").asText(),
              mapper.writeValueAsBytes(event));
      tx.executeWithoutResult(status -> store.accept(event, source, now));
      jdbc.update(
          "UPDATE commerce.inbox SET created_at=? WHERE consumer='commerce-notifications.v1' AND event_id=?",
          now.plusMillis(index).atOffset(ZoneOffset.UTC),
          event.eventId());
      readyEvent = event.eventId();
    }
    for (int index = 0; index < 100; index++) {
      tx.executeWithoutResult(
          status -> {
            var notice = store.claimNotice(null, now).orElseThrow();
            store.retryNotice(
                notice.id(),
                notice.version(),
                "NOTIFICATION_ORDER_PREDECESSOR_NOT_READY",
                false,
                now);
          });
    }

    var next = tx.execute(status -> store.claimNotice(null, now.plusSeconds(2)).orElseThrow());

    assertThat(next.event().eventId()).isEqualTo(readyEvent);
  }

  @Test
  @DisplayName("늦은 Inbox 실패 복구는 다른 작업자가 완료한 상태와 버전을 되돌리지 않는다")
  void fencesLateInboxRecovery() throws Exception {
    var event = settlementNotice();
    var source =
        new NotificationStore.Source(
            "commerce-settle.settlement.v1",
            0,
            100,
            event.payload().path("batchId").asText(),
            mapper.writeValueAsBytes(event));
    Instant now = Instant.now();
    tx.executeWithoutResult(status -> store.accept(event, source, now));
    var old = new java.util.concurrent.atomic.AtomicReference<NotificationStore.Notice>();
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      old.set(store.claimNotice(event.eventId(), now).orElseThrow());
                      throw new IllegalStateException("apply rolled back");
                    }))
        .isInstanceOf(IllegalStateException.class);
    tx.executeWithoutResult(
        status -> {
          var newer = store.claimNotice(event.eventId(), now).orElseThrow();
          store.completeNotice(newer.id(), newer.version(), now);
        });
    tx.executeWithoutResult(
        status ->
            store.retryNotice(
                old.get().id(),
                old.get().version(),
                "NOTIFICATION_NOTICE_APPLY_FAILED",
                false,
                now.plusSeconds(1)));
    var row =
        jdbc.queryForMap(
            "SELECT status,version,attempts FROM commerce.inbox WHERE event_id=?", event.eventId());
    assertThat(row.get("status")).isEqualTo("APPLIED");
    assertThat(((Number) row.get("version")).longValue()).isEqualTo(1);
    assertThat(((Number) row.get("attempts")).intValue()).isZero();
  }

  @Test
  @DisplayName("발송 응답 지연 중 DB 트랜잭션·커넥션을 보유하지 않고 5초 제한 뒤 미확정으로 남긴다")
  void boundsExternalCallWithoutHoldingConnection() throws Exception {
    UUID id = append(buyer(), Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID());
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var active = new java.util.concurrent.atomic.AtomicBoolean(true);
    NotificationGateway blocked =
        new NotificationGateway() {
          public Receipt send(Notification n, int attempt) {
            active.set(
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            entered.countDown();
            try {
              release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            throw new NotificationGateway.Failure("NOTIFICATION_RESPONSE_UNKNOWN", false, true);
          }

          public Receipt lookup(UUID delivery, int attempt) {
            return null;
          }

          public String requestHash(Notification n) {
            return gateway.requestHash(n);
          }
        };
    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      long start = System.nanoTime();
      var pending =
          executor.submit(() -> worker(blocked, Instant.now().plusSeconds(1)).processNext());
      try {
        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(active).isFalse();
        assertThat(
                ((com.zaxxer.hikari.HikariDataSource) jdbc.getDataSource())
                    .getHikariPoolMXBean()
                    .getActiveConnections())
            .isZero();
        assertThat(pending.get(8, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
            .isLessThan(java.time.Duration.ofSeconds(8));
        assertThat(store.find(id).orElseThrow().status()).isEqualTo(Notification.Status.UNKNOWN);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  @DisplayName("같은 원수신 키의 다른 본문을 거절하고 최초 수신 기록을 보존한다")
  void rejectsChangedBodyForOriginalReceiptKey() {
    UUID id = append(buyer(), Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID());
    var original = store.find(id).orElseThrow();
    var receipt = gateway.send(original, 1);
    jdbc.update("UPDATE commerce.notification_deliveries SET body=body||'변경' WHERE id=?", id);
    assertThatThrownBy(() -> gateway.send(store.find(id).orElseThrow(), 2))
        .isInstanceOf(NotificationGateway.Failure.class)
        .satisfies(
            error ->
                assertThat(((NotificationGateway.Failure) error).code())
                    .isEqualTo("NOTIFICATION_BODY_CONFLICT"));
    assertThat(receipts.find(id)).isEqualTo(receipt);
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  @DisplayName("발송 결과 로그는 실제 커밋 뒤에만 남고 롤백·stale worker는 성공을 기록하지 않는다")
  void logsOnlyCommittedDeliveryResults(CapturedOutput output) {
    UUID success = append(buyer(), Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID());
    assertThat(worker(gateway, Instant.now().plusSeconds(1)).processNext()).isTrue();
    assertThat(logEntries(output, "notification.send", success))
        .extracting(row -> row.path("result").asText())
        .containsExactly("SENT");

    UUID rollback = append(buyer(), Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID());
    jdbc.execute(
        "CREATE FUNCTION commerce.reject_notification_finish() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='SENT' THEN RAISE EXCEPTION 'injected'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_notification_finish BEFORE UPDATE ON commerce.notification_deliveries FOR EACH ROW EXECUTE FUNCTION commerce.reject_notification_finish()");
    try {
      assertThatThrownBy(() -> worker(gateway, Instant.now().plusSeconds(1)).processNext())
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(store.find(rollback).orElseThrow().status())
          .isEqualTo(Notification.Status.SENDING);
      assertThat(receipts.find(rollback)).isNotNull();
      assertThat(logEntries(output, "notification.send", rollback)).isEmpty();
    } finally {
      jdbc.execute("DROP TRIGGER reject_notification_finish ON commerce.notification_deliveries");
      jdbc.execute("DROP FUNCTION commerce.reject_notification_finish()");
    }

    UUID stale = append(buyer(), Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID());
    var replaced =
        new NotificationGateway() {
          @Override
          public Receipt send(Notification notification, int attempt) {
            var receipt = gateway.send(notification, attempt);
            jdbc.update(
                "UPDATE commerce.notification_deliveries SET version=version+1 WHERE id=?", stale);
            return receipt;
          }

          @Override
          public Receipt lookup(UUID id, int attempt) {
            return gateway.lookup(id, attempt);
          }

          @Override
          public String requestHash(Notification notification) {
            return gateway.requestHash(notification);
          }
        };
    assertThat(worker(replaced, Instant.now().plusSeconds(1)).processNext()).isTrue();
    assertThat(store.find(stale).orElseThrow().status()).isEqualTo(Notification.Status.SENDING);
    assertThat(logEntries(output, "notification.send", stale)).isEmpty();
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  @DisplayName("재처리 로그는 롤백 때 남기지 않고 커밋 뒤 자유 입력 없이 한 번만 기록한다")
  void logsReplayOnlyAfterCommit(CapturedOutput output) {
    UUID id = append(buyer(), Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID());
    worker(mock(0, 1000, 0), Instant.now().plusSeconds(1)).processNext();
    var rejected = store.find(id).orElseThrow();
    String key = UUID.randomUUID().toString();
    String reason = "private-notification-replay-reason";
    jdbc.execute(
        "CREATE FUNCTION commerce.reject_replay_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test replay commit failure'; END $$");
    jdbc.execute(
        "CREATE CONSTRAINT TRIGGER reject_replay_commit AFTER INSERT ON commerce.notification_attempts DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.operation='REPLAY') EXECUTE FUNCTION commerce.reject_replay_commit()");
    try {
      assertThatThrownBy(() -> service.replay("operator:test", id, key, rejected.version(), reason))
          .hasStackTraceContaining("test replay commit failure");
    } finally {
      jdbc.execute("DROP TRIGGER reject_replay_commit ON commerce.notification_attempts");
      jdbc.execute("DROP FUNCTION commerce.reject_replay_commit()");
    }
    assertThat(store.find(id).orElseThrow().version()).isEqualTo(rejected.version());
    assertThat(logEntries(output, "notification.replay", id)).isEmpty();
    service.replay("operator:test", id, key, rejected.version(), reason);
    service.replay("operator:test", id, key, rejected.version(), reason);
    assertThat(logEntries(output, "notification.replay", id)).hasSize(1);
    assertThat(output.getAll()).doesNotContain(reason);
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  @DisplayName("만료 임대의 UNKNOWN 복구와 같은 이벤트의 다른 본문 격리도 커밋 후 기록한다")
  void logsExpiredAttemptAndConflictingEvent(CapturedOutput output) throws Exception {
    UUID id = append(buyer(), Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID());
    Instant now = Instant.now().plusSeconds(1);
    var expired = tx.execute(status -> store.claim(now)).lease();
    gateway.send(expired.notification(), expired.notification().attemptCount());
    assertThat(worker(gateway, now.plusSeconds(31)).processNext()).isTrue();
    assertThat(logEntries(output, "notification.lease.expired", id))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.path("result").asText()).isEqualTo("UNKNOWN");
              assertThat(row.path("errorCode").asText()).isEqualTo("NOTIFICATION_LEASE_EXPIRED");
              assertThat(row.path("level").asText()).isEqualTo("WARN");
            });
    assertThat(store.find(id).orElseThrow().status()).isEqualTo(Notification.Status.SENT);

    var event = settlementNotice();
    seller(UUID.fromString(event.payload().path("sellerId").asText()));
    byte[] raw = mapper.writeValueAsBytes(event);
    var source =
        new NotificationStore.Source(
            "commerce-settle.settlement.v1", 0, 200, event.payload().path("batchId").asText(), raw);
    service.accept(event, source);
    var changed =
        new com.commercesettle.commerce.application.event.EventEnvelope<JsonNode>(
            event.eventId(),
            event.eventType(),
            event.eventVersion(),
            event.occurredAt(),
            event.aggregateId(),
            event.aggregateVersion() + 1,
            event.payload());
    service.accept(
        changed,
        new NotificationStore.Source(
            source.topic(), 0, 201, source.key(), mapper.writeValueAsBytes(changed)));
    service.accept(event, source);
    assertThat(logEntries(output, "notification.notice.quarantine", null))
        .singleElement()
        .satisfies(
            row ->
                assertThat(row.path("errorCode").asText())
                    .isEqualTo("NOTIFICATION_EVENT_BODY_CONFLICT"));
    assertThat(
            jdbc.queryForObject(
                "SELECT raw_body FROM commerce.inbox WHERE consumer='commerce-notifications.v1' AND event_id=?",
                byte[].class,
                event.eventId()))
        .isEqualTo(raw);
    assertThat(number("notification_inbox_quarantines")).isEqualTo(1);
  }

  @Test
  @DisplayName("실제 채널의 응답 유실은 수동 확인으로 멈추고 명시적 replay만 원본문을 다시 보낸다")
  void stopsSelfSendUnknownUntilExplicitReplay() throws Exception {
    UUID buyer = buyer();
    try (var demo =
        new KakaoDemoFixture(
            gateway,
            mapper,
            Clock.systemUTC(),
            demoDirectory,
            java.util.Set.of(buyer),
            java.util.Set.of())) {
      UUID id =
          appendSelf(
              buyer, Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID(), demo.properties());
      var original = store.find(id).orElseThrow();
      demo.loseResponse(true);

      assertThat(worker(demo.gateway(), Instant.now().plusSeconds(1)).processNext()).isTrue();

      var unknown = store.find(id).orElseThrow();
      assertThat(unknown.status()).isEqualTo(Notification.Status.REVIEW_REQUIRED);
      assertThat(store.attempts(id, 0, 20).items())
          .singleElement()
          .satisfies(
              a -> {
                assertThat(a.operation()).isEqualTo("SEND");
                assertThat(a.status()).isEqualTo("UNKNOWN");
              });
      assertThat(worker(demo.gateway(), Instant.now().plusSeconds(60)).processNext()).isFalse();
      assertThat(demo.messages()).containsExactly(original.body());
      assertThat(number("notification_mock_receipts")).isZero();
      assertThat(
              internal("/internal/notification-mock/receipts/" + id, null, null)
                  .getStatusCode()
                  .value())
          .isEqualTo(404);
      var own = get(buyer, "/api/notifications/" + id);
      assertThat(own.getBody().path("channel").asText()).isEqualTo("KAKAO_SELF");
      assertThat(own.getBody().path("isMock").asBoolean()).isFalse();
      matches("Notification", own.getBody());
      demo.loseResponse(false);
      service.replay(
          "operator:test",
          id,
          UUID.randomUUID().toString(),
          unknown.version(),
          "수신 미확정과 중복 가능성을 확인하고 재발송");

      assertThat(worker(demo.gateway(), Instant.now().plusSeconds(1)).processNext()).isTrue();

      var sent = store.find(id).orElseThrow();
      assertThat(sent.status()).isEqualTo(Notification.Status.SENT);
      assertThat(sent.body()).isEqualTo(original.body());
      assertThat(sent.channelAccountRef()).isEqualTo(original.channelAccountRef());
      assertThat(demo.messages()).containsExactly(original.body(), original.body());
      assertThat(store.attempts(id, 0, 20).items())
          .anySatisfy(
              a -> {
                assertThat(a.operation()).isEqualTo("REPLAY");
                assertThat(a.reason()).contains("중복 가능성");
              });
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("계정 변경·시연 해제는 기존 실제 발송을 새 계정이나 모의 채널로 돌리지 않는다")
  void preservesPendingSelfDestinationWhenConfigurationChanges(boolean changedOwner)
      throws Exception {
    UUID buyer = buyer();
    try (var demo =
        new KakaoDemoFixture(
            gateway,
            mapper,
            Clock.systemUTC(),
            demoDirectory,
            java.util.Set.of(buyer),
            java.util.Set.of())) {
      UUID id =
          appendSelf(
              buyer, Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID(), demo.properties());
      var original = store.find(id).orElseThrow();
      var old = demo.properties();
      var changed =
          new KakaoSelfProperties(
              old.credentialsFile(),
              "999",
              old.appId(),
              old.messageLink(),
              old.buyerIds(),
              old.sellerIds());
      NotificationGateway current =
          changedOwner
              ? new KakaoSelfNotificationGateway(gateway, demo.client(), changed, Clock.systemUTC())
              : gateway;

      assertThat(worker(current, Instant.now().plusSeconds(1)).processNext()).isTrue();

      var stored = store.find(id).orElseThrow();
      assertThat(stored.status()).isEqualTo(Notification.Status.REVIEW_REQUIRED);
      assertThat(stored.channel()).isEqualTo(Notification.Channel.KAKAO_SELF);
      assertThat(stored.channelAccountRef()).isEqualTo(original.channelAccountRef());
      assertThat(stored.body()).isEqualTo(original.body());
      assertThat(stored.lastErrorCode())
          .isEqualTo(changedOwner ? "KAKAO_DESTINATION_CHANGED" : "KAKAO_SELF_DISABLED");
      assertThat(demo.messages()).isEmpty();
      assertThat(number("notification_mock_receipts")).isZero();
    }
  }

  @Test
  @DisplayName("실제 발송의 만료 임대는 조회·재발송 없이 확인 대상으로 보존한다")
  void movesExpiredSelfLeaseToReviewWithoutLookup() throws Exception {
    UUID buyer = buyer();
    try (var demo =
        new KakaoDemoFixture(
            gateway,
            mapper,
            Clock.systemUTC(),
            demoDirectory,
            java.util.Set.of(buyer),
            java.util.Set.of())) {
      UUID id =
          appendSelf(
              buyer, Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID(), demo.properties());
      Instant now = Instant.now().plusSeconds(1);
      var claim = tx.execute(status -> store.claim(now));

      assertThat(worker(demo.gateway(), now.plusSeconds(31)).processNext()).isTrue();

      var reviewed = store.find(id).orElseThrow();
      assertThat(reviewed.status()).isEqualTo(Notification.Status.REVIEW_REQUIRED);
      assertThat(reviewed.leaseToken()).isNull();
      assertThat(reviewed.lastErrorCode()).isEqualTo("KAKAO_RESPONSE_UNKNOWN");
      assertThat(store.attempts(id, 0, 20).items())
          .singleElement()
          .satisfies(a -> assertThat(a.status()).isEqualTo("UNKNOWN"));
      assertThat(demo.messages()).isEmpty();
      var stale =
          tx.execute(
              status ->
                  store.finish(
                      claim.lease(),
                      Notification.Status.SENT,
                      null,
                      now,
                      "SUCCEEDED",
                      now.plusSeconds(32)));
      assertThat(stale).isNull();
      assertThat(store.find(id).orElseThrow()).isEqualTo(reviewed);
    }
  }

  @Test
  @DisplayName("실제 스키마에서 확인 대상·소진·확정 실패와 도래 작업 지연을 관측한다")
  void observesCanonicalNotificationSchemaWithoutCountingFutureWork() {
    UUID buyer = buyer();
    UUID exhausted = append(buyer, Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID());
    UUID action = append(buyer, Notification.Kind.REFUND_ACTION_REQUIRED, UUID.randomUUID());
    UUID due = append(buyer, Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID());
    UUID future = append(buyer, Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID());
    UUID active = append(buyer, Notification.Kind.SHIPMENT_STARTED, UUID.randomUUID());
    jdbc.update(
        "UPDATE commerce.notification_deliveries SET status='REVIEW_REQUIRED',retry_count=10 WHERE id=?",
        exhausted);
    jdbc.update(
        "UPDATE commerce.notification_deliveries SET status='REVIEW_REQUIRED' WHERE id=?", action);
    jdbc.update(
        "UPDATE commerce.notification_deliveries SET created_at=statement_timestamp()-interval '301 seconds' WHERE id=?",
        due);
    jdbc.update(
        "UPDATE commerce.notification_deliveries SET status='RETRY',created_at=statement_timestamp()-interval '999 seconds',next_run_at=statement_timestamp()+interval '10 minutes' WHERE id=?",
        future);
    jdbc.update(
        "UPDATE commerce.notification_deliveries SET status='SENDING',created_at=statement_timestamp()-interval '999 seconds',lease_token=?,lease_until=statement_timestamp()+interval '10 minutes' WHERE id=?",
        UUID.randomUUID(),
        active);
    var registry = new SimpleMeterRegistry();
    try (var metrics = new NotificationMetrics(dataSource, registry)) {
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(5))
          .untilAsserted(
              () -> {
                assertThat(registry.get("commerce.notification.collection.success").gauge().value())
                    .isEqualTo(1);
                assertThat(registry.get("commerce.notification.review.required").gauge().value())
                    .isEqualTo(2);
                assertThat(registry.get("commerce.notification.retry.exhausted").gauge().value())
                    .isEqualTo(1);
                assertThat(
                        registry
                            .get("commerce.notification.financial.action.recent")
                            .gauge()
                            .value())
                    .isEqualTo(1);
                assertThat(registry.get("commerce.notification.overdue.seconds").gauge().value())
                    .isBetween(300.0, 310.0);
              });
    } finally {
      registry.close();
    }
  }

  @Test
  @DisplayName("실제 판매 알림도 1분 집계를 유지하며 계정 변경이 기존 묶음과 중복 항목을 옮기지 않는다")
  void aggregatesSelfSalesWithinFrozenAccount() throws Exception {
    UUID seller = UUID.randomUUID();
    seller(seller);
    try (var demo =
        new KakaoDemoFixture(
            gateway,
            mapper,
            Clock.systemUTC(),
            demoDirectory,
            java.util.Set.of(),
            java.util.Set.of(seller))) {
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(8))
          .until(() -> Instant.now().atOffset(ZoneOffset.UTC).getSecond() < 53);
      UUID first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID();
      var original =
          new Notification.Destination(
              Notification.Channel.KAKAO_SELF, demo.properties().accountRef());
      var changed = new Notification.Destination(Notification.Channel.KAKAO_SELF, "b".repeat(64));
      selfSale(seller, first, 10000, original);
      selfSale(seller, second, 20000, original);
      selfSale(seller, third, 30000, changed);
      selfSale(seller, first, 10000, changed);

      var all = store.page(ActorKind.SELLER, seller, null, 0, 20).items();
      assertThat(all)
          .hasSize(2)
          .allSatisfy(n -> assertThat(n.status()).isEqualTo(Notification.Status.COLLECTING));
      var bucket =
          all.stream()
              .filter(n -> n.channelAccountRef().equals(original.accountRef()))
              .findFirst()
              .orElseThrow();
      assertThat(bucket.itemCount()).isEqualTo(2);
      assertThat(bucket.totalAmount()).isEqualTo(30000);
      assertThat(bucket.body()).isEmpty();
      assertThat(number("notification_items")).isEqualTo(3);
      assertThat(
              jdbc.queryForObject(
                  "SELECT delivery_id FROM commerce.notification_items WHERE business_ref=?",
                  UUID.class,
                  first))
          .isEqualTo(bucket.id());
      assertThat(worker(demo.gateway(), Instant.now()).processNext()).isFalse();
      assertThat(demo.messages()).isEmpty();
      Instant previous =
          Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).minusSeconds(60);
      jdbc.update(
          "UPDATE commerce.notification_deliveries SET bucket_start=?,bucket_end=?,next_run_at=? WHERE id=?",
          previous.atOffset(ZoneOffset.UTC),
          previous.plusSeconds(60).atOffset(ZoneOffset.UTC),
          previous.plusSeconds(60).atOffset(ZoneOffset.UTC),
          bucket.id());
      assertThat(worker(demo.gateway(), Instant.now().plusSeconds(1)).processNext()).isTrue();
      var sent = store.find(bucket.id()).orElseThrow();
      assertThat(sent.status()).isEqualTo(Notification.Status.SENT);
      assertThat(sent.channelAccountRef()).isEqualTo(original.accountRef());
      assertThat(sent.templateVersion()).isEqualTo(NotificationTemplates.SELF_VERSION);
      assertThat(demo.messages())
          .singleElement()
          .satisfies(
              body ->
                  assertThat(body)
                      .contains("[판매자]", "결제 완료 주문 2건", "합계 30,000원", "집계 상품")
                      .doesNotContain("잔액", "마일리지"));
      assertThat(number("notification_mock_receipts")).isZero();
    }
  }

  private void selfSale(
      UUID seller, UUID business, long amount, Notification.Destination destination) {
    var draft =
        new NotificationStore.Draft(
            "commerce",
            UUID.randomUUID(),
            Notification.Kind.SALE_COMPLETED,
            business,
            ActorKind.SELLER,
            seller,
            Instant.parse("2026-11-02T00:00:00Z"),
            mapper
                .createObjectNode()
                .put("orderId", business.toString())
                .put("productName", "집계 상품")
                .put("totalAmount", amount),
            destination);
    tx.executeWithoutResult(status -> store.append(draft));
  }

  private UUID appendSelf(
      UUID buyer, Notification.Kind kind, UUID business, KakaoSelfProperties properties) {
    var draft =
        new NotificationStore.Draft(
            "commerce",
            UUID.randomUUID(),
            kind,
            business,
            ActorKind.BUYER,
            buyer,
            Instant.now(),
            mapper
                .createObjectNode()
                .put("orderId", business.toString())
                .put("productName", "시연 상품")
                .put("totalAmount", 10000),
            new Notification.Destination(Notification.Channel.KAKAO_SELF, properties.accountRef()));
    tx.executeWithoutResult(status -> store.append(draft));
    return jdbc.queryForObject(
        "SELECT delivery_id FROM commerce.notification_items WHERE kind=? AND business_ref=?",
        UUID.class,
        kind.name(),
        business);
  }

  private List<JsonNode> logEntries(CapturedOutput output, String event, UUID deliveryId) {
    return output
        .getAll()
        .lines()
        .map(
            line -> {
              var node = mapper.createObjectNode();
              var fields =
                  java.util.regex.Pattern.compile("([a-zA-Z]+)=(?:\"([^\"]*)\"|(\\S+))")
                      .matcher(line);
              while (fields.find())
                node.put(
                    fields.group(1), fields.group(2) == null ? fields.group(3) : fields.group(2));
              var level =
                  java.util.regex.Pattern.compile("\\b(INFO|WARN|ERROR|DEBUG)\\b").matcher(line);
              if (level.find()) node.put("level", level.group(1));
              return (JsonNode) node;
            })
        .filter(
            row ->
                row.path("event").asText().equals(event)
                    && (deliveryId == null
                        || row.path("deliveryId").asText().equals(deliveryId.toString())))
        .toList();
  }

  private com.commercesettle.commerce.application.event.EventEnvelope<JsonNode> settlementNotice()
      throws Exception {
    UUID batch = UUID.randomUUID();
    var payload =
        mapper
            .createObjectNode()
            .put("kind", "SETTLEMENT_READY")
            .put("businessRef", batch.toString())
            .put("batchId", batch.toString())
            .put("sellerId", UUID.randomUUID().toString())
            .put("cutoffAt", "2026-11-02T00:00:00+09:00")
            .put("amount", 10000)
            .put("batchDate", "2026-11-01")
            .put("policyVersion", "test-v1");
    payload.putNull("payoutId").putNull("succeededAt").putNull("reasonCode");
    return new com.commercesettle.commerce.application.event.EventEnvelope<>(
        UUID.randomUUID(),
        "SettlementNoticeRequested",
        1,
        java.time.OffsetDateTime.now(ZoneOffset.UTC),
        UUID.randomUUID(),
        1,
        payload);
  }

  private NotificationStore.Draft draft(UUID buyer, Notification.Kind kind, UUID business) {
    return new NotificationStore.Draft(
        "commerce",
        UUID.randomUUID(),
        kind,
        business,
        ActorKind.BUYER,
        buyer,
        Instant.parse("2026-11-02T00:00:00Z"),
        mapper.createObjectNode().put("orderId", business.toString()));
  }

  private MockNotificationGateway mock(int temporary, int permanent, int loss) {
    return new MockNotificationGateway(
        receipts, Clock.systemUTC(), 67, 0, temporary, permanent, loss);
  }

  private NotificationDeliveryService worker(NotificationGateway source, Instant now) {
    return new NotificationDeliveryService(
        store, source, calls, Clock.fixed(now, ZoneOffset.UTC), manager);
  }

  private long number(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM commerce." + table, Long.class);
  }

  private void matches(String name, JsonNode body) throws Exception {
    var canonical = mapper.readTree(getClass().getResourceAsStream("/contracts/openapi.json"));
    var root = mapper.createObjectNode();
    root.set("components", canonical.required("components"));
    root.put("$ref", "#/components/schemas/" + name);
    var registry =
        com.networknt.schema.SchemaRegistry.withDefaultDialect(
            com.networknt.schema.SpecificationVersion.DRAFT_2020_12);
    var schema =
        registry.getSchema(
            com.networknt.schema.SchemaLocation.of("urn:notification:test:" + name),
            root.toString(),
            com.networknt.schema.InputFormat.JSON);
    assertThat(schema.validate(body.toString(), com.networknt.schema.InputFormat.JSON)).isEmpty();
  }

  private org.springframework.http.ResponseEntity<JsonNode> internal(
      String path, JsonNode body, String key) {
    var h = new HttpHeaders();
    h.setBearerAuth(OPERATOR);
    h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    if (key != null) h.set("Idempotency-Key", key);
    return http.exchange(
        path,
        body == null ? HttpMethod.GET : HttpMethod.POST,
        new HttpEntity<>(body, h),
        JsonNode.class);
  }

  private org.springframework.http.ResponseEntity<JsonNode> get(UUID buyer, String path) {
    var headers = headers(buyer);
    return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
  }

  private static HttpHeaders headers(UUID buyer) {
    var headers = new HttpHeaders();
    headers.setBearerAuth(GATEWAY);
    headers.set("X-Authenticated-Actor-Kind", "BUYER");
    headers.set("X-Authenticated-Actor-Id", buyer.toString());
    return headers;
  }

  private UUID buyer() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into commerce.buyer_accounts (id,email_normalized,password_hash,display_name,status,created_at,updated_at,version) values (?,?,'hash','구매자','ACTIVE',now(),now(),0)",
        id,
        id + "@example.com");
    return id;
  }

  private void seller(UUID id) {
    jdbc.update(
        "insert into commerce.seller_accounts (id,email_normalized,password_hash,display_name,status,payout_account_ref,created_at,updated_at,version) values (?,?,'hash','판매자','ACTIVE','test-payout',now(),now(),0)",
        id,
        id + "@example.com");
  }
}
