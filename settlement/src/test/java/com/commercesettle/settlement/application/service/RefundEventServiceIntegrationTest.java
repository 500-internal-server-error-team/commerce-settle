package com.commercesettle.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.event.RefundReservationRequestedPayload;
import com.commercesettle.settlement.application.port.InboxStore.Source;
import com.commercesettle.settlement.domain.model.SettlementAmounts;
import com.commercesettle.settlement.domain.model.SettlementItem;
import com.commercesettle.settlement.domain.repository.SettlementItemRepository;
import com.commercesettle.settlement.support.EventContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실제 PostgreSQL에서 환불 SAGA 요청의 Inbox 저장·판정·항목/예약 반영과 결과 Outbox를 확인한다. Kafka 경계는 Listener 통합 시험이 맡는다.
 */
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "spring.kafka.admin.auto-create=false",
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123",
      "app.internal-auth.senders[service\\:commerce].current=test-commerce-retention-credential-0123456789abcdef",
      "settlement.payout.mock-credential=test-settlement-credential-0123456789abcdef0123",
      "settlement.daily-batch.schedule-enabled=false",
      "settlement.payout-worker.enabled=false",
      "settlement.inbox-processor.enabled=false",
      // 대기 건을 바로 다시 판정할 수 있게 한다.
      "settlement.inbox-processor.interval-ms=0"
    })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RefundEventServiceIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  private static final long BASE = 10_000;
  private static final long FEE = BASE * 15 / 100;
  private static final long NET = BASE - FEE;

  @Autowired RefundEventService refunds;
  @Autowired SettlementEventService events;
  @Autowired SettlementItemRepository items;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired TransactionTemplate transactions;

  private final AtomicLong offsets = new AtomicLong();
  private UUID paymentId;

  @BeforeEach
  void setUp() {
    paymentId = UUID.randomUUID();
  }

  @AfterEach
  void deleteRows() {
    for (String table : List.of("inbox", "outbox", "settlement_items", "settlement_reservations")) {
      jdbc.update("DELETE FROM settlement." + table);
    }
  }

  @Test
  @DisplayName("구매확정 전 항목을 환불 예약하면 직전 상태·순액으로 ACTIVE 예약을 만들고 RESERVED를 결제 key로 발행한다")
  void reservesWaitingItem() throws Exception {
    SettlementItem item = item(false);
    var request = request(UUID.randomUUID(), NET);

    receive(request);

    Map<String, Object> reservation = onlyReservation();
    assertThat(reservation)
        .containsEntry("item_id", item.getId())
        .containsEntry("kind", "REFUND")
        .containsEntry("business_ref", request.payload().refundId())
        .containsEntry("amount", NET)
        .containsEntry("previous_item_status", "WAITING_CONFIRMATION")
        .containsEntry("status", "ACTIVE");
    assertThat(itemStatus()).isEqualTo("REFUND_RESERVED");
    assertInbox(request, "APPLIED", reservation.get("id"));
    JsonNode result = onlyResult();
    assertThat(result.path("payload").path("result").asText()).isEqualTo("RESERVED");
    assertThat(result.path("payload").path("reservationId").asText())
        .isEqualTo(reservation.get("id").toString());
    assertThat(result.path("payload").path("reasonCode").isNull()).isTrue();
    assertThat(result.path("aggregateId").asText()).isEqualTo(paymentId.toString());
    assertThat(result.path("aggregateVersion").asLong()).isEqualTo(itemVersion());
    assertThat(jdbc.queryForObject("SELECT message_key FROM settlement.outbox", String.class))
        .isEqualTo(paymentId.toString());
  }

  @Test
  @DisplayName("지급 가능 항목도 예약하고 직전 상태를 PAYABLE로 남긴다")
  void reservesPayableItem() {
    item(true);

    receive(request(UUID.randomUUID(), NET));

    assertThat(onlyReservation()).containsEntry("previous_item_status", "PAYABLE");
    assertThat(itemStatus()).isEqualTo("REFUND_RESERVED");
  }

  @Test
  @DisplayName("지급 배정·지급 완료·환불 완료·다른 환불 예약·금액 불일치는 상태를 바꾸지 않고 사유와 함께 REJECTED로 답한다")
  void rejectsWithReason() throws Exception {
    Map<String, String> cases =
        Map.of(
            "UPDATE settlement.settlement_items SET status = 'PAYOUT_RESERVED', payout_id = gen_random_uuid()",
                "PAYOUT_RESERVED",
            "UPDATE settlement.settlement_items SET status = 'PAID', payout_id = gen_random_uuid()",
                "PAYOUT_RESERVED",
            "UPDATE settlement.settlement_items SET status = 'REFUNDED'", "ALREADY_REFUNDED");
    for (var entry : cases.entrySet()) {
      item(true);
      jdbc.update(entry.getKey());
      String before = itemStatus();

      receive(request(UUID.randomUUID(), NET));

      assertThat(itemStatus()).isEqualTo(before);
      assertThat(onlyResult().path("payload").path("reasonCode").asText())
          .isEqualTo(entry.getValue());
      deleteRows();
      paymentId = UUID.randomUUID();
    }

    item(true);
    receive(request(UUID.randomUUID(), NET));
    jdbc.update("DELETE FROM settlement.outbox");
    receive(request(UUID.randomUUID(), NET));
    assertThat(onlyResult().path("payload").path("reasonCode").asText())
        .isEqualTo("ALREADY_REFUNDED");
    deleteRows();
    paymentId = UUID.randomUUID();

    item(true);
    var mismatch = request(UUID.randomUUID(), NET - 1);
    receive(mismatch);
    JsonNode result = onlyResult();
    assertThat(result.path("payload").path("result").asText()).isEqualTo("REJECTED");
    assertThat(result.path("payload").path("reasonCode").asText())
        .isEqualTo("REFUND_AMOUNT_MISMATCH");
    assertThat(result.path("payload").path("reservationId").isNull()).isTrue();
    assertThat(itemStatus()).isEqualTo("PAYABLE");
    assertThat(count("settlement_reservations")).isZero();
    assertInbox(mismatch, "APPLIED", itemId());
  }

  @Test
  @DisplayName("결제 원장이 없으면 대기하며 WAITING을 한 번만 알리고, 원장이 생기면 처리기가 RESERVED를 뒤 순서로 발행한다")
  void waitsForPaymentThenReserves() throws Exception {
    var request = request(UUID.randomUUID(), NET);
    receive(request);
    assertInbox(request, "WAITING", null);
    assertThat(events.processNextWaiting()).isTrue();
    assertThat(results())
        .extracting(r -> r.path("payload").path("result").asText())
        .containsExactly("WAITING");

    item(false);
    assertThat(events.processNextWaiting()).isTrue();

    List<JsonNode> results = results();
    assertThat(results)
        .extracting(r -> r.path("payload").path("result").asText())
        .containsExactly("WAITING", "RESERVED");
    assertThat(results.get(0).path("aggregateVersion").asLong())
        .isLessThan(results.get(1).path("aggregateVersion").asLong());
    assertThat(itemStatus()).isEqualTo("REFUND_RESERVED");
    assertInbox(request, "APPLIED", onlyReservation().get("id"));
  }

  @Test
  @DisplayName("결제 원장이 끝내 오지 않으면 10번째 판정에서 결과 없이 운영 확인으로 남긴다")
  void waitingStopsAfterMaxAttempts() throws Exception {
    var request = request(UUID.randomUUID(), NET);
    receive(request);
    while (events.processNextWaiting()) {}

    assertInbox(request, "REVIEW_REQUIRED", null);
    assertThat(results())
        .extracting(r -> r.path("payload").path("result").asText())
        .containsExactly("WAITING");
  }

  @Test
  @DisplayName("같은 환불의 재전달과 같은 사실의 다른 eventId는 결과를 다시 쓰지 않고, 금액이 다르면 운영 확인으로 남긴다")
  void sameRefundIsNotReservedTwice() throws Exception {
    item(true);
    UUID refundId = UUID.randomUUID();
    var first = request(refundId, NET);
    receive(first);
    receive(first);
    var sameFact = envelope(UUID.randomUUID(), refundId, NET);
    receive(sameFact);
    var different = envelope(UUID.randomUUID(), refundId, NET - 1);
    receive(different);

    assertThat(count("settlement_reservations")).isEqualTo(1);
    assertThat(results()).hasSize(1);
    assertInbox(sameFact, "APPLIED", onlyReservation().get("id"));
    assertInbox(different, "REVIEW_REQUIRED", null);
  }

  @Test
  @DisplayName("거절된 환불도 같은 refundId의 다른 금액 재요청은 예약하지 않고 운영 확인으로 남긴다")
  void rejectedRefundStillChecksLaterValues() throws Exception {
    item(true);
    UUID refundId = UUID.randomUUID();
    var rejected = request(refundId, NET - 1);
    receive(rejected);
    var changed = envelope(UUID.randomUUID(), refundId, NET);
    receive(changed);
    var sameAsRejected = envelope(UUID.randomUUID(), refundId, NET - 1);
    receive(sameAsRejected);

    assertThat(count("settlement_reservations")).isZero();
    assertThat(itemStatus()).isEqualTo("PAYABLE");
    assertThat(onlyResult().path("payload").path("reasonCode").asText())
        .isEqualTo("REFUND_AMOUNT_MISMATCH");
    assertInbox(changed, "REVIEW_REQUIRED", null);
    assertInbox(sameAsRejected, "APPLIED", itemId());
  }

  @Test
  @DisplayName("대기 처리기가 예약하는 동안 같은 환불의 새 요청이 와도 결과는 RESERVED 하나만 더해진다")
  void waitingProcessorAndNewRequestPublishOneResult() throws Exception {
    UUID refundId = UUID.randomUUID();
    var waiting = request(refundId, NET);
    receive(waiting);
    item(true);
    var concurrent = envelope(UUID.randomUUID(), refundId, NET);
    CountDownLatch reserved = new CountDownLatch(1);
    CountDownLatch commit = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      // 대기 처리기가 예약을 만들고 커밋 전에 멈춘 사이 새 수신이 같은 환불을 판정한다.
      Future<?> processor =
          executor.submit(
              () ->
                  transactions.executeWithoutResult(
                      status -> {
                        assertThat(events.processNextWaiting()).isTrue();
                        reserved.countDown();
                        await(commit);
                      }));
      assertThat(reserved.await(10, TimeUnit.SECONDS)).isTrue();
      Future<?> receiver = executor.submit(() -> receive(concurrent));
      awaitLockWaitOrDone(receiver);
      commit.countDown();
      processor.get(10, TimeUnit.SECONDS);
      receiver.get(10, TimeUnit.SECONDS);
    } finally {
      commit.countDown();
      executor.shutdownNow();
    }

    assertThat(results())
        .extracting(r -> r.path("payload").path("result").asText())
        .containsExactly("WAITING", "RESERVED");
    assertThat(count("settlement_reservations")).isEqualTo(1);
    assertInbox(waiting, "APPLIED", onlyReservation().get("id"));
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM settlement.inbox WHERE event_id = ?",
                String.class,
                concurrent.eventId()))
        .isEqualTo("APPLIED");
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** 작업이 행 잠금을 기다리기 시작하거나 기다리지 않고 끝날 때까지 기다린다. */
  private void awaitLockWaitOrDone(Future<?> task) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!task.isDone() && System.nanoTime() < deadline) {
      Long waiting =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity"
                  + " WHERE datname = current_database() AND wait_event_type = 'Lock'",
              Long.class);
      if (waiting != null && waiting > 0) {
        return;
      }
      Thread.sleep(10);
    }
  }

  private void receive(EventEnvelope<RefundReservationRequestedPayload> event) {
    try {
      refunds.receiveReservationRequested(
          event,
          new Source(
              "commerce-settle.payment.v1",
              0,
              offsets.getAndIncrement(),
              mapper.writeValueAsBytes(event)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private EventEnvelope<RefundReservationRequestedPayload> request(UUID refundId, long net) {
    return envelope(UUID.randomUUID(), refundId, net);
  }

  private EventEnvelope<RefundReservationRequestedPayload> envelope(
      UUID eventId, UUID refundId, long net) {
    return new EventEnvelope<>(
        eventId,
        "RefundReservationRequested",
        1,
        OffsetDateTime.parse("2026-11-02T10:00:00.123456+09:00"),
        refundId,
        0,
        new RefundReservationRequestedPayload(refundId, paymentId, net));
  }

  private SettlementItem item(boolean payable) {
    Instant at = Instant.parse("2026-10-20T03:00:00Z");
    SettlementItem item =
        SettlementItem.create(
            paymentId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            new SettlementAmounts(BASE, 0, BASE, BASE, 0, FEE, NET),
            0,
            at);
    if (payable) {
      item.markPayable(at, at, at);
    }
    return items.save(item);
  }

  private List<JsonNode> results() throws Exception {
    List<byte[]> bodies =
        jdbc.queryForList(
            "SELECT raw_body FROM settlement.outbox ORDER BY sequence_no", byte[].class);
    List<JsonNode> nodes = new java.util.ArrayList<>();
    for (byte[] body : bodies) {
      EventContract.assertValid(body);
      nodes.add(mapper.readTree(body));
    }
    return nodes;
  }

  private JsonNode onlyResult() throws Exception {
    List<JsonNode> results = results();
    assertThat(results).hasSize(1);
    assertThat(results.getFirst().path("eventType").asText()).isEqualTo("RefundReservationResult");
    return results.getFirst();
  }

  private Map<String, Object> onlyReservation() {
    return jdbc.queryForMap("SELECT * FROM settlement.settlement_reservations");
  }

  private String itemStatus() {
    return jdbc.queryForObject(
        "SELECT status FROM settlement.settlement_items WHERE payment_id = ?",
        String.class,
        paymentId);
  }

  private long itemVersion() {
    return jdbc.queryForObject(
        "SELECT version FROM settlement.settlement_items WHERE payment_id = ?",
        Long.class,
        paymentId);
  }

  private UUID itemId() {
    return jdbc.queryForObject(
        "SELECT id FROM settlement.settlement_items WHERE payment_id = ?", UUID.class, paymentId);
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM settlement." + table, Long.class);
  }

  private void assertInbox(EventEnvelope<?> event, String status, Object resultRef) {
    if ("REVIEW_REQUIRED".equals(status)) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT last_error_code FROM settlement.inbox WHERE event_id=?",
                  String.class,
                  event.eventId()))
          .isNotBlank();
    }
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status, result_ref FROM settlement.inbox WHERE event_id = ?", event.eventId());
    assertThat(row.get("status")).isEqualTo(status);
    assertThat(row.get("result_ref")).isEqualTo(resultRef);
  }
}
