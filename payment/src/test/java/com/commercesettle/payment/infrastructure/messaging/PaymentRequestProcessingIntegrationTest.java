package com.commercesettle.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import com.commercesettle.payment.application.service.ApplyOrderPaymentRequestService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 실제 커밋 경계에서 JDBC Inbox와 JPA 결제·마일리지 저장이 함께 커밋·롤백되는지 확인한다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PaymentRequestProcessingIntegrationTest.Config.class)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentRequestProcessingIntegrationTest {

  private static final Instant NOW = Instant.parse("2100-01-01T00:00:00Z");
  private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID CYCLE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a4");
  private static final UUID BUYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/payment.sql");

  @Autowired private ApplyOrderPaymentRequestService service;
  @Autowired private InboxStore inbox;
  @Autowired private ObjectMapper mapper;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager manager;

  @AfterEach
  void deleteRows() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM payment.mileage_entries");
    jdbc.update("DELETE FROM payment.mileage_reservations");
    jdbc.update("DELETE FROM payment.mileage_wallets");
    jdbc.update("DELETE FROM payment.payment_jobs");
    jdbc.update("DELETE FROM payment.payments");
    jdbc.update("DELETE FROM payment.inbox");
    jdbc.update("DELETE FROM payment.outbox");
  }

  @Test
  @DisplayName("WAITING 결제 요청을 처리하면 결제·승인 작업·APPLIED가 함께 커밋된다")
  void commitsPaymentJobAndAppliedInboxTogether() {
    saveWaiting();

    assertThat(service.processNext()).isTrue();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID paymentId =
        jdbc.queryForObject(
            "SELECT id FROM payment.payments WHERE order_id = ?", UUID.class, ORDER_ID);
    assertThat(
            jdbc.queryForObject(
                "SELECT business_key FROM payment.payment_jobs WHERE payment_id = ?",
                UUID.class,
                paymentId))
        .isEqualTo(CYCLE_ID);
    assertThat(jdbc.queryForObject("SELECT status FROM payment.inbox", String.class))
        .isEqualTo("APPLIED");
    assertThat(jdbc.queryForObject("SELECT result_ref FROM payment.inbox", UUID.class))
        .isEqualTo(paymentId);
    assertThat(
            jdbc.queryForMap(
                "SELECT event_type, aggregate_id, message_key, status FROM payment.outbox"))
        .containsEntry("event_type", "PaymentAccepted")
        .containsEntry("aggregate_id", paymentId)
        .containsEntry("message_key", paymentId.toString())
        .containsEntry("status", "PENDING");
    assertThat(service.processNext()).isFalse();
  }

  @Test
  @DisplayName("저장 중 실패하면 결제·작업·Inbox 변경이 모두 롤백되어 WAITING으로 남는다")
  void rollsBackEverythingWhenSavingFails() {
    saveWaiting();
    insertConflictingApprovalJob();

    assertThatThrownBy(service::processNext).isInstanceOf(RuntimeException.class);

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payments", Long.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.outbox", Long.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT status FROM payment.inbox", String.class))
        .isEqualTo("WAITING");
    assertThat(jdbc.queryForObject("SELECT result_ref IS NULL FROM payment.inbox", Boolean.class))
        .isTrue();
  }

  @Test
  @DisplayName("잔액 2,000점에 1,500점 결제 두 건을 동시에 처리하면 하나만 예약된다")
  void reservesOnlyOneOfConcurrentMileagePayments() throws Exception {
    fundWallet(2_000);
    saveWaiting(event(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 10_000, 1_500));
    saveWaiting(event(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 10_000, 1_500));
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        results.add(
            executor.submit(
                () -> {
                  start.await(10, TimeUnit.SECONDS);
                  return service.processNext();
                }));
      }
      start.countDown();
      for (Future<Boolean> result : results) {
        assertThat(result.get(30, TimeUnit.SECONDS)).isTrue();
      }
    } finally {
      executor.shutdownNow();
    }

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertThat(
            jdbc.queryForObject(
                "SELECT reserved_amount FROM payment.mileage_wallets WHERE buyer_id = ?",
                Long.class,
                BUYER_ID))
        .isEqualTo(1_500);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payments", Long.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payment.mileage_reservations WHERE status = 'RESERVED'",
                Long.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForList("SELECT status FROM payment.inbox ORDER BY status", String.class))
        .containsExactly("APPLIED", "REVIEW_REQUIRED");
  }

  @Test
  @DisplayName("마일리지 전액 결제는 차감·원장·확정이 함께 커밋된다")
  void confirmsMileageOnlyPaymentInDatabase() {
    fundWallet(12_000);
    saveWaiting(event(UUID.randomUUID(), ORDER_ID, CYCLE_ID, 10_000, 10_000));

    service.processNext();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM payment.payments WHERE order_id = ?", String.class, ORDER_ID))
        .isEqualTo("CONFIRMED");
    assertThat(
            jdbc.queryForMap(
                "SELECT balance, reserved_amount FROM payment.mileage_wallets WHERE buyer_id = ?",
                BUYER_ID))
        .containsEntry("balance", 2_000L)
        .containsEntry("reserved_amount", 0L);
    assertThat(jdbc.queryForObject("SELECT status FROM payment.mileage_reservations", String.class))
        .isEqualTo("CONSUMED");
    assertThat(
            jdbc.queryForList(
                "SELECT kind FROM payment.mileage_entries ORDER BY balance_after DESC",
                String.class))
        .containsExactly("RESERVE", "DEBIT");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payment_jobs", Long.class))
        .isZero();
  }

  @Test
  @DisplayName("마일리지 예약 뒤 저장이 실패하면 지갑·예약·원장도 함께 롤백된다")
  void rollsBackMileageWhenSavingFails() {
    fundWallet(5_000);
    saveWaiting(event(UUID.randomUUID(), ORDER_ID, CYCLE_ID, 10_000, 2_000));
    insertConflictingApprovalJob();

    assertThatThrownBy(service::processNext).isInstanceOf(RuntimeException.class);

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertThat(
            jdbc.queryForObject(
                "SELECT reserved_amount FROM payment.mileage_wallets WHERE buyer_id = ?",
                Long.class,
                BUYER_ID))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.mileage_reservations", Long.class))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.mileage_entries", Long.class))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT status FROM payment.inbox", String.class))
        .isEqualTo("WAITING");
  }

  private void fundWallet(long balance) {
    new JdbcTemplate(dataSource)
        .update(
            "INSERT INTO payment.mileage_wallets"
                + " (id, buyer_id, balance, reserved_amount, created_at, updated_at, version)"
                + " VALUES (?, ?, ?, 0, now(), now(), 0)",
            UUID.randomUUID(),
            BUYER_ID,
            balance);
  }

  private void insertConflictingApprovalJob() {
    // 같은 회차의 승인 작업을 미리 넣어 UNIQUE(operation, business_key) 위반을 일으킨다.
    new JdbcTemplate(dataSource)
        .update(
            "INSERT INTO payment.payment_jobs (id, payment_id, operation, business_key, status,"
                + " attempts, next_run_at, created_at, updated_at, version)"
                + " VALUES (?, ?, 'APPROVE', ?, 'PENDING', 0, now(), now(), now(), 0)",
            UUID.randomUUID(),
            UUID.randomUUID(),
            CYCLE_ID);
  }

  private void saveWaiting() {
    saveWaiting(cardPaymentEvent());
  }

  private void saveWaiting(String json) {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    try {
      EventEnvelope<OrderPaymentRequestedPayload> event =
          mapper.readValue(body, new TypeReference<>() {});
      new TransactionTemplate(manager)
          .executeWithoutResult(
              status ->
                  inbox.saveWaiting(event, new Source("commerce-settle.commerce.v1", 0, 0L, body)));
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  /** 쿠폰 없는 결제 요청. 외부 결제액이 0원이면 결제수단을 비운다. */
  private static String event(UUID eventId, UUID orderId, UUID cycleId, long gross, long mileage) {
    long external = gross - mileage;
    String method = external > 0 ? "\"CREDIT_CARD\"" : "null";
    String account = external > 0 ? "\"test-card\"" : "null";
    return """
        {
          "eventId": "%s",
          "eventType": "OrderPaymentRequested",
          "eventVersion": 1,
          "occurredAt": "2026-01-01T00:00:00Z",
          "aggregateId": "%s",
          "aggregateVersion": 0,
          "payload": {
            "orderId": "%s",
            "buyerId": "%s",
            "sellerId": "00000000-0000-0000-0000-0000000000a3",
            "paymentCycleId": "%s",
            "productId": "00000000-0000-0000-0000-0000000000a5",
            "orderVersion": 0,
            "status": "PAYMENT_PENDING",
            "policyVersion": "test-v1",
            "paymentWaitUntil": "2026-01-01T00:10:00Z",
            "externalMethod": %s,
            "methodAccountRef": %s,
            "grossAmount": %d,
            "couponAmount": 0,
            "totalAmount": %d,
            "mileageAmount": %d,
            "externalAmount": %d,
            "sellerCouponAmount": 0,
            "platformCouponAmount": 0,
            "settlementBaseAmount": %d
          }
        }
        """
        .formatted(
            eventId, orderId, orderId, BUYER_ID, cycleId, method, account, gross, gross, mileage,
            external, gross);
  }

  private static String cardPaymentEvent() {
    return """
        {
          "eventId": "00000000-0000-0000-0000-0000000000a6",
          "eventType": "OrderPaymentRequested",
          "eventVersion": 1,
          "occurredAt": "2026-01-01T00:00:00Z",
          "aggregateId": "%1$s",
          "aggregateVersion": 0,
          "payload": {
            "orderId": "%1$s",
            "buyerId": "00000000-0000-0000-0000-0000000000a2",
            "sellerId": "00000000-0000-0000-0000-0000000000a3",
            "paymentCycleId": "%2$s",
            "productId": "00000000-0000-0000-0000-0000000000a5",
            "orderVersion": 0,
            "status": "PAYMENT_PENDING",
            "policyVersion": "test-v1",
            "paymentWaitUntil": "2026-01-01T00:10:00Z",
            "externalMethod": "CREDIT_CARD",
            "methodAccountRef": "test-card",
            "grossAmount": 23000,
            "couponAmount": 3000,
            "totalAmount": 20000,
            "mileageAmount": 0,
            "externalAmount": 20000,
            "sellerCouponAmount": 0,
            "platformCouponAmount": 3000,
            "settlementBaseAmount": 23000
          }
        }
        """
        .formatted(ORDER_ID, CYCLE_ID);
  }

  @TestConfiguration
  // 같은 패키지의 다른 테스트 설정을 읽지 않도록 저장소 구현만 등록한다.
  @ComponentScan(
      basePackages = "com.commercesettle.payment.infrastructure.persistence",
      useDefaultFilters = false,
      includeFilters = @ComponentScan.Filter(Repository.class))
  @Import(ApplyOrderPaymentRequestService.class)
  static class Config {
    @Bean
    NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
      return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}
