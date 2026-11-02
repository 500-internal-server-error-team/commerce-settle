package com.commercesettle.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.event.PaymentCancellationRequestedPayload;
import com.commercesettle.payment.application.port.InboxClaim;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import com.commercesettle.payment.application.port.WaitingInboxEvent;
import com.commercesettle.payment.application.service.ApplyOrderPaymentRequestService;
import com.commercesettle.payment.application.service.RefundEventHandler;
import com.commercesettle.payment.application.service.RefundEvents;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
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
  @Autowired private MovableClock clock;
  @Autowired private HookedTransactions transactions;

  @AfterEach
  void deleteRows() {
    clock.set(NOW);
    transactions.clear();
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
  @DisplayName("저장 중 실패하면 결제·작업 변경은 롤백되고 실패 1회와 1초 뒤 재시도만 커밋된다")
  void rollsBackEverythingWhenSavingFails() {
    saveWaiting();
    insertConflictingApprovalJob();

    assertThat(service.processNext()).isTrue();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payments", Long.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.outbox", Long.class)).isZero();
    assertThat(
            jdbc.queryForMap(
                "SELECT status, attempts, last_error_code, result_ref,"
                    + " next_run_at = ? AS retry_due FROM payment.inbox",
                Timestamp.from(NOW.plusSeconds(1))))
        .containsEntry("status", "WAITING")
        .containsEntry("attempts", 1)
        .containsEntry("last_error_code", "PAYMENT_REQUEST_PROCESSING_FAILED")
        .containsEntry("result_ref", null)
        .containsEntry("retry_due", true);
  }

  @Test
  @DisplayName("계속 실패하는 요청은 1·2·4…60초 간격으로 재시도하다 10번째 실패에 REVIEW_REQUIRED가 된다")
  void marksReviewAfterTenFailuresWithGrowingDelay() {
    saveWaiting();
    insertConflictingApprovalJob();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    List<Long> delays = new ArrayList<>();

    for (int attempt = 1; attempt <= 10; attempt++) {
      assertThat(service.processNext()).isTrue();
      Timestamp next =
          jdbc.queryForObject("SELECT next_run_at FROM payment.inbox", Timestamp.class);
      if (next != null) {
        delays.add(Duration.between(clock.instant(), next.toInstant()).toSeconds());
        clock.set(next.toInstant());
      }
    }

    assertThat(delays).containsExactly(1L, 2L, 4L, 8L, 16L, 32L, 60L, 60L, 60L);
    assertThat(jdbc.queryForMap("SELECT status, attempts, last_error_code FROM payment.inbox"))
        .containsEntry("status", "REVIEW_REQUIRED")
        .containsEntry("attempts", 10)
        .containsEntry("last_error_code", "PAYMENT_REQUEST_PROCESSING_FAILED");
    assertThat(service.processNext()).isFalse();
  }

  @Test
  @DisplayName("실패한 요청이 앞에 있어도 같은 주기에 뒤의 요청을 처리한다")
  void processesLaterRequestAfterFailedOne() {
    saveWaiting();
    insertConflictingApprovalJob();
    UUID laterOrderId = UUID.randomUUID();
    saveWaiting(event(UUID.randomUUID(), laterOrderId, UUID.randomUUID(), 10_000, 0));

    new PaymentRequestProcessor(service, 10).run();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payment.payments WHERE order_id = ?",
                Long.class,
                laterOrderId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForList(
                "SELECT status || ':' || attempts FROM payment.inbox ORDER BY status",
                String.class))
        .containsExactly("APPLIED:1", "WAITING:1");
  }

  @Test
  @DisplayName("확인 대상 전환도 처리 건수에 포함하고, 배치가 차면 이어서 남은 요청까지 처리한다")
  void countsReviewTransitionsTowardBatchSize() {
    List<UUID> exhausted = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      UUID eventId = UUID.randomUUID();
      saveWaiting(event(eventId, UUID.randomUUID(), UUID.randomUUID(), 10_000, 0));
      exhausted.add(eventId);
    }
    UUID normalOrderId = UUID.randomUUID();
    saveWaiting(event(UUID.randomUUID(), normalOrderId, UUID.randomUUID(), 10_000, 0));
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    for (UUID eventId : exhausted) {
      // 10번 시도한 뒤 결과를 남기지 못한 행. 정상 요청보다 먼저 선점되게 처리 시각을 앞당긴다.
      jdbc.update(
          "UPDATE payment.inbox SET attempts = 10, next_run_at = '2000-01-01T00:00:00Z'"
              + " WHERE event_id = ?",
          eventId);
    }
    PaymentRequestProcessor processor = new PaymentRequestProcessor(service, 2);

    // 첫 배치 2건은 모두 확인 대상 전환이다. 배치가 찼으므로 다음 배치에서 남은 확인 대상과 정상 요청을 처리한다.
    processor.run();

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payment.inbox WHERE status = 'REVIEW_REQUIRED'"
                    + " AND last_error_code = 'PAYMENT_REQUEST_ATTEMPTS_EXHAUSTED'",
                Long.class))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payment.payments WHERE order_id = ?",
                Long.class,
                normalOrderId))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("처리가 롤백된 직후 실패 기록 전에 다른 처리기가 돌아도 같은 요청을 다시 잡지 않고 실패가 1회로 기록된다")
  void otherProcessorCannotClaimBetweenRollbackAndFailureRecord() {
    saveWaiting();
    insertConflictingApprovalJob();
    AtomicReference<Boolean> otherProcessed = new AtomicReference<>();
    transactions.afterNextRollback(() -> otherProcessed.set(service.processNext()));

    assertThat(service.processNext()).isTrue();

    assertThat(otherProcessed.get()).isFalse();
    assertThat(
            new JdbcTemplate(dataSource)
                .queryForMap(
                    "SELECT status, attempts, last_error_code, next_run_at = ? AS retry_due"
                        + " FROM payment.inbox",
                    Timestamp.from(NOW.plusSeconds(1))))
        .containsEntry("status", "WAITING")
        .containsEntry("attempts", 1)
        .containsEntry("last_error_code", "PAYMENT_REQUEST_PROCESSING_FAILED")
        .containsEntry("retry_due", true);
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

    assertThat(service.processNext()).isTrue();

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

  @Test
  @DisplayName("승인 전 혼합 결제를 취소하면 결제 취소·예약 해제·승인 작업 종료·PaymentCancelled가 함께 커밋된다")
  void cancelsPendingMixedPaymentInDatabase() {
    fundWallet(5_000);
    saveWaiting(event(UUID.randomUUID(), ORDER_ID, CYCLE_ID, 10_000, 2_000));
    service.processNext();
    saveCancellation(ORDER_ID, CYCLE_ID);

    assertThat(service.processNext()).isTrue();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertThat(jdbc.queryForObject("SELECT status FROM payment.payments", String.class))
        .isEqualTo("CANCELLED");
    assertThat(jdbc.queryForObject("SELECT status FROM payment.mileage_reservations", String.class))
        .isEqualTo("RELEASED");
    assertThat(
            jdbc.queryForMap(
                "SELECT balance, reserved_amount FROM payment.mileage_wallets WHERE buyer_id = ?",
                BUYER_ID))
        .containsEntry("balance", 5_000L)
        .containsEntry("reserved_amount", 0L);
    assertThat(
            jdbc.queryForList(
                "SELECT kind FROM payment.mileage_entries ORDER BY created_at, kind", String.class))
        .containsExactlyInAnyOrder("RESERVE", "RELEASE");
    assertThat(jdbc.queryForObject("SELECT status FROM payment.payment_jobs", String.class))
        .isEqualTo("DONE");
    assertThat(
            jdbc.queryForList(
                "SELECT event_type || ':' || aggregate_version FROM payment.outbox"
                    + " ORDER BY sequence_no",
                String.class))
        .containsExactly("PaymentAccepted:0", "PaymentCancelled:1");
    assertThat(jdbc.queryForList("SELECT DISTINCT status FROM payment.inbox", String.class))
        .containsExactly("APPLIED");
  }

  @Test
  @DisplayName("취소가 결제 요청보다 먼저 처리되면 늦은 결제 요청은 취소된 결제를 만들고 두 수신 기록을 그 결제에 연결한다")
  void cancelsLateRequestAfterEarlyCancellationInDatabase() {
    fundWallet(5_000);
    saveCancellation(ORDER_ID, CYCLE_ID);
    service.processNext();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payments", Long.class)).isZero();

    saveWaiting(event(UUID.randomUUID(), ORDER_ID, CYCLE_ID, 10_000, 2_000));
    service.processNext();

    UUID paymentId =
        jdbc.queryForObject(
            "SELECT id FROM payment.payments WHERE status = 'CANCELLED'", UUID.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payment_jobs", Long.class))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.mileage_reservations", Long.class))
        .isZero();
    assertThat(jdbc.queryForList("SELECT result_ref FROM payment.inbox", UUID.class))
        .containsExactly(paymentId, paymentId);
    assertThat(
            jdbc.queryForList(
                "SELECT event_type FROM payment.outbox ORDER BY sequence_no", String.class))
        .containsExactly("PaymentAccepted", "PaymentCancelled");
  }

  @Test
  @DisplayName("승인 Worker가 먼저 PROCESSING으로 바꾼 결제는 취소하지 않고 마일리지 예약도 유지한다")
  void rejectsCancellationAfterApprovalStartedInDatabase() {
    fundWallet(5_000);
    saveWaiting(event(UUID.randomUUID(), ORDER_ID, CYCLE_ID, 10_000, 2_000));
    service.processNext();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    // 승인 Worker가 외부 호출 전에 결제를 PROCESSING으로 바꾼 상태.
    jdbc.update("UPDATE payment.payments SET status = 'PROCESSING', version = version + 1");
    saveCancellation(ORDER_ID, CYCLE_ID);

    service.processNext();

    assertThat(jdbc.queryForObject("SELECT status FROM payment.payments", String.class))
        .isEqualTo("PROCESSING");
    assertThat(jdbc.queryForObject("SELECT status FROM payment.mileage_reservations", String.class))
        .isEqualTo("RESERVED");
    assertThat(
            jdbc.queryForList(
                "SELECT event_type || ':' || (payload -> 'payload' ->> 'reasonCode')"
                    + " FROM payment.outbox WHERE event_type <> 'PaymentAccepted'",
                String.class))
        .containsExactly("PaymentCancellationRejected:PAYMENT_IN_PROGRESS");
  }

  @Test
  @DisplayName("결제 요청이 마일리지 부족으로 확인 대상이 된 뒤 온 취소는 취소된 결제를 만들고 PaymentCancelled를 커밋한다")
  void cancelsOrderWhosePaymentRequestEndedInReviewInDatabase() {
    fundWallet(1_000);
    saveWaiting(event(UUID.randomUUID(), ORDER_ID, CYCLE_ID, 10_000, 2_000));
    service.processNext();
    saveCancellation(ORDER_ID, CYCLE_ID);

    service.processNext();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID paymentId = jdbc.queryForObject("SELECT id FROM payment.payments", UUID.class);
    assertThat(jdbc.queryForObject("SELECT status FROM payment.payments", String.class))
        .isEqualTo("CANCELLED");
    assertThat(
            jdbc.queryForList(
                "SELECT event_type || ':' || status || ':' || coalesce(result_ref::text, '-')"
                    + " FROM payment.inbox ORDER BY event_type",
                String.class))
        .containsExactly(
            "OrderPaymentRequested:REVIEW_REQUIRED:" + paymentId,
            "PaymentCancellationRequested:APPLIED:" + paymentId);
    assertThat(
            jdbc.queryForList(
                "SELECT event_type FROM payment.outbox ORDER BY sequence_no", String.class))
        .containsExactly("PaymentAccepted", "PaymentCancelled");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.mileage_reservations", Long.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT last_error_code FROM payment.inbox WHERE event_type = 'OrderPaymentRequested'",
                String.class))
        .isEqualTo("PAYMENT_REQUEST_MILEAGE_INSUFFICIENT");
    assertThat(
            new TransactionTemplate(manager)
                .<Optional<OrderPaymentRequestedPayload>>execute(
                    status -> inbox.findOriginalRequest(paymentId)))
        .hasValueSatisfying(
            request -> assertThat(request.methodAccountRef()).isEqualTo("test-card"));

    UUID replay = UUID.randomUUID();
    saveWaiting(event(replay, ORDER_ID, CYCLE_ID, 10_000, 2_000));
    service.processEvent(replay);
    UUID conflict = UUID.randomUUID();
    saveWaiting(
        event(conflict, ORDER_ID, CYCLE_ID, 10_000, 2_000)
            .replace("\"policyVersion\": \"test-v1\"", "\"policyVersion\": \"test-v2\""));
    service.processEvent(conflict);

    assertThat(
            jdbc.queryForObject(
                "SELECT result_ref FROM payment.inbox WHERE event_id = ?", UUID.class, replay))
        .isEqualTo(paymentId);
    assertThat(
            jdbc.queryForObject(
                "SELECT last_error_code FROM payment.inbox WHERE event_id = ?",
                String.class,
                conflict))
        .isEqualTo("PAYMENT_REQUEST_ORDER_CONFLICT");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payments", Long.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.outbox", Long.class)).isEqualTo(2);
  }

  @Test
  @DisplayName("같은 주문의 결제 요청과 취소를 두 처리기가 동시에 처리해도 승인 가능한 결제가 남지 않고 결제가 취소된다")
  void serializesConcurrentRequestAndCancellationOfSameOrder() throws Exception {
    List<UUID> orders = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      UUID orderId = UUID.randomUUID();
      UUID cycleId = UUID.randomUUID();
      saveWaiting(event(UUID.randomUUID(), orderId, cycleId, 10_000, 0));
      saveCancellation(orderId, cycleId);
      orders.add(orderId);
    }
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> workers = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        workers.add(
            executor.submit(
                () -> {
                  start.await(10, TimeUnit.SECONDS);
                  while (service.processNext()) {
                    // 처리할 행이 없을 때까지 계속 처리한다.
                  }
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> worker : workers) {
        worker.get(60, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }
    // 잠금 대기 상한으로 재시도 대기에 들어간 요청이 있으면 처리 시각을 지나 마저 처리한다.
    clock.set(NOW.plusSeconds(120));
    while (service.processNext()) {
      // 남은 재시도를 처리한다.
    }

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertThat(
            jdbc.queryForList(
                "SELECT order_id FROM payment.payments WHERE status = 'CANCELLED'", UUID.class))
        .containsExactlyInAnyOrderElementsOf(orders);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payment.payment_jobs WHERE status <> 'DONE'", Long.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payment.outbox WHERE event_type = 'PaymentCancelled'",
                Long.class))
        .isEqualTo(orders.size());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payment.inbox WHERE status <> 'APPLIED'", Long.class))
        .isZero();
    // 주문 단위로 직렬화되므로 같은 주문의 결제를 동시에 만들다 실패해 재시도한 요청이 없다.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payment.inbox WHERE attempts > 1 OR last_error_code IS NOT NULL",
                Long.class))
        .isZero();
  }

  @Test
  @DisplayName("다른 처리기가 선점한 원문도 취소가 먼저 적용되면 같은 트랜잭션에서 연결·완료하고 재활성화하지 않는다")
  void cancellationCompletesAlreadyClaimedRequest() throws Exception {
    UUID requestId = UUID.randomUUID();
    saveWaiting(event(requestId, ORDER_ID, CYCLE_ID, 10_000, 0));
    saveCancellation(ORDER_ID, CYCLE_ID);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID cancellationId =
        jdbc.queryForObject(
            "SELECT event_id FROM payment.inbox WHERE event_type = 'PaymentCancellationRequested'",
            UUID.class);
    var claimed = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    transactions.afterClaim(
        requestId,
        () -> {
          claimed.countDown();
          awaitRelease(resume);
        });
    var executor = Executors.newSingleThreadExecutor();
    try {
      Future<Boolean> original = executor.submit(() -> service.processEvent(requestId));
      assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();

      assertThat(service.processEvent(cancellationId)).isTrue();

      UUID paymentId =
          jdbc.queryForObject(
              "SELECT id FROM payment.payments WHERE status = 'CANCELLED'", UUID.class);
      assertThat(jdbc.queryForList("SELECT result_ref FROM payment.inbox", UUID.class))
          .containsExactly(paymentId, paymentId);
      assertThat(jdbc.queryForList("SELECT DISTINCT status FROM payment.inbox", String.class))
          .containsExactly("APPLIED");
      assertThat(
              new TransactionTemplate(manager)
                  .<Optional<OrderPaymentRequestedPayload>>execute(
                      status -> inbox.findOriginalRequest(paymentId)))
          .isPresent();
      resume.countDown();
      assertThat(original.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payment_jobs", Long.class))
          .isZero();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.outbox", Long.class))
          .isEqualTo(2);
    } finally {
      resume.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("주문 잠금 대기가 상한을 넘으면 업무 변경은 롤백되고 원문은 같은 키로 재시도된다")
  void boundsOrderLockWaitAndRetriesWithoutBusinessChanges() throws Exception {
    UUID requestId = UUID.randomUUID();
    saveWaiting(event(requestId, ORDER_ID, CYCLE_ID, 10_000, 0));
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    Future<?> holder =
        executor.submit(
            () ->
                new TransactionTemplate(manager)
                    .executeWithoutResult(
                        status -> {
                          new JdbcTemplate(dataSource)
                              .queryForList(
                                  "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                                  "payment-order:" + ORDER_ID);
                          held.countDown();
                          awaitRelease(release);
                        }));
    try {
      assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
      long started = System.nanoTime();

      assertThat(service.processEvent(requestId)).isTrue();

      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payments", Long.class)).isZero();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.outbox", Long.class)).isZero();
      assertThat(jdbc.queryForMap("SELECT status, attempts, last_error_code FROM payment.inbox"))
          .containsEntry("status", "WAITING")
          .containsEntry("attempts", 1)
          .containsEntry("last_error_code", "PAYMENT_REQUEST_PROCESSING_FAILED");
    } finally {
      release.countDown();
      holder.get(10, TimeUnit.SECONDS);
      executor.shutdownNow();
    }
    clock.set(NOW.plusSeconds(2));
    service.processEvent(requestId);
    assertThat(
            new JdbcTemplate(dataSource)
                .queryForObject("SELECT status FROM payment.inbox", String.class))
        .isEqualTo("APPLIED");
  }

  private static void awaitRelease(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS))
        throw new IllegalStateException("Test barrier timed out");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  @Test
  @DisplayName("취소 처리의 커밋이 실패하면 원문 연결·결제·Outbox가 함께 롤백되고 같은 요청으로 복구된다")
  void rollsBackCancellationSourceLinkWithBusinessChanges() {
    fundWallet(1_000);
    saveWaiting(event(UUID.randomUUID(), ORDER_ID, CYCLE_ID, 10_000, 2_000));
    service.processNext();
    saveCancellation(ORDER_ID, CYCLE_ID);
    transactions.beforeNextProcessingCommit(
        () -> {
          throw new IllegalStateException("simulated processing rollback");
        });

    service.processNext();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payments", Long.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.outbox", Long.class)).isZero();
    assertThat(
            jdbc.queryForMap(
                "SELECT status, result_ref, last_error_code FROM payment.inbox WHERE event_type = 'OrderPaymentRequested'"))
        .containsEntry("status", "REVIEW_REQUIRED")
        .containsEntry("result_ref", null)
        .containsEntry("last_error_code", "PAYMENT_REQUEST_MILEAGE_INSUFFICIENT");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM payment.inbox WHERE event_type = 'PaymentCancellationRequested'",
                String.class))
        .isEqualTo("WAITING");
    clock.set(NOW.plusSeconds(2));

    service.processNext();

    assertThat(jdbc.queryForObject("SELECT status FROM payment.payments", String.class))
        .isEqualTo("CANCELLED");
    assertThat(
            jdbc.queryForObject("SELECT count(DISTINCT result_ref) FROM payment.inbox", Long.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.outbox", Long.class)).isEqualTo(2);
  }

  @Test
  @DisplayName("누적 Inbox에서도 상태와 관계없는 원문 연결 및 주문·회차 조회는 인덱스 조건으로 탐색한다")
  void keepsRequestLookupsIndexedAcrossStatuses() throws Exception {
    fundWallet(1_000);
    saveWaiting(event(UUID.randomUUID(), ORDER_ID, CYCLE_ID, 10_000, 2_000));
    service.processNext();
    saveCancellation(ORDER_ID, CYCLE_ID);
    service.processNext();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID paymentId = jdbc.queryForObject("SELECT id FROM payment.payments", UUID.class);
    // 조회 계획용 합성 이력. Envelope와 SHA를 보존하되 금융 작업으로 처리하지 않는다.
    jdbc.update(
        """
        WITH template AS (SELECT payload FROM payment.inbox WHERE event_type = 'OrderPaymentRequested' LIMIT 1),
        identities AS (SELECT gen_random_uuid() AS event_id, gen_random_uuid() AS order_id,
            gen_random_uuid() AS cycle_id, gen_random_uuid() AS result_id, n FROM generate_series(1, 10000) n),
        bodies AS (SELECT identities.*, jsonb_set(payload || jsonb_build_object('eventId', event_id::text,
            'aggregateId', order_id::text), '{payload}', payload -> 'payload' ||
            jsonb_build_object('orderId', order_id::text, 'paymentCycleId', cycle_id::text)) AS body
            FROM identities CROSS JOIN template)
        INSERT INTO payment.inbox (id, consumer, event_id, event_type, event_version, occurred_at,
            payload, raw_body, body_hash, source_topic, source_partition, source_offset,
            aggregate_id, aggregate_version, status, result_ref, last_error_code)
        SELECT gen_random_uuid(), 'payment.v1', event_id, 'OrderPaymentRequested', 1,
            (body ->> 'occurredAt')::timestamptz, body, convert_to(body::text, 'UTF8'),
            encode(sha256(convert_to(body::text, 'UTF8')), 'hex'), 'commerce-settle.fixture.plan.v1', 0, n,
            order_id, 0, CASE WHEN n % 2 = 0 THEN 'APPLIED' ELSE 'REVIEW_REQUIRED' END,
            result_id, CASE WHEN n % 2 = 0 THEN NULL ELSE 'FIXTURE_REVIEW' END FROM bodies
        """);
    jdbc.execute("ANALYZE payment.inbox");
    UUID appliedId =
        jdbc.queryForObject(
            "SELECT result_ref FROM payment.inbox WHERE status = 'APPLIED' AND event_type = 'OrderPaymentRequested' LIMIT 1",
            UUID.class);
    for (UUID result : List.of(paymentId, appliedId)) {
      String plan =
          jdbc.queryForObject(
              """
          EXPLAIN (FORMAT JSON) SELECT id, raw_body FROM payment.inbox
          WHERE consumer = 'payment.v1' AND result_ref = ? AND event_type = 'OrderPaymentRequested'
          ORDER BY created_at, id LIMIT 1
          """,
              String.class,
              result);
      assertThat(mapper.readTree(plan).findValuesAsText("Index Cond"))
          .withFailMessage("Result lookup lost its key index: %s", plan)
          .anySatisfy(condition -> assertThat(condition).contains("result_ref"));
    }
    String plan =
        jdbc.queryForObject(
            """
        EXPLAIN (FORMAT JSON) SELECT id, raw_body FROM payment.inbox
        WHERE consumer = 'payment.v1' AND event_type = 'OrderPaymentRequested'
          AND aggregate_id = ? AND payload -> 'payload' ->> 'paymentCycleId' = ?
        ORDER BY created_at, id LIMIT 1
        """,
            String.class,
            ORDER_ID,
            CYCLE_ID.toString());
    assertThat(mapper.readTree(plan).findValuesAsText("Index Cond"))
        .withFailMessage("Order/cycle lookup lost its key index: %s", plan)
        .anySatisfy(condition -> assertThat(condition).contains("aggregate_id", "paymentCycleId"));
  }

  private void saveCancellation(UUID orderId, UUID cycleId) {
    String json =
        """
        {
          "eventId": "%s",
          "eventType": "PaymentCancellationRequested",
          "eventVersion": 1,
          "occurredAt": "2026-01-01T00:05:00Z",
          "aggregateId": "%s",
          "aggregateVersion": 1,
          "payload": {
            "orderId": "%s",
            "orderVersion": 1,
            "paymentCycleId": "%s",
            "requestId": "%s"
          }
        }
        """
            .formatted(UUID.randomUUID(), orderId, orderId, cycleId, UUID.randomUUID());
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    try {
      EventEnvelope<PaymentCancellationRequestedPayload> event =
          mapper.readValue(body, new TypeReference<>() {});
      new TransactionTemplate(manager)
          .executeWithoutResult(
              status ->
                  inbox.saveWaiting(event, new Source("commerce-settle.commerce.v1", 0, 1L, body)));
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
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
  @Import({ApplyOrderPaymentRequestService.class, RefundEventHandler.class, RefundEvents.class})
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
    MovableClock clock() {
      return new MovableClock();
    }

    @Bean
    HookedTransactions transactions(PlatformTransactionManager manager) {
      return new HookedTransactions(new TransactionTemplate(manager));
    }
  }

  /** 처리 트랜잭션이 롤백된 직후, 실패를 기록하기 전에 다른 처리기를 끼워 넣어 순서를 고정한다. */
  static final class HookedTransactions implements TransactionOperations {
    private final TransactionTemplate delegate;
    private final AtomicReference<Runnable> afterRollback = new AtomicReference<>();
    private final AtomicReference<ClaimHook> afterClaim = new AtomicReference<>();
    private final AtomicReference<Runnable> beforeProcessingCommit = new AtomicReference<>();

    HookedTransactions(TransactionTemplate delegate) {
      this.delegate = delegate;
    }

    void afterNextRollback(Runnable hook) {
      afterRollback.set(hook);
    }

    void afterClaim(UUID eventId, Runnable action) {
      afterClaim.set(new ClaimHook(eventId, action));
    }

    void beforeNextProcessingCommit(Runnable action) {
      beforeProcessingCommit.set(action);
    }

    void clear() {
      beforeProcessingCommit.set(null);
      afterClaim.set(null);
      afterRollback.set(null);
    }

    @Override
    public <T> T execute(TransactionCallback<T> action) {
      T result;
      try {
        result =
            delegate.execute(
                status -> {
                  T value = action.doInTransaction(status);
                  if (value == null) {
                    Runnable hook = beforeProcessingCommit.getAndSet(null);
                    if (hook != null) hook.run();
                  }
                  return value;
                });
      } catch (RuntimeException exception) {
        Runnable hook = afterRollback.getAndSet(null);
        if (hook != null) {
          hook.run();
        }
        throw exception;
      }
      ClaimHook hook = afterClaim.get();
      if (hook != null
          && result instanceof Optional<?> row
          && row.orElse(null) instanceof InboxClaim.Claimed(WaitingInboxEvent claimed)
          && claimed.event().eventId().equals(hook.eventId())
          && afterClaim.compareAndSet(hook, null)) {
        hook.action().run();
      }
      return result;
    }

    private record ClaimHook(UUID eventId, Runnable action) {}
  }

  /** 재시도 대기를 기다리지 않고 다음 처리 시각으로 옮기기 위한 시계. */
  static final class MovableClock extends Clock {
    private volatile Instant instant = NOW;

    void set(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
