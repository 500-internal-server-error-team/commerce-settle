package com.commercesettle.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.event.PaymentConfirmedPayload;
import com.commercesettle.settlement.application.event.PurchaseConfirmedPayload;
import com.commercesettle.settlement.application.port.InboxStore;
import com.commercesettle.settlement.application.port.InboxStore.Source;
import com.commercesettle.settlement.domain.model.SettlementBatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL에서 결제확정·구매확정의 Inbox 저장·판정·반영을 확인한다. Kafka 경계는 Listener 통합 시험이 맡는다. */
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123",
      "settlement.payout.mock-credential=test-settlement-credential-0123456789abcdef0123",
      "spring.kafka.admin.auto-create=false",
      "settlement.daily-batch.schedule-enabled=false",
      "settlement.payout-worker.enabled=false",
      "settlement.inbox-processor.enabled=false",
      // 대기 건을 바로 다시 판정할 수 있게 한다.
      "settlement.inbox-processor.interval-ms=0"
    })
@Testcontainers
class SettlementEventServiceIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  private static final Instant NOW = Instant.parse("2026-10-20T03:00:00Z");
  private static final OffsetDateTime DELIVERED_AT =
      OffsetDateTime.parse("2026-10-10T12:00:00+09:00");
  private static final OffsetDateTime CONFIRMED_AT = DELIVERED_AT.plusHours(168).plusMinutes(5);

  @Autowired SettlementEventService service;
  @Autowired SellerSettlementService sellers;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired MutableClock clock;
  @Autowired InboxStore inbox;
  @Autowired TransactionTemplate transactions;

  private final AtomicLong offsets = new AtomicLong();
  private UUID paymentId;
  private UUID orderId;
  private UUID sellerId;

  @BeforeEach
  void setUp() {
    clock.set(NOW);
    paymentId = UUID.randomUUID();
    orderId = UUID.randomUUID();
    sellerId = UUID.randomUUID();
  }

  @AfterEach
  void deleteRows() {
    for (String table :
        List.of(
            "inbox",
            "inbox_conflicts",
            "settlement_entries",
            "settlement_items",
            "settlement_batches",
            "payouts",
            "payout_jobs",
            "settlement_reservations")) {
      jdbc.update("DELETE FROM settlement." + table);
    }
  }

  @Test
  @DisplayName("결제확정 한 건으로 항목 1건과 원장 5건이 생기고 각 측 합계가 B다")
  void paymentConfirmedCreatesItemAndLedger() {
    // G=12,000 S=2,000 P=1,000 → B=10,000, T=9,000(E=9,000 M=0), 수수료 1,500
    var event = payment(UUID.randomUUID(), 12_000, 2_000, 1_000, 9_000, 0);

    service.receivePaymentConfirmed(event, source("commerce-settle.payment.v1", event));

    Map<String, Object> item = item();
    assertThat(item.get("status")).isEqualTo("WAITING_CONFIRMATION");
    assertThat(item.get("settlement_base_amount")).isEqualTo(10_000L);
    assertThat(item.get("net_amount")).isEqualTo(8_500L);
    assertThat(
            jdbc.queryForList(
                "SELECT leg, amount FROM settlement.settlement_entries WHERE payment_id = ? ORDER BY leg",
                paymentId))
        .extracting(row -> row.get("leg") + "=" + row.get("amount"))
        .containsExactly(
            "EXTERNAL=9000", "FEE=1500", "MILEAGE=0", "PLATFORM_COUPON=1000", "SELLER=8500");
    assertThat(sideSum("SOURCE")).isEqualTo(10_000);
    assertThat(sideSum("ALLOCATION")).isEqualTo(10_000);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(DISTINCT source_event_id) FROM settlement.settlement_entries"
                    + " WHERE payment_id = ? AND source_event_id = ?",
                Long.class,
                paymentId,
                event.eventId()))
        .isEqualTo(1);
    assertInbox(event.eventId(), "APPLIED", item.get("id"));
  }

  @Test
  @DisplayName("같은 bytes 재전달은 결과를 바꾸지 않고, 같은 eventId의 다른 bytes는 충돌로 격리한다")
  void redeliveryAndBodyConflict() throws Exception {
    var event = payment(UUID.randomUUID(), 10_000, 0, 0, 10_000, 0);
    Source first = source("commerce-settle.payment.v1", event);
    service.receivePaymentConfirmed(event, first);

    service.receivePaymentConfirmed(event, first);
    var tampered = withPolicy(event, "v2");
    service.receivePaymentConfirmed(tampered, source("commerce-settle.payment.v1", tampered));

    assertThat(count("settlement_items")).isEqualTo(1);
    assertThat(count("settlement_entries")).isEqualTo(5);
    assertThat(count("inbox")).isEqualTo(1);
    assertThat(count("inbox_conflicts")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT DISTINCT policy_version FROM settlement.settlement_entries", String.class))
        .isEqualTo("v1");
    assertInbox(event.eventId(), "APPLIED", item().get("id"));
  }

  @Test
  @DisplayName("금액 계약이 맞지 않으면 항목·원장 없이 운영 확인으로 남긴다")
  void amountMismatchRequiresReview() {
    var valid = payment(UUID.randomUUID(), 10_000, 0, 0, 10_000, 0);
    PaymentConfirmedPayload p = valid.payload();
    var wrongFee =
        envelope(
            valid.eventId(),
            "PaymentConfirmed",
            paymentId,
            new PaymentConfirmedPayload(
                p.grossAmount(),
                p.couponAmount(),
                p.totalAmount(),
                p.mileageAmount(),
                p.externalAmount(),
                p.paymentId(),
                p.orderId(),
                p.buyerId(),
                p.sellerId(),
                p.orderVersion(),
                p.paymentCycleId(),
                p.productId(),
                1_000,
                9_000,
                p.confirmedAt(),
                p.policyVersion(),
                p.sellerCouponAmount(),
                p.platformCouponAmount(),
                p.settlementBaseAmount()));

    service.receivePaymentConfirmed(wrongFee, source("commerce-settle.payment.v1", wrongFee));

    assertThat(count("settlement_items")).isZero();
    assertThat(count("settlement_entries")).isZero();
    assertInbox(wrongFee.eventId(), "REVIEW_REQUIRED", null);
  }

  @Test
  @DisplayName("같은 결제의 다른 eventId는 값이 같으면 기존 결과를 유지하고, 다르면 운영 확인으로 남긴다")
  void otherEventIdForSamePayment() {
    var original = payment(UUID.randomUUID(), 10_000, 0, 0, 10_000, 0);
    service.receivePaymentConfirmed(original, source("commerce-settle.payment.v1", original));
    Object itemId = item().get("id");

    var sameFact = payment(UUID.randomUUID(), 10_000, 0, 0, 10_000, 0);
    service.receivePaymentConfirmed(sameFact, source("commerce-settle.payment.v1", sameFact));
    var otherAmount = payment(UUID.randomUUID(), 20_000, 0, 0, 20_000, 0);
    service.receivePaymentConfirmed(otherAmount, source("commerce-settle.payment.v1", otherAmount));

    assertInbox(sameFact.eventId(), "APPLIED", itemId);
    assertInbox(otherAmount.eventId(), "REVIEW_REQUIRED", null);
    assertThat(count("settlement_items")).isEqualTo(1);
    assertThat(count("settlement_entries")).isEqualTo(5);
    assertThat(item().get("settlement_base_amount")).isEqualTo(10_000L);
  }

  @Test
  @DisplayName("같은 결제의 다른 eventId가 정책 버전만 다르면 운영 확인으로 남기고 원장은 그대로다")
  void otherPolicyVersionForSamePayment() {
    var original = payment(UUID.randomUUID(), 10_000, 0, 0, 10_000, 0);
    service.receivePaymentConfirmed(original, source("commerce-settle.payment.v1", original));

    var otherPolicy = withPolicy(payment(UUID.randomUUID(), 10_000, 0, 0, 10_000, 0), "v2");
    service.receivePaymentConfirmed(otherPolicy, source("commerce-settle.payment.v1", otherPolicy));

    assertInbox(otherPolicy.eventId(), "REVIEW_REQUIRED", null);
    assertThat(count("settlement_entries")).isEqualTo(5);
    assertThat(
            jdbc.queryForObject(
                "SELECT DISTINCT policy_version FROM settlement.settlement_entries", String.class))
        .isEqualTo("v1");
  }

  @Test
  @DisplayName("환불 마감 정각의 구매확정은 운영 확인으로 남기고 항목을 PAYABLE로 바꾸지 않는다")
  void purchaseAtRefundDeadlineRequiresReview() {
    var payment = payment(UUID.randomUUID(), 10_000, 0, 0, 10_000, 0);
    service.receivePaymentConfirmed(payment, source("commerce-settle.payment.v1", payment));

    var atDeadline = purchase(UUID.randomUUID(), orderId, DELIVERED_AT.plusHours(168));
    service.receivePurchaseConfirmed(atDeadline, source("commerce-settle.commerce.v1", atDeadline));

    assertInbox(atDeadline.eventId(), "REVIEW_REQUIRED", null);
    assertThat(item().get("status")).isEqualTo("WAITING_CONFIRMATION");
  }

  @Test
  @DisplayName("구매확정이 먼저 오면 대기하고, 결제확정 뒤 처리기가 PAYABLE로 바꾼다")
  void purchaseBeforePaymentWaitsThenBecomesPayable() {
    var purchase = purchase(UUID.randomUUID(), orderId, CONFIRMED_AT);
    service.receivePurchaseConfirmed(purchase, source("commerce-settle.commerce.v1", purchase));
    assertInbox(purchase.eventId(), "WAITING", null);
    assertThat(service.processNextWaiting()).isTrue();
    assertInbox(purchase.eventId(), "WAITING", null);

    var payment = payment(UUID.randomUUID(), 10_000, 0, 0, 10_000, 0);
    service.receivePaymentConfirmed(payment, source("commerce-settle.payment.v1", payment));
    Instant eligible = NOW.plusSeconds(1);
    clock.set(eligible);
    assertThat(service.processNextWaiting()).isTrue();

    Map<String, Object> item = item();
    assertThat(item.get("status")).isEqualTo("PAYABLE");
    assertThat(instant(item, "purchase_confirmed_at")).isEqualTo(CONFIRMED_AT.toInstant());
    assertThat(instant(item, "eligible_received_at")).isEqualTo(eligible);
    assertThat(instant(item, "ready_at")).isEqualTo(eligible);
    assertInbox(purchase.eventId(), "APPLIED", item.get("id"));
    assertThat(service.processNextWaiting()).isFalse();
  }

  @Test
  @DisplayName("결제확정이 오지 않으면 10번째 판정에서 대기를 멈추고 운영 확인으로 남긴다")
  void waitingPurchaseStopsAfterMaxAttempts() {
    var purchase = purchase(UUID.randomUUID(), orderId, CONFIRMED_AT);
    service.receivePurchaseConfirmed(purchase, source("commerce-settle.commerce.v1", purchase));
    for (int attempt = 2; attempt < 10; attempt++) {
      assertThat(service.processNextWaiting()).isTrue();
    }
    assertInbox(purchase.eventId(), "WAITING", null);
    assertThat(attempts(purchase.eventId())).isEqualTo(9);

    assertThat(service.processNextWaiting()).isTrue();

    assertInbox(purchase.eventId(), "REVIEW_REQUIRED", null);
    assertThat(attempts(purchase.eventId())).isEqualTo(10);
    assertThat(
            jdbc.queryForObject(
                "SELECT last_error_code FROM settlement.inbox WHERE event_id = ?",
                String.class,
                purchase.eventId()))
        .isEqualTo("PAYMENT_NOT_ARRIVED");
    assertThat(service.processNextWaiting()).isFalse();
  }

  @Test
  @DisplayName("대기 간격은 기준 1초에서 두 배씩 늘어 60초에서 멈춘다")
  void waitingDelayGrowsExponentially() {
    var purchase = purchase(UUID.randomUUID(), orderId, CONFIRMED_AT);
    service.receivePurchaseConfirmed(purchase, source("commerce-settle.commerce.v1", purchase));
    UUID inboxId =
        jdbc.queryForObject(
            "SELECT id FROM settlement.inbox WHERE event_id = ?", UUID.class, purchase.eventId());

    List<Long> delays = new ArrayList<>();
    for (int attempt = 2; attempt <= 8; attempt++) {
      transactions.executeWithoutResult(
          status -> inbox.markWaiting(inboxId, Duration.ofSeconds(1), 10, NOW));
      delays.add(
          jdbc.queryForObject(
              "SELECT round(extract(epoch FROM next_run_at - clock_timestamp()))::bigint"
                  + " FROM settlement.inbox WHERE id = ?",
              Long.class,
              inboxId));
    }

    assertThat(delays).containsExactly(2L, 4L, 8L, 16L, 32L, 60L, 60L);
  }

  @Test
  @DisplayName("결제확정 뒤 구매확정은 바로 PAYABLE이 되고, 다른 주문의 구매확정은 운영 확인으로 남긴다")
  void purchaseAfterPayment() {
    var payment = payment(UUID.randomUUID(), 10_000, 0, 0, 10_000, 0);
    service.receivePaymentConfirmed(payment, source("commerce-settle.payment.v1", payment));
    clock.set(CONFIRMED_AT.toInstant().minusSeconds(86_400));

    var otherOrder = purchase(UUID.randomUUID(), UUID.randomUUID(), CONFIRMED_AT);
    service.receivePurchaseConfirmed(otherOrder, source("commerce-settle.commerce.v1", otherOrder));
    var purchase = purchase(UUID.randomUUID(), orderId, CONFIRMED_AT);
    service.receivePurchaseConfirmed(purchase, source("commerce-settle.commerce.v1", purchase));

    assertInbox(otherOrder.eventId(), "REVIEW_REQUIRED", null);
    Map<String, Object> item = item();
    assertThat(item.get("status")).isEqualTo("PAYABLE");
    // 구매확정 시각이 원장 준비 시각보다 늦으면 readyAt은 구매확정 시각이다.
    assertThat(instant(item, "ready_at")).isEqualTo(CONFIRMED_AT.toInstant());
    assertInbox(purchase.eventId(), "APPLIED", item.get("id"));
  }

  @Test
  @DisplayName("이벤트로 만든 PAYABLE 항목을 일배치가 대상으로 고른다")
  void dailyBatchSelectsEventMadeItem() {
    var payment = payment(UUID.randomUUID(), 10_000, 0, 0, 10_000, 0);
    service.receivePaymentConfirmed(payment, source("commerce-settle.payment.v1", payment));
    var purchase = purchase(UUID.randomUUID(), orderId, CONFIRMED_AT);
    service.receivePurchaseConfirmed(purchase, source("commerce-settle.commerce.v1", purchase));

    LocalDate batchDate = LocalDate.ofInstant(NOW, ZoneId.of("Asia/Seoul")).plusDays(1);
    clock.set(SettlementBatch.cutoffOf(batchDate).plusSeconds(1));
    var batch = sellers.settle(sellerId, batchDate).orElseThrow();

    assertThat(batch.getNetAmount()).isEqualTo(8_500);
    assertThat(item().get("status")).isEqualTo("PAYOUT_RESERVED");
  }

  private EventEnvelope<PaymentConfirmedPayload> payment(
      UUID eventId, long g, long s, long p, long t, long m) {
    long b = t + p;
    long fee = b * 15 / 100;
    return envelope(
        eventId,
        "PaymentConfirmed",
        paymentId,
        new PaymentConfirmedPayload(
            g,
            s + p,
            t,
            m,
            t - m,
            paymentId,
            orderId,
            UUID.randomUUID(),
            sellerId,
            3,
            UUID.randomUUID(),
            PRODUCT_ID,
            fee,
            b - fee,
            OffsetDateTime.parse("2026-10-01T12:00:00+09:00"),
            "v1",
            s,
            p,
            b));
  }

  private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

  private EventEnvelope<PurchaseConfirmedPayload> purchase(
      UUID eventId, UUID order, OffsetDateTime confirmedAt) {
    return envelope(
        eventId,
        "PurchaseConfirmed",
        order,
        new PurchaseConfirmedPayload(
            paymentId, order, 4, confirmedAt, DELIVERED_AT, DELIVERED_AT.plusHours(168)));
  }

  private static <T> EventEnvelope<T> envelope(
      UUID eventId, String type, UUID aggregateId, T payload) {
    return new EventEnvelope<>(
        eventId,
        type,
        1,
        OffsetDateTime.parse("2026-10-01T12:00:01.123456+09:00"),
        aggregateId,
        2,
        payload);
  }

  private static EventEnvelope<PaymentConfirmedPayload> withPolicy(
      EventEnvelope<PaymentConfirmedPayload> event, String policyVersion) {
    PaymentConfirmedPayload p = event.payload();
    return new EventEnvelope<>(
        event.eventId(),
        event.eventType(),
        event.eventVersion(),
        event.occurredAt(),
        event.aggregateId(),
        event.aggregateVersion(),
        new PaymentConfirmedPayload(
            p.grossAmount(),
            p.couponAmount(),
            p.totalAmount(),
            p.mileageAmount(),
            p.externalAmount(),
            p.paymentId(),
            p.orderId(),
            p.buyerId(),
            p.sellerId(),
            p.orderVersion(),
            p.paymentCycleId(),
            p.productId(),
            p.feeAmount(),
            p.sellerNetAmount(),
            p.confirmedAt(),
            policyVersion,
            p.sellerCouponAmount(),
            p.platformCouponAmount(),
            p.settlementBaseAmount()));
  }

  private Source source(String topic, EventEnvelope<?> event) {
    try {
      return new Source(topic, 0, offsets.getAndIncrement(), mapper.writeValueAsBytes(event));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Map<String, Object> item() {
    return jdbc.queryForMap(
        "SELECT * FROM settlement.settlement_items WHERE payment_id = ?", paymentId);
  }

  private long sideSum(String side) {
    return jdbc.queryForObject(
        "SELECT sum(amount) FROM settlement.settlement_entries WHERE payment_id = ? AND side = ?",
        Long.class,
        paymentId,
        side);
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM settlement." + table, Long.class);
  }

  private void assertInbox(UUID eventId, String status, Object resultRef) {
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status, result_ref FROM settlement.inbox WHERE event_id = ?", eventId);
    assertThat(row.get("status")).isEqualTo(status);
    assertThat(row.get("result_ref")).isEqualTo(resultRef);
  }

  private int attempts(UUID eventId) {
    return jdbc.queryForObject(
        "SELECT attempts FROM settlement.inbox WHERE event_id = ?", Integer.class, eventId);
  }

  private static Instant instant(Map<String, Object> row, String column) {
    return ((Timestamp) row.get(column)).toInstant();
  }

  @TestConfiguration
  static class ClockConfig {
    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock();
    }
  }

  /** 원장 준비 시각과 일배치 마감을 시험에서 정한다. */
  static class MutableClock extends Clock {
    private volatile Instant now = NOW;

    void set(Instant now) {
      this.now = now;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
