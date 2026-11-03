package com.commercesettle.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.payment.application.dto.RefundWorkerSettings;
import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.RefundApprovedPayload;
import com.commercesettle.payment.application.event.RefundOrderRestoredPayload;
import com.commercesettle.payment.application.event.RefundReservationResultPayload;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import com.commercesettle.payment.application.port.PaymentGateway.Result;
import com.commercesettle.payment.application.port.RefundGateway;
import com.commercesettle.payment.domain.model.MileageWallet;
import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.model.PaymentAmounts;
import com.commercesettle.payment.domain.model.PaymentAttempt;
import com.commercesettle.payment.domain.repository.MileageWalletRepository;
import com.commercesettle.payment.domain.repository.PaymentAttemptRepository;
import com.commercesettle.payment.domain.repository.PaymentRepository;
import com.commercesettle.payment.infrastructure.messaging.StubSettlementRefundResponder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
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

/**
 * 환불 승인 수신부터 정산 예약(stub)·외부 환불·마일리지 복원·원장 상쇄(stub)·주문 복구 확인까지 실제 PostgreSQL에서 이어 실행한다. 외부 환불 대역은
 * 결과를 고를 수 있는 시험용 대역이다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(RefundSagaIntegrationTest.Config.class)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefundSagaIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/payment.sql");

  @Autowired private ApplyOrderPaymentRequestService inboxProcessor;
  @Autowired private RefundSettlementReplyService replies;
  @Autowired private RefundWorker worker;
  @Autowired private FakeRefundGateway gateway;
  @Autowired private InboxStore inbox;
  @Autowired private PaymentRepository payments;
  @Autowired private PaymentAttemptRepository attempts;
  @Autowired private MileageWalletRepository wallets;
  @Autowired private ObjectMapper mapper;
  @Autowired private DataSource dataSource;
  @Autowired private Clock clock;
  @Autowired private PlatformTransactionManager manager;

  private Connection lockHolder;

  @AfterEach
  void deleteRows() throws SQLException {
    if (lockHolder != null) {
      lockHolder.rollback();
      lockHolder.close();
      lockHolder = null;
    }
    gateway.reset();
    JdbcTemplate jdbc = jdbc();
    for (String table :
        List.of(
            "mileage_entries",
            "mileage_wallets",
            "payment_jobs",
            "payment_attempts",
            "refund_sagas",
            "refunds",
            "payments",
            "inbox",
            "outbox")) {
      jdbc.update("DELETE FROM payment." + table);
    }
  }

  @Test
  @DisplayName("카드 결제는 외부 환불 1건 뒤 접수·예약 요청·금융 완료·주문 복구 요청 순서로 발행하고 완료된다")
  void refundsCardPayment() throws Exception {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 0), 0);

    UUID refundId = runSagaToOrderRestore(payment);

    assertThat(gateway.refunds)
        .singleElement()
        .satisfies(r -> assertThat(r.amount()).isEqualTo(10_000));
    assertThat(eventTypes(payment))
        .containsExactly(
            "RefundAccepted",
            "RefundReservationRequested",
            "RefundFinancialCompleted",
            "RefundCompleted");
    assertThat(restoreEntries()).isZero();
    assertThat(refundStatus()).isEqualTo("INTERNAL_PENDING");

    restoreOrder(refundId, payment);

    assertThat(refundStatus()).isEqualTo("COMPLETED");
    assertThat(jdbc().queryForObject("SELECT step FROM payment.refund_sagas", String.class))
        .isEqualTo("COMPLETED");
  }

  @Test
  @DisplayName("혼합 결제는 외부 결제액만 환불하고 사용 마일리지를 원결제별 한 번 복원한다")
  void refundsMixedPayment() throws Exception {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 2_000), 500);

    UUID refundId = runSagaToOrderRestore(payment);
    restoreOrder(refundId, payment);

    assertThat(gateway.refunds)
        .singleElement()
        .satisfies(r -> assertThat(r.amount()).isEqualTo(8_000));
    assertThat(walletBalance(payment)).isEqualTo(2_500);
    assertThat(restoreEntries()).isEqualTo(1);
    JsonNode completed = payload(payment, "RefundFinancialCompleted");
    assertThat(completed.path("externalAmount").asLong()).isEqualTo(8_000);
    assertThat(completed.path("mileageAmount").asLong()).isEqualTo(2_000);
    assertThat(refundStatus()).isEqualTo("COMPLETED");
  }

  @Test
  @DisplayName("마일리지 전액 결제는 외부 호출 없이 사용 마일리지만 복원한다")
  void refundsMileageOnlyPayment() throws Exception {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 10_000), 0);

    UUID refundId = runSagaToOrderRestore(payment);
    restoreOrder(refundId, payment);

    assertThat(gateway.refunds).isEmpty();
    assertThat(gateway.lookups).isEmpty();
    assertThat(walletBalance(payment)).isEqualTo(10_000);
    assertThat(restoreEntries()).isEqualTo(1);
    assertThat(jdbc().queryForObject("SELECT external_status FROM payment.refunds", String.class))
        .isEqualTo("NOT_REQUIRED");
    assertThat(refundStatus()).isEqualTo("COMPLETED");
  }

  @Test
  @DisplayName("외부 환불이 확정 거절되면 마일리지를 복원하지 않고 예약 해제 확인 뒤 RefundFailed를 발행한다")
  void compensatesWhenExternalRefundDeclined() throws Exception {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 2_000), 500);
    gateway.nextRefund(new Result.Declined(UUID.randomUUID()));
    approve(payment, UUID.randomUUID());
    inboxProcessor.processNext();
    stub().run();

    assertThat(worker.runOnce()).isTrue();
    assertThat(refundStatus()).isEqualTo("COMPENSATING");
    stub().run();

    assertThat(refundStatus()).isEqualTo("DECLINED");
    assertThat(walletBalance(payment)).isEqualTo(500);
    assertThat(restoreEntries()).isZero();
    assertThat(eventTypes(payment))
        .containsExactly(
            "RefundAccepted",
            "RefundReservationRequested",
            "RefundReleaseRequested",
            "RefundFailed");
    assertThat(payload(payment, "RefundFailed").path("reasonCode").asText())
        .isEqualTo(RefundResultRecorder.EXTERNAL_DECLINED);
  }

  @Test
  @DisplayName("외부 결과가 미확정이면 예약을 유지하고 같은 원거래 조회로 성공을 확인해 한 번만 복원한다")
  void keepsReservationUntilUnconfirmedRefundResolved() throws Exception {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 2_000), 0);
    gateway.nextRefund(new Result.Unconfirmed("timeout"));
    approve(payment, UUID.randomUUID());
    inboxProcessor.processNext();
    stub().run();

    worker.runOnce();

    assertThat(refundStatus()).isEqualTo("UNKNOWN");
    assertThat(jdbc().queryForObject("SELECT step FROM payment.refund_sagas", String.class))
        .isEqualTo("EXTERNAL_REFUND_PENDING");
    assertThat(jdbc().queryForObject("SELECT status FROM payment.payment_jobs", String.class))
        .isEqualTo("RETRY");
    assertThat(walletBalance(payment)).isZero();

    gateway.nextLookup(new Result.Succeeded(UUID.randomUUID(), Instant.now()));
    makeRefundDue();
    worker.runOnce();

    assertThat(gateway.refunds).hasSize(1);
    assertThat(gateway.lookups).hasSize(1);
    assertThat(refundStatus()).isEqualTo("INTERNAL_PENDING");
    assertThat(walletBalance(payment)).isEqualTo(2_000);
    assertThat(restoreEntries()).isEqualTo(1);
  }

  @ParameterizedTest
  @CsvSource({"1, 1", "2, 2", "3, 4", "7, 60", "9, 60"})
  @DisplayName("미확정 환불은 시도 횟수에 따라 지수 재시도하고 예약과 업무 키를 유지한다")
  void reschedulesUnconfirmedRefundWithExponentialDelay(int attempt, long delaySeconds)
      throws Exception {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 2_000), 500);
    approve(payment, UUID.randomUUID());
    inboxProcessor.processNext();
    stub().run();
    UUID businessKey =
        jdbc().queryForObject("SELECT business_key FROM payment.payment_jobs", UUID.class);
    jdbc().update("UPDATE payment.payment_jobs SET attempts = ?", attempt - 1);
    gateway.nextRefund(new Result.Unconfirmed("provider unavailable"));
    Instant before = clock.instant();

    assertThat(worker.runOnce()).isTrue();

    Instant after = clock.instant();
    Instant nextRunAt =
        jdbc()
            .queryForObject("SELECT next_run_at FROM payment.payment_jobs", OffsetDateTime.class)
            .toInstant();
    assertThat(nextRunAt)
        .isBetween(before.plusSeconds(delaySeconds), after.plusSeconds(delaySeconds));
    assertThat(
            jdbc().queryForMap("SELECT status, attempts, business_key FROM payment.payment_jobs"))
        .containsEntry("status", "RETRY")
        .containsEntry("attempts", attempt)
        .containsEntry("business_key", businessKey);
    assertThat(refundStatus()).isEqualTo("UNKNOWN");
    assertThat(jdbc().queryForObject("SELECT step FROM payment.refund_sagas", String.class))
        .isEqualTo("EXTERNAL_REFUND_PENDING");
    assertThat(walletBalance(payment)).isEqualTo(500);
    assertThat(restoreEntries()).isZero();
    assertThat(eventTypes(payment)).containsExactly("RefundAccepted", "RefundReservationRequested");
  }

  @Test
  @DisplayName("미확정 환불은 열 번째 시도 뒤 예약을 유지한 확인 대상으로 남기고 자동 실행하지 않는다")
  void requiresReviewAfterTenUnconfirmedAttempts() throws Exception {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 2_000), 500);
    approve(payment, UUID.randomUUID());
    inboxProcessor.processNext();
    stub().run();
    jdbc().update("UPDATE payment.payment_jobs SET attempts = 9");
    gateway.nextRefund(new Result.Unconfirmed("provider unavailable"));

    assertThat(worker.runOnce()).isTrue();

    assertThat(jdbc().queryForMap("SELECT status, attempts FROM payment.payment_jobs"))
        .containsEntry("status", "REVIEW_REQUIRED")
        .containsEntry("attempts", 10);
    assertThat(refundStatus()).isEqualTo("UNKNOWN");
    assertThat(jdbc().queryForObject("SELECT step FROM payment.refund_sagas", String.class))
        .isEqualTo("EXTERNAL_REFUND_PENDING");
    assertThat(walletBalance(payment)).isEqualTo(500);
    assertThat(restoreEntries()).isZero();
    assertThat(worker.runOnce()).isFalse();
    assertThat(gateway.refunds).hasSize(1);
    assertThat(gateway.lookups).isEmpty();
  }

  @Test
  @DisplayName("호출 기록 뒤 원거래가 없으면 같은 업무 키로 다시 요청해 외부 환불은 하나다")
  void resendsWithSameKeyWhenTransactionNotFound() throws Exception {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 0), 0);
    gateway.nextRefund(new Result.Unconfirmed("connection reset"));
    approve(payment, UUID.randomUUID());
    inboxProcessor.processNext();
    stub().run();
    worker.runOnce();

    gateway.nextLookup(new Result.NotFound());
    makeRefundDue();
    worker.runOnce();

    assertThat(gateway.lookups).hasSize(1);
    assertThat(gateway.refunds).hasSize(2);
    assertThat(gateway.refunds.get(1)).isEqualTo(gateway.refunds.get(0));
    assertThat(refundStatus()).isEqualTo("INTERNAL_PENDING");
  }

  @Test
  @DisplayName("완료 뒤 같은 요청·다른 요청의 환불 승인이 다시 와도 외부 환불과 복원은 한 번이다")
  void keepsSingleRefundAcrossRedelivery() throws Exception {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 2_000), 0);
    UUID requestId = UUID.randomUUID();
    approve(payment, requestId);
    inboxProcessor.processNext();
    stub().run();
    worker.runOnce();
    stub().run();

    approve(payment, requestId);
    approve(payment, UUID.randomUUID());
    inboxProcessor.processNext();
    inboxProcessor.processNext();
    stub().run();
    while (worker.runOnce()) {
      // 남은 작업이 있으면 모두 실행한다.
    }

    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.refunds", Long.class))
        .isEqualTo(1);
    assertThat(gateway.refunds).hasSize(1);
    assertThat(restoreEntries()).isEqualTo(1);
    assertThat(walletBalance(payment)).isEqualTo(2_000);
    assertThat(
            jdbc()
                .queryForList(
                    "SELECT last_error_code FROM payment.inbox WHERE status = 'REVIEW_REQUIRED'",
                    String.class))
        .containsExactly(RefundEventHandler.ALREADY_IN_PROGRESS);
  }

  @Test
  @DisplayName("환불 행 잠금 경합이 길면 준비 단계는 대기 상한 안에 롤백하고 외부 환불을 호출하지 않는다")
  void rollsBackPrepareWhenRefundRowLockIsContended() throws Exception {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 0), 0);
    approve(payment, UUID.randomUUID());
    inboxProcessor.processNext();
    stub().run();
    lockRefundRow();

    long started = System.nanoTime();
    assertThatThrownBy(worker::runOnce).isInstanceOf(PessimisticLockingFailureException.class);

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    assertThat(gateway.refunds).isEmpty();
    assertThat(gateway.lookups).isEmpty();
    assertThat(jdbc().queryForObject("SELECT external_status FROM payment.refunds", String.class))
        .isEqualTo("PENDING");
  }

  @Test
  @DisplayName("결과 반영 중 환불 행 잠금 경합이 길면 대기 상한 안에 롤백하고 복원·금융 완료를 남기지 않는다")
  void rollsBackRecordWhenRefundRowLockIsContended() throws Exception {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 2_000), 0);
    approve(payment, UUID.randomUUID());
    inboxProcessor.processNext();
    stub().run();
    gateway.beforeRefund = this::lockRefundRow;

    long started = System.nanoTime();
    assertThatThrownBy(worker::runOnce).isInstanceOf(PessimisticLockingFailureException.class);

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    assertThat(gateway.refunds).hasSize(1);
    assertThat(jdbc().queryForObject("SELECT external_status FROM payment.refunds", String.class))
        .isEqualTo("REQUESTED");
    assertThat(restoreEntries()).isZero();
    assertThat(walletBalance(payment)).isZero();
    assertThat(eventTypes(payment)).containsExactly("RefundAccepted", "RefundReservationRequested");
    assertThat(jdbc().queryForObject("SELECT status FROM payment.payment_jobs", String.class))
        .isEqualTo("RUNNING");
  }

  @Test
  @DisplayName("정산 응답 반영 중 환불 행 잠금 경합이 길면 대기 상한 안에 롤백하고 환불 작업을 만들지 않는다")
  void rollsBackSettlementReplyWhenRefundRowLockIsContended() throws Exception {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 0), 0);
    approve(payment, UUID.randomUUID());
    inboxProcessor.processNext();
    UUID refundId = jdbc().queryForObject("SELECT id FROM payment.refunds", UUID.class);
    lockRefundRow();

    long started = System.nanoTime();
    assertThatThrownBy(
            () ->
                replies.applyReservationResult(
                    new RefundReservationResultPayload(
                        refundId,
                        payment.getId(),
                        RefundReservationResultPayload.Result.RESERVED,
                        UUID.randomUUID(),
                        null)))
        .isInstanceOf(PessimisticLockingFailureException.class);

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.payment_jobs", Long.class))
        .isZero();
    assertThat(jdbc().queryForObject("SELECT step FROM payment.refund_sagas", String.class))
        .isEqualTo("RESERVATION_REQUESTED");
  }

  /** 다른 트랜잭션에서 환불 행을 잠그고 정리 때까지 놓지 않는다. */
  private void lockRefundRow() {
    try {
      lockHolder = dataSource.getConnection();
      lockHolder.setAutoCommit(false);
      try (Statement statement = lockHolder.createStatement()) {
        statement.execute("SELECT id FROM payment.refunds FOR UPDATE");
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 환불 승인부터 RefundCompleted 발행까지 실행하고 환불 ID를 돌려준다. */
  private UUID runSagaToOrderRestore(Payment payment) throws Exception {
    approve(payment, UUID.randomUUID());
    assertThat(inboxProcessor.processNext()).isTrue();
    stub().run();
    assertThat(worker.runOnce()).isTrue();
    stub().run();
    assertThat(worker.runOnce()).isFalse();
    return jdbc().queryForObject("SELECT id FROM payment.refunds", UUID.class);
  }

  private void restoreOrder(UUID refundId, Payment payment) throws Exception {
    saveWaiting(
        new EventEnvelope<>(
            UUID.randomUUID(),
            "RefundOrderRestored",
            1,
            clock.instant().atOffset(ZoneOffset.UTC),
            payment.getOrderId(),
            5,
            new RefundOrderRestoredPayload(refundId, payment.getId(), payment.getOrderId(), 5)));
    assertThat(inboxProcessor.processNext()).isTrue();
  }

  private void approve(Payment payment, UUID requestId) throws Exception {
    OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
    saveWaiting(
        new EventEnvelope<>(
            UUID.randomUUID(),
            "RefundApproved",
            1,
            now,
            payment.getOrderId(),
            3,
            new RefundApprovedPayload(
                requestId, payment.getOrderId(), payment.getId(), now, now, 3)));
  }

  private void saveWaiting(EventEnvelope<?> event) throws Exception {
    byte[] body = mapper.writeValueAsBytes(event);
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status ->
                inbox.saveWaiting(event, new Source("commerce-settle.commerce.v1", 0, 0L, body)));
  }

  /** 승인(카드는 승인 시도까지)과 마일리지 차감이 끝난 결제. 지갑에는 차감 뒤 잔액을 둔다. */
  private Payment confirmed(PaymentAmounts amounts, long balanceAfterDebit) {
    Instant now = clock.instant();
    Payment payment =
        Payment.create(
            UUID.randomUUID(),
            0,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            amounts,
            "v1",
            now);
    if (amounts.externalAmount() > 0) {
      payment.startProcessing(now);
    }
    payment.confirm(now);
    payment = payments.save(payment);
    if (amounts.externalAmount() > 0) {
      PaymentAttempt attempt =
          PaymentAttempt.start(
              payment, "PG_A", "CREDIT_CARD", "card-1", "funding-1", "pg-normal-v1", now);
      attempt.approve(UUID.randomUUID(), now);
      attempts.save(attempt);
    }
    if (amounts.mileageAmount() > 0) {
      wallets.save(MileageWallet.open(payment.getBuyerId(), balanceAfterDebit, now));
    }
    return payment;
  }

  private StubSettlementRefundResponder stub() {
    return new StubSettlementRefundResponder(replies, clock, 20);
  }

  private List<String> eventTypes(Payment payment) {
    return jdbc()
        .queryForList(
            "SELECT event_type FROM payment.outbox WHERE aggregate_id = ? ORDER BY sequence_no",
            String.class,
            payment.getId());
  }

  private JsonNode payload(Payment payment, String eventType) throws Exception {
    String json =
        jdbc()
            .queryForObject(
                "SELECT payload::text FROM payment.outbox WHERE aggregate_id = ? AND event_type = ?",
                String.class,
                payment.getId(),
                eventType);
    return mapper.readTree(json).path("payload");
  }

  private String refundStatus() {
    return jdbc().queryForObject("SELECT status FROM payment.refunds", String.class);
  }

  private long restoreEntries() {
    return jdbc()
        .queryForObject(
            "SELECT count(*) FROM payment.mileage_entries WHERE kind = 'RESTORE'", Long.class);
  }

  private long walletBalance(Payment payment) {
    return jdbc()
        .queryForObject(
            "SELECT balance FROM payment.mileage_wallets WHERE buyer_id = ?",
            Long.class,
            payment.getBuyerId());
  }

  private JdbcTemplate jdbc() {
    return new JdbcTemplate(dataSource);
  }

  /** 테스트가 실제 재시도 간격을 기다리지 않고 같은 작업의 다음 실행을 검증한다. */
  private void makeRefundDue() {
    jdbc().update("UPDATE payment.payment_jobs SET next_run_at = clock_timestamp()");
  }

  /** 다음 결과를 고를 수 있는 외부 환불 대역. 정하지 않으면 성공한다. 호출 본문을 기록하고, 응답 전 동작을 끼울 수 있다. */
  static final class FakeRefundGateway implements RefundGateway {
    final List<RefundRequest> refunds = new ArrayList<>();
    final List<RefundRequest> lookups = new ArrayList<>();
    private final Deque<Result> refundResults = new ArrayDeque<>();
    private final Deque<Result> lookupResults = new ArrayDeque<>();
    Runnable beforeRefund = () -> {};

    void nextRefund(Result result) {
      refundResults.add(result);
    }

    void nextLookup(Result result) {
      lookupResults.add(result);
    }

    void reset() {
      refunds.clear();
      lookups.clear();
      refundResults.clear();
      lookupResults.clear();
      beforeRefund = () -> {};
    }

    @Override
    public synchronized Result refund(RefundRequest request) {
      refunds.add(request);
      beforeRefund.run();
      Result next = refundResults.poll();
      return next != null ? next : new Result.Succeeded(UUID.randomUUID(), Instant.now());
    }

    @Override
    public synchronized Result lookup(RefundRequest request) {
      lookups.add(request);
      Result next = lookupResults.poll();
      return next != null ? next : new Result.NotFound();
    }
  }

  /** DB의 현재 시각을 읽는 시험용 시계. */
  static final class DatabaseClock extends Clock {
    private final JdbcTemplate jdbc;

    DatabaseClock(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
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
      return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
    }
  }

  @TestConfiguration
  // 같은 패키지의 다른 테스트 설정을 읽지 않도록 저장소 구현만 등록한다.
  @ComponentScan(
      basePackages = "com.commercesettle.payment.infrastructure.persistence",
      useDefaultFilters = false,
      includeFilters = @ComponentScan.Filter(Repository.class))
  @Import({
    ApplyOrderPaymentRequestService.class,
    RefundEventHandler.class,
    RefundEvents.class,
    RefundSettlementReplyService.class,
    RefundWorker.class,
    RefundResultRecorder.class
  })
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

    // Inbox 대기·작업 도래·임대는 DB 시각으로 저장·판정하므로 앱 시계도 DB 시각을 읽는다.
    // 컨테이너와 호스트 시계가 조금만 어긋나도 방금 저장한 대기를 처리하지 못하기 때문이다.
    @Bean
    Clock clock(DataSource dataSource) {
      return new DatabaseClock(new JdbcTemplate(dataSource));
    }

    @Bean
    TransactionTemplate transactions(PlatformTransactionManager manager) {
      return new TransactionTemplate(manager);
    }

    @Bean
    RefundWorkerSettings refundWorkerSettings() {
      return new RefundWorkerSettings(Duration.ofSeconds(30), Duration.ofSeconds(5), 10);
    }

    @Bean
    FakeRefundGateway refundGateway() {
      return new FakeRefundGateway();
    }
  }
}
