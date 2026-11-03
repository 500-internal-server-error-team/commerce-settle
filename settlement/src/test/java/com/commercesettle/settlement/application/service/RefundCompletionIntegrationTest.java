package com.commercesettle.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.event.PaymentConfirmedPayload;
import com.commercesettle.settlement.application.event.RefundFinancialCompletedPayload;
import com.commercesettle.settlement.application.event.RefundReleaseRequestedPayload;
import com.commercesettle.settlement.application.event.RefundReservationRequestedPayload;
import com.commercesettle.settlement.application.port.InboxStore.Source;
import com.commercesettle.settlement.domain.repository.SettlementItemRepository;
import com.commercesettle.settlement.support.EventContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL에서 환불 예약 뒤의 원장 상쇄와 결과 발행을 확인한다. 결제는 실제 결제확정 경로로 기록해 최초 원장 5 leg를 만든다. */
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "spring.kafka.admin.auto-create=false",
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123",
      "app.internal-auth.senders[service\\:commerce].current=test-commerce-retention-credential-0123456789abcdef",
      "settlement.payout.mock-credential=test-settlement-credential-0123456789abcdef0123",
      "settlement.daily-batch.schedule-enabled=false",
      "settlement.payout-worker.enabled=false",
      "settlement.inbox-processor.enabled=false"
    })
@Testcontainers
class RefundCompletionIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  // 외부 7,000원 + 마일리지 3,000원, 플랫폼 쿠폰 0원(0원 leg).
  private static final long EXTERNAL = 7_000;
  private static final long MILEAGE = 3_000;
  private static final long NET = 8_500;

  @Autowired RefundEventService refunds;
  @Autowired SettlementEventService events;
  @Autowired SettlementItemRepository items;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  private final AtomicLong offsets = new AtomicLong();
  private UUID paymentId;
  private UUID orderId;
  private UUID sellerId;
  private UUID productId;
  private EventEnvelope<PaymentConfirmedPayload> payment;

  @BeforeEach
  void recordPayment() {
    paymentId = UUID.randomUUID();
    orderId = UUID.randomUUID();
    sellerId = UUID.randomUUID();
    productId = UUID.randomUUID();
    payment = payment(UUID.randomUUID());
    events.receivePaymentConfirmed(payment, source(payment));
  }

  @AfterEach
  void deleteRows() {
    for (String table :
        List.of(
            "inbox",
            "outbox",
            "settlement_items",
            "settlement_reservations",
            "settlement_entries")) {
      jdbc.update("DELETE FROM settlement." + table);
    }
  }

  @Test
  @DisplayName("환불 완료면 최초 원장 5 leg마다 반대 부호 줄을 만들고 항목 REFUNDED·예약 CONSUMED와 상쇄 결과를 발행한다")
  void reversesLedger() throws Exception {
    UUID refundId = UUID.randomUUID();
    receive(reservation(refundId));
    UUID reservationId = reservationId();
    var completed = completed(refundId, EXTERNAL, MILEAGE);

    receive(completed);

    List<Map<String, Object>> reversals =
        jdbc.queryForList(
            "SELECT r.leg, r.amount, r.business_ref, r.policy_version, r.source_event_id,"
                + " o.amount AS original FROM settlement.settlement_entries r"
                + " JOIN settlement.settlement_entries o ON o.id = r.reversal_of");
    assertThat(reversals).hasSize(5);
    assertThat(reversals)
        .allSatisfy(
            row -> {
              assertThat((Long) row.get("amount")).isEqualTo(-(Long) row.get("original"));
              assertThat(row.get("business_ref")).isEqualTo(refundId);
              assertThat(row.get("policy_version")).isEqualTo("v1");
              assertThat(row.get("source_event_id")).isEqualTo(completed.eventId());
            });
    assertThat(reversals)
        .extracting(row -> row.get("leg") + ":" + row.get("amount"))
        .containsExactlyInAnyOrder(
            "EXTERNAL:-7000", "MILEAGE:-3000", "PLATFORM_COUPON:0", "SELLER:-8500", "FEE:-1500");
    assertThat(
            jdbc.queryForList(
                "SELECT side, sum(amount) AS total FROM settlement.settlement_entries"
                    + " GROUP BY side"))
        .hasSize(2)
        .allSatisfy(row -> assertThat(((Number) row.get("total")).longValue()).isZero());
    assertThat(itemStatus()).isEqualTo("REFUNDED");
    assertThat(reservationStatus()).isEqualTo("CONSUMED");
    JsonNode reversed = lastEvent("RefundLedgerReversed");
    assertThat(reversed.path("payload").path("reservationId").asText())
        .isEqualTo(reservationId.toString());
    assertThat(reversed.path("payload").path("refundId").asText()).isEqualTo(refundId.toString());
    assertThat(reversed.path("aggregateVersion").asLong()).isEqualTo(itemVersion());
  }

  @Test
  @DisplayName("상쇄 결과 재전달과 같은 사실의 다른 eventId는 원장·발행을 늘리지 않는다")
  void reversalIsIdempotent() {
    UUID refundId = UUID.randomUUID();
    receive(reservation(refundId));
    var completed = completed(refundId, EXTERNAL, MILEAGE);
    receive(completed);
    long entries = count("settlement_entries");
    long published = count("outbox");

    receive(completed);
    var sameFact = completed(refundId, EXTERNAL, MILEAGE);
    receive(sameFact);

    assertThat(count("settlement_entries")).isEqualTo(entries);
    assertThat(count("outbox")).isEqualTo(published);
    assertInbox(sameFact, "APPLIED");
  }

  @Test
  @DisplayName("예약 없는 환불 완료와 금액이 원거래와 다른 환불 완료는 상쇄 없이 운영 확인으로 남긴다")
  void unexpectedCompletionRequiresReview() {
    var withoutReservation = completed(UUID.randomUUID(), EXTERNAL, MILEAGE);
    receive(withoutReservation);
    UUID refundId = UUID.randomUUID();
    receive(reservation(refundId));
    var mismatch = completed(refundId, EXTERNAL - 1, MILEAGE + 1);
    receive(mismatch);

    assertInbox(withoutReservation, "REVIEW_REQUIRED");
    assertInbox(mismatch, "REVIEW_REQUIRED");
    assertThat(count("settlement_entries")).isEqualTo(5);
    assertThat(itemStatus()).isEqualTo("REFUND_RESERVED");
    assertThat(reservationStatus()).isEqualTo("ACTIVE");
  }

  @Test
  @DisplayName("상쇄 뒤에도 같은 결제의 같은 사실 결제확정은 최초 원장 정책 버전으로 판정해 APPLIED다")
  void duplicatePaymentAfterReversalKeepsPolicyCheck() {
    UUID refundId = UUID.randomUUID();
    receive(reservation(refundId));
    receive(completed(refundId, EXTERNAL, MILEAGE));

    var duplicate = payment(UUID.randomUUID());
    events.receivePaymentConfirmed(duplicate, source(duplicate));

    assertInbox(duplicate, "APPLIED");
    assertThat(count("settlement_entries")).isEqualTo(10);
  }

  @Test
  @DisplayName("예약 해제 요청이면 항목을 구매확정 전 상태로 되돌리고 예약 RELEASED와 해제 결과를 발행하며, 재전달은 무변경이다")
  void releasesToWaitingConfirmation() throws Exception {
    UUID refundId = UUID.randomUUID();
    receive(reservation(refundId));
    UUID reservationId = reservationId();
    var release = release(refundId, reservationId);

    receive(release);
    long published = count("outbox");
    receive(release);
    receive(release(refundId, reservationId));

    assertThat(itemStatus()).isEqualTo("WAITING_CONFIRMATION");
    assertThat(reservationStatus()).isEqualTo("RELEASED");
    assertThat(count("outbox")).isEqualTo(published);
    JsonNode released = lastEvent("RefundReservationReleased");
    assertThat(released.path("payload").path("reservationId").asText())
        .isEqualTo(reservationId.toString());
    assertThat(released.path("aggregateVersion").asLong()).isEqualTo(itemVersion());
  }

  @Test
  @DisplayName("지급 가능 항목의 예약을 풀면 PAYABLE로 돌아가고, 같은 항목에 지급 선점과 새 환불 예약이 다시 가능하다")
  void releasedPayableItemCanBeReservedAgain() {
    jdbc.update(
        "UPDATE settlement.settlement_items SET status = 'PAYABLE',"
            + " purchase_confirmed_at = now() - interval '2 days',"
            + " eligible_received_at = now() - interval '2 days',"
            + " ready_at = now() - interval '2 days' WHERE payment_id = ?",
        paymentId);
    UUID refundId = UUID.randomUUID();
    receive(reservation(refundId));
    receive(release(refundId, reservationId()));
    assertThat(itemStatus()).isEqualTo("PAYABLE");

    UUID next = UUID.randomUUID();
    receive(reservation(next));
    assertThat(itemStatus()).isEqualTo("REFUND_RESERVED");
    receive(release(next, activeReservationId()));

    assertThat(items.reserveForPayout(sellerId, Instant.now(), UUID.randomUUID(), Instant.now()))
        .hasSize(1);
    assertThat(itemStatus()).isEqualTo("PAYOUT_RESERVED");
  }

  @Test
  @DisplayName("상쇄가 끝난 예약과 다른 환불·다른 결제의 해제 요청은 운영 확인으로 남기고 상태를 바꾸지 않는다")
  void unexpectedReleaseRequiresReview() {
    UUID refundId = UUID.randomUUID();
    receive(reservation(refundId));
    UUID reservationId = reservationId();
    var otherRefund = release(UUID.randomUUID(), reservationId);
    receive(otherRefund);
    assertInbox(otherRefund, "REVIEW_REQUIRED");
    assertThat(reservationStatus()).isEqualTo("ACTIVE");

    receive(completed(refundId, EXTERNAL, MILEAGE));
    var afterReversal = release(refundId, reservationId);
    receive(afterReversal);

    assertInbox(afterReversal, "REVIEW_REQUIRED");
    assertThat(itemStatus()).isEqualTo("REFUNDED");
    assertThat(reservationStatus()).isEqualTo("CONSUMED");
  }

  private void receive(EventEnvelope<?> event) {
    switch (event.payload()) {
      case RefundReservationRequestedPayload request ->
          refunds.receiveReservationRequested(cast(event), source(event));
      case RefundFinancialCompletedPayload completed ->
          refunds.receiveFinancialCompleted(cast(event), source(event));
      case RefundReleaseRequestedPayload release ->
          refunds.receiveReleaseRequested(cast(event), source(event));
      default -> throw new IllegalArgumentException("unexpected event");
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> EventEnvelope<T> cast(EventEnvelope<?> event) {
    return (EventEnvelope<T>) event;
  }

  private EventEnvelope<RefundReleaseRequestedPayload> release(UUID refundId, UUID reservationId) {
    return envelope(
        "RefundReleaseRequested",
        refundId,
        new RefundReleaseRequestedPayload(refundId, paymentId, reservationId));
  }

  private UUID activeReservationId() {
    return jdbc.queryForObject(
        "SELECT id FROM settlement.settlement_reservations WHERE status = 'ACTIVE'", UUID.class);
  }

  private EventEnvelope<RefundReservationRequestedPayload> reservation(UUID refundId) {
    return envelope(
        "RefundReservationRequested",
        refundId,
        new RefundReservationRequestedPayload(refundId, paymentId, NET));
  }

  private EventEnvelope<RefundFinancialCompletedPayload> completed(
      UUID refundId, long external, long mileage) {
    return envelope(
        "RefundFinancialCompleted",
        refundId,
        new RefundFinancialCompletedPayload(
            refundId,
            paymentId,
            external,
            mileage,
            OffsetDateTime.parse("2026-11-02T11:00:00+09:00")));
  }

  private EventEnvelope<PaymentConfirmedPayload> payment(UUID eventId) {
    return new EventEnvelope<>(
        eventId,
        "PaymentConfirmed",
        1,
        OffsetDateTime.parse("2026-10-01T12:00:01.123456+09:00"),
        paymentId,
        2,
        new PaymentConfirmedPayload(
            10_000,
            0,
            10_000,
            MILEAGE,
            EXTERNAL,
            paymentId,
            orderId,
            UUID.randomUUID(),
            sellerId,
            3,
            UUID.randomUUID(),
            productId,
            1_500,
            NET,
            OffsetDateTime.parse("2026-10-01T12:00:00+09:00"),
            "v1",
            0,
            0,
            10_000));
  }

  private static <T> EventEnvelope<T> envelope(String type, UUID aggregateId, T payload) {
    return new EventEnvelope<>(
        UUID.randomUUID(),
        type,
        1,
        OffsetDateTime.parse("2026-11-02T10:00:00.123456+09:00"),
        aggregateId,
        0,
        payload);
  }

  private Source source(EventEnvelope<?> event) {
    try {
      return new Source(
          "commerce-settle.payment.v1",
          0,
          offsets.getAndIncrement(),
          mapper.writeValueAsBytes(event));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode lastEvent(String type) throws Exception {
    List<JsonNode> events = new ArrayList<>();
    for (byte[] body :
        jdbc.queryForList(
            "SELECT raw_body FROM settlement.outbox ORDER BY sequence_no", byte[].class)) {
      EventContract.assertValid(body);
      events.add(mapper.readTree(body));
    }
    assertThat(events).isNotEmpty();
    JsonNode last = events.getLast();
    assertThat(last.path("eventType").asText()).isEqualTo(type);
    return last;
  }

  private UUID reservationId() {
    return jdbc.queryForObject(
        "SELECT id FROM settlement.settlement_reservations WHERE kind = 'REFUND'", UUID.class);
  }

  private String reservationStatus() {
    return jdbc.queryForObject(
        "SELECT status FROM settlement.settlement_reservations WHERE kind = 'REFUND'",
        String.class);
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

  private long count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM settlement." + table, Long.class);
  }

  private void assertInbox(EventEnvelope<?> event, String status) {
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM settlement.inbox WHERE event_id = ?",
                String.class,
                event.eventId()))
        .isEqualTo(status);
  }
}
