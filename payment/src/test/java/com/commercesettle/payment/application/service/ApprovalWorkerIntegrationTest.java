package com.commercesettle.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.payment.application.dto.ApprovalWorkerSettings;
import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.event.PaymentCancellationRequestedPayload;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import com.commercesettle.payment.application.port.PaymentGateway;
import com.commercesettle.payment.application.port.PaymentGateway.ApprovalRequest;
import com.commercesettle.payment.application.port.PaymentGateway.Result;
import com.commercesettle.payment.domain.repository.MileageReservationRepository;
import com.commercesettle.payment.domain.repository.PaymentJobRepository;
import com.commercesettle.payment.support.PaymentObservationsTestConfiguration;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 실제 PostgreSQL에서 승인 작업 임대·외부 호출 전후 트랜잭션·결과 반영을 확인한다. PG 대역은 결과를 정할 수 있는 대역으로 바꾼다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ApprovalWorkerIntegrationTest.Config.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApprovalWorkerIntegrationTest {
  private static final Instant NOW = Instant.parse("2100-01-01T00:00:00Z");
  private static final UUID BUYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/payment.sql");

  @Autowired private ApplyOrderPaymentRequestService requests;
  @Autowired private ApprovalWorker worker;
  @Autowired private FakeGateway gateway;
  @Autowired private InboxStore inbox;
  @Autowired private ObjectMapper mapper;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager manager;
  @Autowired private MileageReservationRepository reservations;
  @Autowired private PaymentJobRepository jobs;
  @Autowired private ApprovalResultRecorder recorder;

  private JdbcTemplate jdbc() {
    return new JdbcTemplate(dataSource);
  }

  private Connection lockHolder;

  @AfterEach
  void deleteRows() throws SQLException {
    if (lockHolder != null) {
      lockHolder.rollback();
      lockHolder.close();
      lockHolder = null;
    }
    gateway.reset();
    for (String table :
        List.of(
            "mileage_entries",
            "mileage_reservations",
            "mileage_wallets",
            "payment_attempts",
            "payment_jobs",
            "payments",
            "inbox",
            "outbox")) {
      jdbc().update("DELETE FROM payment." + table);
    }
  }

  @Test
  @DisplayName("카드 결제를 승인하면 시도·결제 확정·작업 종료·PaymentConfirmed가 함께 커밋된다")
  void confirmsCardPayment() {
    UUID orderId = request(10_000, 0, "CREDIT_CARD");

    assertThat(worker.runOnce()).isTrue();

    assertThat(status()).isEqualTo("CONFIRMED");
    assertThat(
            jdbc()
                .queryForMap(
                    "SELECT provider_id, method, account_ref, amount, status, external_ref IS NOT"
                        + " NULL AS has_ref FROM payment.payment_attempts"))
        .containsEntry("provider_id", "PG_A")
        .containsEntry("method", "CREDIT_CARD")
        .containsEntry("account_ref", "card-ref")
        .containsEntry("amount", 10_000L)
        .containsEntry("status", "APPROVED")
        .containsEntry("has_ref", true);
    assertThat(jobStatus()).isEqualTo("DONE");
    assertThat(outbox())
        .containsExactly("PaymentAccepted:0", "PaymentConfirmed:2", "PaymentNoticeRequested:2");
    assertThat(
            jdbc()
                .queryForObject(
                    "SELECT payload -> 'payload' ->> 'orderId' FROM payment.outbox"
                        + " WHERE event_type = 'PaymentConfirmed'",
                    String.class))
        .isEqualTo(orderId.toString());
    assertThat(notice().path("mileageBalanceAfter").isNull()).isTrue();
    assertThat(notice().path("walletVersion").isNull()).isTrue();
    assertThat(notice().path("orderId").asText()).isEqualTo(orderId.toString());
    assertThat(notice().path("processedAt").asText()).endsWith("+09:00");
    assertThat(gateway.approvals).hasSize(1);
    assertThat(worker.runOnce()).isFalse();
  }

  @Test
  @DisplayName("혼합 결제를 승인하면 예약한 마일리지를 차감하고, 간편결제는 EASY_PAY 대역으로 보낸다")
  void consumesMileageOnApproval() {
    fundWallet(5_000);
    request(10_000, 2_000, "EASY_PAY");

    worker.runOnce();

    assertThat(gateway.approvals.get(0).providerId()).isEqualTo("EASY_PAY");
    assertThat(gateway.approvals.get(0).amount()).isEqualTo(8_000);
    assertThat(
            jdbc()
                .queryForMap(
                    "SELECT balance, reserved_amount FROM payment.mileage_wallets WHERE buyer_id ="
                        + " ?",
                    BUYER_ID))
        .containsEntry("balance", 3_000L)
        .containsEntry("reserved_amount", 0L);
    assertThat(
            jdbc()
                .queryForList(
                    "SELECT kind FROM payment.mileage_entries ORDER BY kind", String.class))
        .containsExactly("DEBIT", "RESERVE");
    assertThat(notice().path("mileageBalanceAfter").asLong()).isEqualTo(3000);
    assertThat(notice().path("walletVersion").asLong())
        .isEqualTo(
            jdbc().queryForObject("SELECT version FROM payment.mileage_wallets", Long.class));
    jdbc().update("UPDATE payment.mileage_wallets SET balance=9999, version=version+1");
    assertThat(notice().path("mileageBalanceAfter").asLong()).isEqualTo(3000);
  }

  @Test
  @DisplayName("확정 거절되면 결제를 거절하고 마일리지 예약을 풀고 PaymentDeclined를 남긴다")
  void declinesPaymentAndReleasesMileage() {
    fundWallet(5_000);
    request(10_000, 2_000, "CREDIT_CARD");
    gateway.approve = r -> new Result.Declined(UUID.randomUUID());

    worker.runOnce();

    assertThat(status()).isEqualTo("DECLINED");
    assertThat(
            jdbc().queryForObject("SELECT status FROM payment.mileage_reservations", String.class))
        .isEqualTo("RELEASED");
    assertThat(
            jdbc()
                .queryForObject(
                    "SELECT reserved_amount FROM payment.mileage_wallets WHERE buyer_id = ?",
                    Long.class,
                    BUYER_ID))
        .isZero();
    assertThat(outbox()).containsExactly("PaymentAccepted:0", "PaymentDeclined:2");
  }

  @Test
  @DisplayName("혼합 결제를 두 번 거절한 뒤 새 회차로 승인해도 이전 시도·예약·원장은 보존되고 한 번만 차감된다")
  void preservesHistoryAcrossRepeatedMixedRetries() throws Exception {
    fundWallet(5_000);
    request(10_000, 2_000, "CREDIT_CARD");
    gateway.approve = r -> new Result.Declined(UUID.randomUUID());
    worker.runOnce();
    var identity = jdbc().queryForMap("SELECT id, created_at FROM payment.payments");
    var firstAttempt = jdbc().queryForMap("SELECT * FROM payment.payment_attempts");
    var firstReservation = jdbc().queryForMap("SELECT * FROM payment.mileage_reservations");

    retry(UUID.randomUUID(), 2);
    worker.runOnce();
    UUID finalCycle = UUID.randomUUID();
    UUID finalEvent = retry(finalCycle, 4);
    gateway.approve = r -> new Result.Succeeded(UUID.randomUUID(), NOW);
    worker.runOnce();
    assertThat(requests.processEvent(finalEvent)).isFalse();
    assertThat(worker.runOnce()).isFalse();

    assertThat(jdbc().queryForMap("SELECT id, created_at FROM payment.payments"))
        .isEqualTo(identity);
    assertThat(status()).isEqualTo("CONFIRMED");
    assertThat(jdbc().queryForMap("SELECT * FROM payment.payment_attempts WHERE attempt_no=1"))
        .isEqualTo(firstAttempt);
    assertThat(
            jdbc()
                .queryForMap(
                    "SELECT * FROM payment.mileage_reservations WHERE id=?",
                    firstReservation.get("id")))
        .isEqualTo(firstReservation);
    assertThat(
            jdbc()
                .queryForList(
                    "SELECT attempt_no || ':' || status FROM payment.payment_attempts ORDER BY attempt_no",
                    String.class))
        .containsExactly("1:DECLINED", "2:DECLINED", "3:APPROVED");
    assertThat(
            jdbc()
                .queryForList(
                    "SELECT status FROM payment.mileage_reservations ORDER BY status",
                    String.class))
        .containsExactly("CONSUMED", "RELEASED", "RELEASED");
    assertThat(reservations.findByPaymentId((UUID) identity.get("id")).orElseThrow().getCycleId())
        .isEqualTo(finalCycle);
    assertThat(jdbc().queryForMap("SELECT balance, reserved_amount FROM payment.mileage_wallets"))
        .containsEntry("balance", 3_000L)
        .containsEntry("reserved_amount", 0L);
    assertThat(
            jdbc()
                .queryForList(
                    "SELECT kind FROM payment.mileage_entries ORDER BY kind", String.class))
        .containsExactly("DEBIT", "RELEASE", "RELEASE", "RESERVE", "RESERVE", "RESERVE");
    assertThat(outbox())
        .containsExactly(
            "PaymentAccepted:0",
            "PaymentDeclined:2",
            "PaymentAccepted:3",
            "PaymentDeclined:5",
            "PaymentAccepted:6",
            "PaymentConfirmed:8",
            "PaymentNoticeRequested:8");
    assertThat(gateway.approvals).hasSize(3);
    assertThat(new HashSet<>(gateway.approvals.stream().map(ApprovalRequest::businessKey).toList()))
        .hasSize(3);
  }

  @Test
  @DisplayName("이전 회차 작업을 인위적으로 다시 대기시켜도 새 회차를 승인하지 않고 늦은 결과도 반영하지 않는다")
  void fencesSupersededJobsAndLateResults() throws Exception {
    request(10_000, 0, "CREDIT_CARD");
    var oldJob =
        jobs.leaseNext(
                com.commercesettle.payment.domain.model.PaymentJobOperation.APPROVE,
                "old-worker",
                Duration.ofSeconds(30))
            .orElseThrow();
    recorder.prepare(oldJob).orElseThrow();
    recorder.record(oldJob, new Result.Declined(UUID.randomUUID()));
    retry(UUID.randomUUID(), 2);
    assertThatThrownBy(() -> recorder.record(oldJob, new Result.Succeeded(UUID.randomUUID(), NOW)))
        .isInstanceOf(ApprovalResultRecorder.ApprovalLeaseLostException.class);
    // 정상 운영 replay는 DONE을 거절한다. 과거 작업이 재등장하는 방어 경계는 DB 시험 입력으로 만든다.
    jdbc()
        .update(
            "UPDATE payment.payment_jobs SET status='PENDING', next_run_at=clock_timestamp()-interval '2s' WHERE id=?",
            oldJob.jobId());
    assertThat(worker.runOnce()).isTrue();
    assertThat(gateway.approvals).isEmpty();
    assertThat(
            jdbc()
                .queryForObject(
                    "SELECT last_error_code FROM payment.payment_jobs WHERE id=?",
                    String.class,
                    oldJob.jobId()))
        .isEqualTo("PAYMENT_CYCLE_SUPERSEDED");
    assertThat(status()).isEqualTo("DECLINED");
    assertThat(worker.runOnce()).isTrue();
    assertThat(status()).isEqualTo("CONFIRMED");
    assertThat(gateway.approvals).hasSize(1);
    assertThat(
            jdbc()
                .queryForList(
                    "SELECT status FROM payment.payment_attempts ORDER BY attempt_no",
                    String.class))
        .containsExactly("DECLINED", "APPROVED");
  }

  @Test
  @DisplayName("미확정 회차에서 새 회차 요청은 거절하고 기존 예약·거래 조회를 유지한다")
  void rejectsNewCycleWhileApprovalIsUnknown() throws Exception {
    fundWallet(5_000);
    request(10_000, 2_000, "CREDIT_CARD");
    gateway.approve = r -> new Result.Unconfirmed("timeout");
    worker.runOnce();
    UUID currentCycle =
        jdbc().queryForObject("SELECT current_cycle_id FROM payment.payments", UUID.class);
    UUID retryEvent = retry(UUID.randomUUID(), 2);
    assertThat(
            jdbc()
                .queryForObject(
                    "SELECT status FROM payment.inbox WHERE event_id=?", String.class, retryEvent))
        .isEqualTo("REVIEW_REQUIRED");
    assertThat(jdbc().queryForObject("SELECT current_cycle_id FROM payment.payments", UUID.class))
        .isEqualTo(currentCycle);
    assertThat(
            jdbc()
                .queryForObject("SELECT reserved_amount FROM payment.mileage_wallets", Long.class))
        .isEqualTo(2_000);
    gateway.lookup = r -> new Result.Succeeded(UUID.randomUUID(), NOW);
    worker.runOnce();
    assertThat(status()).isEqualTo("CONFIRMED");
    assertThat(gateway.approvals).hasSize(1);
    assertThat(gateway.lookups).hasSize(1);
    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.payment_jobs", Long.class))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("새 회차 예약 가용액이 부족하면 회차·기존 이력·작업을 바꾸지 않는다")
  void doesNotAdoptRetryWhenMileageIsUnavailable() throws Exception {
    fundWallet(5_000);
    request(10_000, 2_000, "CREDIT_CARD");
    gateway.approve = r -> new Result.Declined(UUID.randomUUID());
    worker.runOnce();
    var payment = jdbc().queryForMap("SELECT * FROM payment.payments");
    jdbc().update("UPDATE payment.mileage_wallets SET balance=1000, version=version+1");
    UUID event = retry(UUID.randomUUID(), 2);
    assertThat(jdbc().queryForMap("SELECT * FROM payment.payments")).isEqualTo(payment);
    assertThat(
            jdbc()
                .queryForMap(
                    "SELECT status, last_error_code FROM payment.inbox WHERE event_id=?", event))
        .containsEntry("status", "REVIEW_REQUIRED")
        .containsEntry("last_error_code", "PAYMENT_REQUEST_MILEAGE_INSUFFICIENT");
    assertThat(
            jdbc().queryForObject("SELECT count(*) FROM payment.mileage_reservations", Long.class))
        .isEqualTo(1);
    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.payment_jobs", Long.class))
        .isEqualTo(1);
    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.mileage_entries", Long.class))
        .isEqualTo(2);
    assertThat(worker.runOnce()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("새 회차 취소가 요청보다 먼저 와도, 재예약 뒤 먼저 처리돼도 추가 승인 없이 취소하고 원이력을 보존한다")
  void cancelsRetryBeforeApprovalInEitherDeliveryOrder(boolean cancelFirst) throws Exception {
    fundWallet(5_000);
    UUID order = request(10_000, 2_000, "CREDIT_CARD");
    UUID oldCycle =
        jdbc().queryForObject("SELECT current_cycle_id FROM payment.payments", UUID.class);
    gateway.approve = r -> new Result.Declined(UUID.randomUUID());
    worker.runOnce();
    UUID cycle = UUID.randomUUID();
    if (cancelFirst) cancellation(order, cycle, 3);
    retry(cycle, 2);
    if (!cancelFirst) {
      cancellation(order, oldCycle, 1);
      assertThat(status()).isEqualTo("DECLINED");
      assertThat(jdbc().queryForObject("SELECT current_cycle_id FROM payment.payments", UUID.class))
          .isEqualTo(cycle);
      cancellation(order, cycle, 3);
    }
    assertThat(status()).isEqualTo("CANCELLED");
    assertThat(worker.runOnce()).isFalse();
    assertThat(gateway.approvals).hasSize(1);
    assertThat(jdbc().queryForMap("SELECT balance, reserved_amount FROM payment.mileage_wallets"))
        .containsEntry("balance", 5_000L)
        .containsEntry("reserved_amount", 0L);
    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.payment_attempts", Long.class))
        .isEqualTo(1);
    assertThat(
            jdbc()
                .queryForObject(
                    "SELECT count(*) FROM payment.mileage_reservations WHERE status='RELEASED'",
                    Long.class))
        .isEqualTo(cancelFirst ? 1 : 2);
    assertThat(
            jdbc()
                .queryForObject(
                    "SELECT count(*) FROM payment.outbox WHERE event_type='PaymentCancelled'",
                    Long.class))
        .isEqualTo(1);
    assertThat(
            jdbc()
                .queryForObject(
                    "SELECT count(*) FROM payment.outbox WHERE event_type='PaymentAccepted'",
                    Long.class))
        .isEqualTo(2);
    assertThat(
            jdbc()
                .queryForObject(
                    "SELECT count(*) FROM payment.inbox WHERE event_type='OrderPaymentRequested' AND (status<>'APPLIED' OR result_ref IS NULL)",
                    Long.class))
        .isZero();
  }

  @Test
  @DisplayName("결과를 모르면 미확정으로 두고 다시 실행할 때 승인을 반복하지 않고 원거래를 조회해 확정한다")
  void looksUpUnconfirmedApprovalInsteadOfRetrying() {
    request(10_000, 0, "CREDIT_CARD");
    gateway.approve = r -> new Result.Unconfirmed("timeout");

    worker.runOnce();

    assertThat(status()).isEqualTo("UNKNOWN");
    assertThat(jobStatus()).isEqualTo("RETRY");
    makeJobDue();
    gateway.lookup = r -> new Result.Succeeded(UUID.randomUUID(), NOW);

    worker.runOnce();

    assertThat(status()).isEqualTo("CONFIRMED");
    assertThat(gateway.approvals).hasSize(1);
    assertThat(gateway.lookups).hasSize(1);
    assertThat(outbox())
        .containsExactly("PaymentAccepted:0", "PaymentConfirmed:3", "PaymentNoticeRequested:3");
  }

  @Test
  @DisplayName("미확정 조회는 지수 간격으로 재시도하고 소진해도 거래 키와 마일리지 예약을 유지한다")
  void backsOffUnconfirmedLookupsUntilAttemptsAreExhausted() {
    fundWallet(5_000);
    request(10_000, 2_000, "CREDIT_CARD");
    gateway.approve = r -> new Result.Unconfirmed("HTTP 503");
    gateway.lookup = r -> new Result.Unconfirmed("HTTP 503");
    long[] delays = {1, 2, 4, 8, 16, 32, 60, 60, 60};

    for (int attempt = 1; attempt <= 10; attempt++) {
      Instant before = databaseNow();
      assertThat(worker.runOnce()).isTrue();
      Instant after = databaseNow();

      assertThat(status()).isEqualTo("UNKNOWN");
      assertThat(jdbc().queryForObject("SELECT attempts FROM payment.payment_jobs", Integer.class))
          .isEqualTo(attempt);
      if (attempt < 10) {
        assertThat(jobStatus()).isEqualTo("RETRY");
        Instant next =
            jdbc()
                .queryForObject(
                    "SELECT next_run_at FROM payment.payment_jobs",
                    (rs, row) -> rs.getTimestamp(1).toInstant());
        assertThat(next)
            .isBetween(
                before.plusSeconds(delays[attempt - 1]), after.plusSeconds(delays[attempt - 1]));
        makeJobDue();
      }
    }

    assertThat(jobStatus()).isEqualTo("REVIEW_REQUIRED");
    assertThat(gateway.approvals).hasSize(1);
    assertThat(gateway.lookups).hasSize(9);
    assertThat(new HashSet<>(gateway.lookups)).containsExactly(gateway.approvals.get(0));
    assertThat(
            jdbc().queryForObject("SELECT status FROM payment.mileage_reservations", String.class))
        .isEqualTo("RESERVED");
    assertThat(jdbc().queryForMap("SELECT balance, reserved_amount FROM payment.mileage_wallets"))
        .containsEntry("balance", 5_000L)
        .containsEntry("reserved_amount", 2_000L);
    assertThat(outbox()).containsExactly("PaymentAccepted:0");
  }

  private Instant databaseNow() {
    return jdbc()
        .queryForObject("SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
  }

  @Test
  @DisplayName("호출 전 중단 뒤 임대가 끝나면 원거래를 조회하고, 거래가 없으면 같은 업무 키·본문으로 재전송한다")
  void resendsSameRequestWhenTransactionWasNotFound() {
    request(10_000, 0, "CREDIT_CARD");
    gateway.approve =
        r -> {
          throw new IllegalStateException("worker stopped before the call reached the provider");
        };
    assertThatThrownBy(worker::runOnce).isInstanceOf(IllegalStateException.class);
    assertThat(status()).isEqualTo("PROCESSING");
    expireLease();
    gateway.approve = r -> new Result.Succeeded(UUID.randomUUID(), NOW);
    gateway.lookup = r -> new Result.NotFound();

    worker.runOnce();

    assertThat(status()).isEqualTo("CONFIRMED");
    assertThat(gateway.approvals).hasSize(2);
    assertThat(gateway.approvals.get(1)).isEqualTo(gateway.approvals.get(0));
    assertThat(outbox())
        .containsExactly("PaymentAccepted:0", "PaymentConfirmed:2", "PaymentNoticeRequested:2");
  }

  @Test
  @DisplayName("응답을 받는 사이 다른 실행자가 임대를 넘겨받았으면 늦은 결과로 결제를 바꾸지 않는다")
  void ignoresLateResultAfterLeaseWasTakenOver() {
    request(10_000, 0, "CREDIT_CARD");
    gateway.approve =
        r -> {
          jdbc().update("UPDATE payment.payment_jobs SET lease_token = ?", UUID.randomUUID());
          return new Result.Succeeded(UUID.randomUUID(), NOW);
        };

    worker.runOnce();

    assertThat(status()).isEqualTo("PROCESSING");
    assertThat(jdbc().queryForObject("SELECT status FROM payment.payment_attempts", String.class))
        .isEqualTo("PROCESSING");
    assertThat(outbox()).containsExactly("PaymentAccepted:0");
  }

  @Test
  @DisplayName("외부 호출 전에 취소된 결제는 대역을 호출하지 않고 작업을 끝낸다")
  void skipsCancelledPayment() {
    request(10_000, 0, "CREDIT_CARD");
    jdbc().update("UPDATE payment.payments SET status = 'CANCELLED', version = version + 1");

    worker.runOnce();

    assertThat(gateway.approvals).isEmpty();
    assertThat(jobStatus()).isEqualTo("DONE");
    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.payment_attempts", Long.class))
        .isZero();
  }

  @Test
  @DisplayName("시도 상한을 넘겨 다시 회수된 작업은 호출하지 않고 확인 대상으로 넘긴다")
  void requiresReviewAfterExhaustingAttempts() {
    request(10_000, 0, "CREDIT_CARD");
    jdbc().update("UPDATE payment.payment_jobs SET attempts = 10");

    worker.runOnce();

    assertThat(gateway.approvals).isEmpty();
    assertThat(jdbc().queryForMap("SELECT status, last_error_code FROM payment.payment_jobs"))
        .containsEntry("status", "REVIEW_REQUIRED")
        .containsEntry("last_error_code", "PAYMENT_APPROVAL_ATTEMPTS_EXHAUSTED");
  }

  @Test
  @DisplayName("마일리지 전액 결제는 승인 작업 없이 결제 요청 처리에서 PaymentConfirmed를 남긴다")
  void confirmsMileageOnlyPaymentAtRequest() {
    fundWallet(12_000);

    request(10_000, 10_000, null);

    assertThat(status()).isEqualTo("CONFIRMED");
    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.payment_jobs", Long.class))
        .isZero();
    assertThat(outbox())
        .containsExactly("PaymentAccepted:0", "PaymentConfirmed:0", "PaymentNoticeRequested:0");
    assertThat(notice().path("mileageBalanceAfter").asLong()).isEqualTo(2000);
    assertThat(notice().path("walletVersion").asLong())
        .isEqualTo(
            jdbc().queryForObject("SELECT version FROM payment.mileage_wallets", Long.class));
    assertThat(worker.runOnce()).isFalse();
  }

  @Test
  @DisplayName("여러 실행 슬롯이 동시에 돌아도 작업마다 한 번만 승인을 요청하고 모든 결제를 한 번씩 확정한다")
  void runsApprovalsConcurrentlyWithoutDuplicateCalls() throws Exception {
    for (int i = 0; i < 24; i++) {
      request(10_000 + i, 0, "CREDIT_CARD");
    }
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger maxInFlight = new AtomicInteger();
    gateway.approve =
        r -> {
          maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
          try {
            Thread.sleep(50);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            inFlight.decrementAndGet();
          }
          return new Result.Succeeded(UUID.randomUUID(), NOW);
        };

    ExecutorService slots = Executors.newFixedThreadPool(6);
    List<Future<?>> runs = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      runs.add(
          slots.submit(
              () -> {
                while (worker.runOnce()) {
                  // 도래한 작업이 없을 때까지 실행한다.
                }
              }));
    }
    for (Future<?> run : runs) {
      run.get(60, TimeUnit.SECONDS);
    }
    slots.shutdown();

    assertThat(
            jdbc()
                .queryForObject(
                    "SELECT count(*) FROM payment.payments WHERE status = 'CONFIRMED'", Long.class))
        .isEqualTo(24);
    assertThat(gateway.approvals).hasSize(24);
    assertThat(new HashSet<>(gateway.approvals.stream().map(ApprovalRequest::businessKey).toList()))
        .hasSize(24);
    assertThat(
            jdbc()
                .queryForObject(
                    "SELECT count(*) FROM payment.outbox WHERE event_type = 'PaymentConfirmed'",
                    Long.class))
        .isEqualTo(24);
    assertThat(maxInFlight.get()).isGreaterThan(1);
  }

  @Test
  @DisplayName("결제 행 잠금 경합이 길면 준비 단계는 대기 상한 안에 롤백하고 외부 승인을 호출하지 않는다")
  void rollsBackPrepareWhenPaymentRowLockIsContended() {
    request(10_000, 0, "CREDIT_CARD");
    lockPaymentRow();

    long started = System.nanoTime();
    assertThatThrownBy(worker::runOnce).isInstanceOf(PessimisticLockingFailureException.class);

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    assertThat(gateway.approvals).isEmpty();
    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.payment_attempts", Long.class))
        .isZero();
    assertThat(jdbc().queryForObject("SELECT status FROM payment.payments", String.class))
        .isEqualTo("PENDING");
  }

  @Test
  @DisplayName("결과 반영 중 결제 행 잠금 경합이 길면 대기 상한 안에 롤백하고 확정·마일리지 차감·이벤트를 남기지 않는다")
  void rollsBackRecordWhenPaymentRowLockIsContended() {
    fundWallet(5_000);
    request(10_000, 2_000, "CREDIT_CARD");
    gateway.approve =
        r -> {
          lockPaymentRow();
          return new Result.Succeeded(UUID.randomUUID(), NOW);
        };

    long started = System.nanoTime();
    assertThatThrownBy(worker::runOnce).isInstanceOf(PessimisticLockingFailureException.class);

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    assertThat(gateway.approvals).hasSize(1);
    assertThat(status()).isEqualTo("PROCESSING");
    assertThat(jdbc().queryForObject("SELECT status FROM payment.payment_attempts", String.class))
        .isEqualTo("PROCESSING");
    assertThat(
            jdbc()
                .queryForList(
                    "SELECT kind FROM payment.mileage_entries ORDER BY kind", String.class))
        .containsExactly("RESERVE");
    assertThat(outbox()).containsExactly("PaymentAccepted:0");
    assertThat(jobStatus()).isEqualTo("RUNNING");
  }

  /** 다른 트랜잭션에서 결제 행을 잠그고 정리 때까지 놓지 않는다. */
  private void lockPaymentRow() {
    try {
      lockHolder = dataSource.getConnection();
      lockHolder.setAutoCommit(false);
      try (Statement statement = lockHolder.createStatement()) {
        statement.execute("SELECT id FROM payment.payments FOR UPDATE");
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName("알림 Outbox 저장 실패는 차감·확정·원장을 롤백하고 원거래 조회로 한 번만 재개한다")
  void rollsBackFinancialResultWhenNoticeInsertFails() {
    fundWallet(5000);
    request(10000, 2000, "CREDIT_CARD");
    jdbc()
        .execute(
            "ALTER TABLE payment.outbox ADD CONSTRAINT reject_notice CHECK (event_type <> 'PaymentNoticeRequested')");
    try {
      assertThatThrownBy(worker::runOnce)
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
      assertThat(status()).isEqualTo("PROCESSING");
      assertThat(jdbc().queryForObject("SELECT balance FROM payment.mileage_wallets", Long.class))
          .isEqualTo(5000);
      assertThat(
              jdbc()
                  .queryForObject(
                      "SELECT count(*) FROM payment.mileage_entries WHERE kind='DEBIT'",
                      Long.class))
          .isZero();
      assertThat(outbox()).containsExactly("PaymentAccepted:0");
    } finally {
      jdbc().execute("ALTER TABLE payment.outbox DROP CONSTRAINT reject_notice");
    }
    gateway.lookup = r -> new Result.Succeeded(UUID.randomUUID(), NOW);
    expireLease();
    worker.runOnce();
    assertThat(status()).isEqualTo("CONFIRMED");
    assertThat(notice().path("mileageBalanceAfter").asLong()).isEqualTo(3000);
    assertThat(gateway.approvals).hasSize(1);
    assertThat(gateway.lookups).hasSize(1);
  }

  private com.fasterxml.jackson.databind.JsonNode notice() {
    try {
      return mapper
          .readTree(
              jdbc()
                  .queryForObject(
                      "SELECT payload::text FROM payment.outbox WHERE event_type='PaymentNoticeRequested'",
                      String.class))
          .path("payload");
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private String status() {
    return jdbc().queryForObject("SELECT status FROM payment.payments", String.class);
  }

  private String jobStatus() {
    return jdbc().queryForObject("SELECT status FROM payment.payment_jobs", String.class);
  }

  private List<String> outbox() {
    return jdbc()
        .queryForList(
            "SELECT event_type || ':' || aggregate_version FROM payment.outbox ORDER BY sequence_no",
            String.class);
  }

  private void makeJobDue() {
    jdbc()
        .update("UPDATE payment.payment_jobs SET next_run_at = clock_timestamp() - interval '1s'");
  }

  private void expireLease() {
    jdbc()
        .update("UPDATE payment.payment_jobs SET lease_until = clock_timestamp() - interval '1s'");
  }

  private void fundWallet(long balance) {
    jdbc()
        .update(
            "INSERT INTO payment.mileage_wallets"
                + " (id, buyer_id, balance, reserved_amount, created_at, updated_at, version)"
                + " VALUES (?, ?, ?, 0, now(), now(), 0)",
            UUID.randomUUID(),
            BUYER_ID,
            balance);
  }

  /** 첫 요청의 불변 합의를 그대로 두고 회차·주문 버전·이벤트 식별자만 바꾼다. */
  private UUID retry(UUID cycle, long version) throws IOException {
    var event =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            mapper.readTree(
                jdbc()
                    .queryForObject(
                        "SELECT payload::text FROM payment.inbox WHERE event_type='OrderPaymentRequested' ORDER BY created_at, id LIMIT 1",
                        String.class));
    UUID eventId = UUID.randomUUID();
    event.put("eventId", eventId.toString());
    event.put("aggregateVersion", version);
    var payload = (com.fasterxml.jackson.databind.node.ObjectNode) event.path("payload");
    payload.put("paymentCycleId", cycle.toString());
    payload.put("orderVersion", version);
    byte[] body = mapper.writeValueAsBytes(event);
    EventEnvelope<OrderPaymentRequestedPayload> parsed =
        mapper.readValue(body, new TypeReference<>() {});
    new TransactionTemplate(manager)
        .executeWithoutResult(
            s ->
                inbox.saveWaiting(
                    parsed, new Source("commerce-settle.commerce.v1", 0, version, body)));
    assertThat(requests.processEvent(eventId)).isTrue();
    makeJobDue();
    return eventId;
  }

  private void cancellation(UUID order, UUID cycle, long version) throws IOException {
    UUID eventId = UUID.randomUUID();
    var event = mapper.createObjectNode();
    event.put("eventId", eventId.toString());
    event.put("eventType", "PaymentCancellationRequested");
    event.put("eventVersion", 1);
    event.put("occurredAt", "2026-01-01T00:05:00Z");
    event.put("aggregateId", order.toString());
    event.put("aggregateVersion", version);
    var payload = event.putObject("payload");
    payload.put("orderId", order.toString());
    payload.put("paymentCycleId", cycle.toString());
    payload.put("orderVersion", version);
    payload.put("requestId", UUID.randomUUID().toString());
    byte[] body = mapper.writeValueAsBytes(event);
    EventEnvelope<PaymentCancellationRequestedPayload> parsed =
        mapper.readValue(body, new TypeReference<>() {});
    new TransactionTemplate(manager)
        .executeWithoutResult(
            s ->
                inbox.saveWaiting(
                    parsed, new Source("commerce-settle.commerce.v1", 0, version + 100, body)));
    assertThat(requests.processEvent(eventId)).isTrue();
  }

  /** 쿠폰 없는 결제 요청을 Inbox에 넣고 처리해 결제·승인 작업을 만든다. */
  private UUID request(long gross, long mileage, String method) {
    UUID orderId = UUID.randomUUID();
    long external = gross - mileage;
    String json =
        """
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
                UUID.randomUUID(),
                orderId,
                orderId,
                BUYER_ID,
                UUID.randomUUID(),
                method == null ? "null" : "\"" + method + "\"",
                method == null ? "null" : "\"card-ref\"",
                gross,
                gross,
                mileage,
                external,
                gross);
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    try {
      EventEnvelope<OrderPaymentRequestedPayload> event =
          mapper.readValue(body, new TypeReference<>() {});
      new TransactionTemplate(manager)
          .executeWithoutResult(
              s ->
                  inbox.saveWaiting(event, new Source("commerce-settle.commerce.v1", 0, 0L, body)));
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
    assertThat(requests.processNext()).isTrue();
    // 시험 시계는 미래로 고정돼 있어 작업의 처리 시각도 미래다. 임대는 DB 시각으로 판정하므로 바로 도래하게 한다.
    makeJobDue();
    return orderId;
  }

  /** 결과를 시험마다 정하는 PG 대역. 기본은 즉시 승인이다. */
  static final class FakeGateway implements PaymentGateway {
    final List<ApprovalRequest> approvals = Collections.synchronizedList(new ArrayList<>());
    final List<ApprovalRequest> lookups = Collections.synchronizedList(new ArrayList<>());
    Function<ApprovalRequest, Result> approve;
    Function<ApprovalRequest, Result> lookup;

    FakeGateway() {
      reset();
    }

    void reset() {
      approvals.clear();
      lookups.clear();
      approve = r -> new Result.Succeeded(UUID.randomUUID(), NOW);
      lookup = r -> new Result.NotFound();
    }

    @Override
    public Result approve(ApprovalRequest request) {
      approvals.add(request);
      return approve.apply(request);
    }

    @Override
    public Result lookup(ApprovalRequest request) {
      lookups.add(request);
      return lookup.apply(request);
    }
  }

  @TestConfiguration
  // 같은 패키지의 다른 테스트 설정을 읽지 않도록 저장소 구현만 등록한다.
  @ComponentScan(
      basePackages = "com.commercesettle.payment.infrastructure.persistence",
      useDefaultFilters = false,
      includeFilters = @ComponentScan.Filter(Repository.class))
  @Import({
    PaymentObservationsTestConfiguration.class,
    ApplyOrderPaymentRequestService.class,
    PaymentNotices.class,
    RefundEventHandler.class,
    MileageAccrualEventHandler.class,
    RefundEvents.class,
    RefundSettlementReplyService.class,
    ApprovalWorker.class,
    ApprovalResultRecorder.class
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

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    FakeGateway fakeGateway() {
      return new FakeGateway();
    }

    @Bean
    ApprovalWorkerSettings approvalWorkerSettings() {
      return new ApprovalWorkerSettings(
          Duration.ofSeconds(30),
          Duration.ofSeconds(5),
          10,
          "PG_A",
          "payout-funding-default",
          "pg-normal-v1");
    }
  }
}
