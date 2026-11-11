package com.commercesettle.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import com.commercesettle.payment.application.dto.ApprovalWorkerSettings;
import com.commercesettle.payment.application.dto.MileageEntryView;
import com.commercesettle.payment.application.dto.MileageWalletView;
import com.commercesettle.payment.application.dto.PageView;
import com.commercesettle.payment.application.dto.PaymentAttemptView;
import com.commercesettle.payment.application.dto.PaymentJobView;
import com.commercesettle.payment.application.dto.ReconciliationEventPageView;
import com.commercesettle.payment.application.dto.SettlementContextView;
import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.event.RefundFinancialCompletedPayload;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import com.commercesettle.payment.application.port.PaymentGateway;
import com.commercesettle.payment.application.port.PaymentGateway.ApprovalRequest;
import com.commercesettle.payment.application.port.PaymentGateway.Result;
import com.commercesettle.payment.application.port.ReconciliationEventReader.Position;
import com.commercesettle.payment.application.port.ReconciliationEventReader.ReconciliationEvent;
import com.commercesettle.payment.domain.exception.IdempotencyConflictException;
import com.commercesettle.payment.domain.exception.InvalidReconciliationCursorException;
import com.commercesettle.payment.domain.exception.PaymentJobNotFoundException;
import com.commercesettle.payment.domain.exception.PaymentNotFoundException;
import com.commercesettle.payment.domain.exception.PaymentStateConflictException;
import com.commercesettle.payment.domain.exception.ReconciliationCutoffNotReadyException;
import com.commercesettle.payment.domain.exception.VersionConflictException;
import com.commercesettle.payment.domain.model.MileageEntryKind;
import com.commercesettle.payment.domain.model.PaymentAttemptStatus;
import com.commercesettle.payment.domain.model.PaymentJobStatus;
import com.commercesettle.payment.support.PaymentObservationsTestConfiguration;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.UncategorizedSQLException;
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

// 실제 PostgreSQL에서 구매자 마일리지·승인 시도 조회, Settlement 계약 조회, 운영 작업 재처리를 확인한다.
@DataJpaTest(properties = "app.payment-reconciliation.safety-margin=PT10M")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PaymentReadAndOperationIntegrationTest.Config.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentReadAndOperationIntegrationTest {
  private static final Instant NOW = Instant.parse("2100-01-01T00:00:00Z");
  private static final UUID BUYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  private static final UUID OTHER_BUYER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
  private static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
  private static final String OPERATOR = "operator:ops-1";
  private static final Instant CUTOFF = Instant.parse("2026-01-01T00:00:00Z");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/payment.sql");

  @Autowired private ApplyOrderPaymentRequestService requests;
  @Autowired private ApprovalWorker worker;
  @Autowired private FakeGateway gateway;
  @Autowired private InboxStore inbox;
  @Autowired private MileageQueryService mileage;
  @Autowired private PaymentAttemptQueryService attempts;
  @Autowired private PaymentQueryService payments;
  @Autowired private ReconciliationQueryService reconciliation;
  @Autowired private PaymentJobOperationService jobs;
  @Autowired private ObjectMapper mapper;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager manager;

  private JdbcTemplate jdbc() {
    return new JdbcTemplate(dataSource);
  }

  @Test
  @DisplayName("예약액 합계가 맞아도 없는 결제의 마일리지 예약 참조를 탐지한다")
  void detectsOrphanMileageReservationWithMatchingReservedBalance() {
    fundWallet(5000);
    UUID wallet = jdbc().queryForObject("SELECT id FROM payment.mileage_wallets", UUID.class);
    jdbc()
        .update(
            "INSERT INTO payment.mileage_entries(id,wallet_id,kind,business_ref,amount,balance_after,created_at) VALUES(?,?,'SEED',?,5000,5000,?)",
            UUID.randomUUID(),
            wallet,
            wallet,
            NOW.atOffset(ZoneOffset.UTC));
    jdbc()
        .update(
            "INSERT INTO payment.mileage_reservations(id,wallet_id,payment_id,cycle_id,amount,status,created_at,updated_at,version) VALUES(?,?,?,?,1000,'RESERVED',?,?,0)",
            UUID.randomUUID(),
            wallet,
            UUID.randomUUID(),
            UUID.randomUUID(),
            NOW.atOffset(ZoneOffset.UTC),
            NOW.atOffset(ZoneOffset.UTC));
    jdbc().update("UPDATE payment.mileage_wallets SET reserved_amount=1000 WHERE id=?", wallet);
    var reader =
        new com.commercesettle.payment.infrastructure.persistence.JdbcPaymentIntegrityReader(
            jdbc());
    assertThat(reader.checks())
        .containsEntry("mileageBalance", 0L)
        .containsEntry("mileageReservations", 0L)
        .containsEntry("mileageReferences", 1L);
  }

  @Test
  @DisplayName("잔액 합계가 맞아도 환불 없는 마일리지 복원의 참조를 탐지한다")
  void detectsOrphanMileageRestoreWithMatchingBalance() {
    fundWallet(5000);
    UUID wallet = jdbc().queryForObject("SELECT id FROM payment.mileage_wallets", UUID.class);
    jdbc()
        .update(
            "INSERT INTO payment.mileage_entries(id,wallet_id,kind,business_ref,amount,balance_after,created_at) VALUES(?,?,'SEED',?,5000,5000,?)",
            UUID.randomUUID(),
            wallet,
            wallet,
            NOW.atOffset(ZoneOffset.UTC));
    request(10000, 1000, "CREDIT_CARD");
    assertThat(worker.runOnce()).isTrue();
    UUID payment = paymentId();
    var reader =
        new com.commercesettle.payment.infrastructure.persistence.JdbcPaymentIntegrityReader(
            jdbc());
    assertThat(reader.checks())
        .containsEntry("mileageReferences", 0L)
        .containsEntry("mileageBalance", 0L);
    UUID refund = UUID.randomUUID();
    jdbc()
        .update(
            "INSERT INTO payment.mileage_entries(id,wallet_id,payment_id,refund_id,kind,business_ref,amount,balance_after,created_at) VALUES(?,?,?,?,'RESTORE',?,1000,5000,?)",
            UUID.randomUUID(),
            wallet,
            payment,
            refund,
            payment,
            NOW.atOffset(ZoneOffset.UTC));
    jdbc().update("UPDATE payment.mileage_wallets SET balance=balance+1000 WHERE id=?", wallet);
    assertThat(reader.checks())
        .containsEntry("mileageBalance", 0L)
        .containsEntry("confirmedMileage", 0L)
        .containsEntry("refundAmounts", 0L)
        .containsEntry("mileageReferences", 1L);
    // 같은 결제·금액·복원 사실을 가진 유효한 부모가 있으면 참조는 일치한다.
    jdbc()
        .update(
            "INSERT INTO payment.refunds(id,payment_id,order_refund_request_id,external_amount,mileage_amount,status,business_key,external_status,external_ref,mileage_restored_at,created_at,updated_at,version) VALUES(?,?,?,9000,1000,'INTERNAL_PENDING',?,'SUCCEEDED',?,?,?,?,0)",
            refund,
            payment,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            NOW.atOffset(ZoneOffset.UTC),
            NOW.atOffset(ZoneOffset.UTC),
            NOW.atOffset(ZoneOffset.UTC));
    assertThat(reader.checks())
        .containsEntry("mileageReferences", 0L)
        .containsEntry("mileageBalance", 0L)
        .containsEntry("refundAmounts", 0L);
  }

  @Test
  @DisplayName("원천별 잔액·금액·참조 SQL은 자기 PostgreSQL에서 실행된다")
  void readsOwnerIntegrityFromOwnDatabase() {
    var reader =
        new com.commercesettle.payment.infrastructure.persistence.JdbcPaymentIntegrityReader(
            jdbc());
    assertThat(reader.checks().values()).allMatch(value -> value == 0L);
    assertThat(reader.page(CUTOFF, reader.now(), null, 101)).isEmpty();
    assertThat(reader.byKeys(List.of(UUID.randomUUID()), CUTOFF, reader.now())).isEmpty();
    assertThat(reader.count(CUTOFF, reader.now())).isZero();
  }

  @Test
  @DisplayName("원문 나노초는 원장과 같은 마이크로초로 읽고 DB 반올림으로 거짓 차이를 만들지 않는다")
  void normalizesSourceTimestampWithoutChangingImmutableBytes() {
    Instant original = CUTOFF.minusSeconds(1).plusNanos(123456789);
    UUID id = confirmedEvent(original);
    UUID atBoundary = confirmedEvent(CUTOFF.plusNanos(789));
    confirmedEvent(CUTOFF.plusNanos(1000));
    var page = reconciliation.read(CUTOFF, null, 100);
    assertThat(page.items()).hasSize(2);
    assertThat(
            page.items().stream()
                .filter(e -> e.eventId().equals(id))
                .findFirst()
                .orElseThrow()
                .occurredAt())
        .isEqualTo(original.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    assertThat(
            page.items().stream()
                .filter(e -> e.eventId().equals(atBoundary))
                .findFirst()
                .orElseThrow()
                .occurredAt())
        .isEqualTo(CUTOFF);
    assertThat(
            jdbc()
                .queryForObject(
                    "SELECT payload->>'occurredAt' FROM payment.outbox WHERE id=?",
                    String.class,
                    id))
        .isEqualTo(original.toString());
  }

  @AfterEach
  void deleteRows() {
    gateway.reset();
    for (String table :
        List.of(
            "audit_logs",
            "idempotency_records",
            "mileage_entries",
            "mileage_reservations",
            "mileage_wallets",
            "refunds",
            "payment_attempts",
            "payment_jobs",
            "payments",
            "inbox",
            "outbox")) {
      jdbc().update("DELETE FROM payment." + table);
    }
  }

  @Test
  @DisplayName("승인된 혼합 결제의 마일리지 잔액·원장과 승인 시도를 본인에게만 보여 준다")
  void showsOwnMileageAndAttempts() {
    fundWallet(5_000);
    request(10_000, 2_000, "EASY_PAY");
    worker.runOnce();
    UUID paymentId = paymentId();

    assertThat(mileage.wallet(BUYER_ID)).isEqualTo(new MileageWalletView(3_000, 0, 3_000, 2));
    PageView<MileageEntryView> entries = mileage.entries(BUYER_ID, 0, 1);
    assertThat(entries.totalElements()).isEqualTo(2);
    assertThat(entries.totalPages()).isEqualTo(2);
    assertThat(entries.items()).hasSize(1);
    assertThat(mileage.entries(BUYER_ID, 0, 20).items())
        .extracting(MileageEntryView::kind, MileageEntryView::paymentId)
        .containsExactlyInAnyOrder(
            tuple(MileageEntryKind.RESERVE, paymentId), tuple(MileageEntryKind.DEBIT, paymentId));

    assertThat(mileage.wallet(OTHER_BUYER)).isEqualTo(MileageWalletView.EMPTY);
    assertThat(mileage.entries(OTHER_BUYER, 0, 20).items()).isEmpty();

    PageView<PaymentAttemptView> page = attempts.list(BUYER_ID, paymentId, 0, 20);
    assertThat(page.totalElements()).isEqualTo(1);
    assertThat(page.items().getFirst())
        .extracting(
            PaymentAttemptView::providerId,
            PaymentAttemptView::method,
            PaymentAttemptView::amount,
            PaymentAttemptView::status)
        .containsExactly("EASY_PAY", "EASY_PAY", 8_000L, PaymentAttemptStatus.APPROVED);
    assertThatThrownBy(() -> attempts.list(OTHER_BUYER, paymentId, 0, 20))
        .isInstanceOf(PaymentNotFoundException.class);
  }

  @Test
  @DisplayName("정산 컨텍스트는 확정 결제의 원금액과 최초 수수료·판매자 순액을 주고, 확정 전 결제는 거절한다")
  void providesSettlementContextOnlyForConfirmedPayment() {
    request(10_000, 0, "CREDIT_CARD");
    UUID paymentId = paymentId();

    assertThatThrownBy(() -> payments.settlementContext(paymentId))
        .isInstanceOf(PaymentStateConflictException.class);
    assertThatThrownBy(() -> payments.settlementContext(UUID.randomUUID()))
        .isInstanceOf(PaymentNotFoundException.class);

    worker.runOnce();

    SettlementContextView context = payments.settlementContext(paymentId);
    assertThat(context.feeAmount()).isEqualTo(1_500);
    assertThat(context.sellerNetAmount()).isEqualTo(8_500);
    assertThat(context.sellerId()).isEqualTo(SELLER_ID);
    assertThat(context.policyVersion()).isEqualTo("test-v1");
    assertThat(context.payment().method()).isEqualTo("CREDIT_CARD");
    assertThat(context.payment().settlementBaseAmount()).isEqualTo(10_000);
  }

  @Test
  @DisplayName("대조 조회는 마감 이하 결제 확정을 발행 여부와 무관하게 주고, cursor 페이지는 최초 기준선 집합을 유지한다")
  void keepsReconciliationBaselineAcrossPages() {
    UUID published = confirmedEvent(CUTOFF.minusSeconds(3));
    confirmedEvent(CUTOFF.minusSeconds(2));
    confirmedEvent(CUTOFF);
    confirmedEvent(CUTOFF.plusSeconds(1));
    otherEvent(CUTOFF.minusSeconds(1));
    jdbc()
        .update(
            "UPDATE payment.outbox SET status = 'PUBLISHED', published_partition = 2,"
                + " published_offset = 41, published_at = clock_timestamp() WHERE id = ?",
            published);

    ReconciliationEventPageView first = reconciliation.read(CUTOFF, null, 2);

    assertThat(first.items()).hasSize(2);
    assertThat(first.page()).isZero();
    assertThat(first.totalElements()).isEqualTo(3);
    assertThat(first.totalPages()).isEqualTo(2);
    assertThat(first.nextCursor()).isNotNull();
    assertThat(first.items())
        .allSatisfy(
            event -> {
              assertThat(event.published()).isFalse();
              assertThat(event.partition()).isNull();
              assertThat(event.topic()).isEqualTo("commerce-settle.payment.v1");
              assertThat(event.externalAmount()).isEqualTo(9_000);
              assertThat(event.feeAmount()).isEqualTo(1_500);
            });

    // 첫 페이지 재개도 같은 기준선으로 읽으며 이후 이벤트를 끼워 넣지 않는다.
    var replay = reconciliation.read(CUTOFF, first.snapshotAt(), null, 2);
    assertThat(replay.items()).isEqualTo(first.items());

    // 기준선 시각 뒤에 저장된 이벤트는 마감 이하여도 다음 페이지에 끼어들지 않는다.
    confirmedEvent(CUTOFF.minusSeconds(10));
    ReconciliationEventPageView second = reconciliation.read(CUTOFF, first.nextCursor(), 2);

    assertThat(second.page()).isEqualTo(1);
    assertThat(second.totalElements()).isEqualTo(3);
    assertThat(second.snapshotAt()).isEqualTo(first.snapshotAt());
    assertThat(second.nextCursor()).isNull();
    assertThat(second.items()).extracting(ReconciliationEvent::eventId).containsExactly(published);
    ReconciliationEvent position = second.items().getFirst();
    assertThat(position.published()).isTrue();
    assertThat(position.partition()).isEqualTo(2);
    assertThat(position.offset()).isEqualTo(41L);
    assertThat(position.publishedAt()).isNotNull();

    assertThatThrownBy(() -> reconciliation.read(CUTOFF, first.nextCursor(), 3))
        .isInstanceOf(InvalidReconciliationCursorException.class);
    assertThatThrownBy(() -> reconciliation.read(CUTOFF.minusSeconds(1), first.nextCursor(), 2))
        .isInstanceOf(InvalidReconciliationCursorException.class);
    assertThatThrownBy(() -> reconciliation.read(CUTOFF, "not-a-cursor", 2))
        .isInstanceOf(InvalidReconciliationCursorException.class);
  }

  @Test
  @DisplayName("미래 마감과 안전 여유가 지나지 않은 마감은 기준선을 고정하지 않는다")
  void rejectsCutoffBeforeSafetyMargin() {
    Instant dbNow = jdbc().queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();

    assertThatThrownBy(() -> reconciliation.read(dbNow.plusSeconds(60), null, 10))
        .isInstanceOf(ReconciliationCutoffNotReadyException.class);
    assertThatThrownBy(() -> reconciliation.read(dbNow.minus(Duration.ofMinutes(5)), null, 10))
        .isInstanceOf(ReconciliationCutoffNotReadyException.class);
    assertThat(reconciliation.read(dbNow.minus(Duration.ofMinutes(11)), null, 10).items())
        .isEmpty();
  }

  @Test
  @DisplayName("환불 완료도 발행 전후 모두 조회하며 최초 확정 Outbox의 원금액을 유지한다")
  void includesRefundWithOriginalFinancialAmounts() throws Exception {
    UUID payment = UUID.randomUUID();
    UUID confirmed = originalEvent(payment);
    UUID refund = UUID.randomUUID();
    UUID eventId = refundEvent(payment, refund, 9_000, 1_000);

    ReconciliationEventPageView first = reconciliation.read(CUTOFF, null, 1);

    assertThat(first.totalElements()).isEqualTo(2);
    ReconciliationEvent event = first.items().getFirst();
    assertThat(event.eventId()).isEqualTo(eventId);
    assertThat(event.eventType()).isEqualTo("RefundFinancialCompleted");
    assertThat(event.paymentId()).isEqualTo(payment);
    assertThat(event.refundId()).isEqualTo(refund);
    assertThat(event.externalAmount()).isEqualTo(9_000);
    assertThat(event.mileageAmount()).isEqualTo(1_000);
    assertThat(event.platformCouponAmount()).isEqualTo(500);
    assertThat(event.settlementBaseAmount()).isEqualTo(10_500);
    assertThat(event.feeAmount()).isEqualTo(1_500);
    assertThat(event.sellerNetAmount()).isEqualTo(9_000);
    assertThat(event.published()).isFalse();
    assertThat(event.partition()).isNull();
    assertThat(event.offset()).isNull();
    assertThat(event.publishedAt()).isNull();
    assertThat(reconciliation.read(CUTOFF, first.nextCursor(), 1).items())
        .extracting(ReconciliationEvent::eventId)
        .containsExactly(confirmed);

    jdbc()
        .update(
            "UPDATE payment.outbox SET status = 'PUBLISHED', published_partition = 2,"
                + " published_offset = 42, published_at = clock_timestamp() WHERE id = ?",
            eventId);
    ReconciliationEvent published = reconciliation.read(CUTOFF, null, 1).items().getFirst();
    assertThat(published.published()).isTrue();
    assertThat(published.partition()).isEqualTo(2);
    assertThat(published.offset()).isEqualTo(42L);
    assertThat(published.publishedAt()).isNotNull();
    assertThat(published.feeAmount()).isEqualTo(event.feeAmount());
    assertThat(published.sellerNetAmount()).isEqualTo(event.sellerNetAmount());
  }

  @Test
  @DisplayName("페이지 밖 환불이라도 최초 확정 원천이 없으면 빈 값 대신 대조 불가로 중단한다")
  void rejectsRefundWithoutOriginalOutsidePage() throws Exception {
    refundEvent(UUID.randomUUID(), UUID.randomUUID(), 9_000, 1_000);
    confirmedEvent(CUTOFF);

    assertThatThrownBy(() -> reconciliation.read(CUTOFF, null, 1))
        .isInstanceOf(TransientDataAccessResourceException.class);
  }

  @ParameterizedTest
  @CsvSource({"9001,1000", "9000,1001"})
  @DisplayName("환불의 외부·마일리지 금액이 최초 확정과 다르면 대조를 중단한다")
  void rejectsRefundAmountMismatch(long external, long mileageAmount) throws Exception {
    UUID payment = UUID.randomUUID();
    originalEvent(payment);
    refundEvent(payment, UUID.randomUUID(), external, mileageAmount);

    assertThatThrownBy(() -> reconciliation.read(CUTOFF, null, 10))
        .isInstanceOf(TransientDataAccessResourceException.class);
  }

  @Test
  @DisplayName("같은 결제의 최초 확정 원천이 여러 개면 임의의 금액을 선택하지 않는다")
  void rejectsAmbiguousOriginalForRefund() throws Exception {
    UUID payment = UUID.randomUUID();
    originalEvent(payment);
    originalEvent(payment);
    refundEvent(payment, UUID.randomUUID(), 9_000, 1_000);

    assertThatThrownBy(() -> reconciliation.read(CUTOFF, null, 10))
        .isInstanceOf(TransientDataAccessResourceException.class);
  }

  @Test
  @DisplayName("cursor 기준선 뒤 저장된 확정 원천으로 과거 환불의 누락 금액을 채우지 않는다")
  void rejectsOriginalCreatedAfterSnapshot() throws Exception {
    Instant snapshot =
        jdbc()
            .queryForObject("SELECT clock_timestamp()", Timestamp.class)
            .toInstant()
            .minusSeconds(60);
    UUID payment = UUID.randomUUID();
    originalEvent(payment);
    UUID refund = refundEvent(payment, UUID.randomUUID(), 9_000, 1_000);
    jdbc()
        .update(
            "UPDATE payment.outbox SET created_at = ? WHERE id = ?",
            Timestamp.from(snapshot.minusSeconds(1)),
            refund);
    String cursor =
        new ReconciliationQueryService.Cursor(
                snapshot, CUTOFF, 1, 1, new Position(snapshot, UUID.randomUUID()))
            .encode();

    assertThatThrownBy(() -> reconciliation.read(CUTOFF, cursor, 1))
        .isInstanceOf(TransientDataAccessResourceException.class);
  }

  private UUID originalEvent(UUID payment) {
    UUID event = UUID.randomUUID();
    insertOutbox(
        event,
        "PaymentConfirmed",
        CUTOFF.minusSeconds(1),
        payment,
        """
        {"paymentId":"%s","externalAmount":9000,"mileageAmount":1000,"platformCouponAmount":500,
         "settlementBaseAmount":10500,"feeAmount":1500,"sellerNetAmount":9000}
        """
            .formatted(payment));
    return event;
  }

  private UUID refundEvent(UUID payment, UUID refund, long external, long mileageAmount)
      throws Exception {
    UUID event = UUID.randomUUID();
    insertOutbox(
        event,
        "RefundFinancialCompleted",
        CUTOFF,
        payment,
        mapper.writeValueAsString(
            new RefundFinancialCompletedPayload(
                refund,
                payment,
                external,
                mileageAmount,
                OffsetDateTime.ofInstant(CUTOFF, ZoneOffset.UTC))));
    return event;
  }

  @Test
  @DisplayName("운영 재처리는 미확정 승인 작업을 같은 업무 키로 다시 대기시키고, 다음 실행은 재승인 없이 원거래를 조회한다")
  void replaysReviewedJobWithOriginalBusinessKey() {
    request(10_000, 0, "CREDIT_CARD");
    gateway.approve = r -> new Result.Unconfirmed("timeout");
    worker.runOnce();
    jdbc()
        .update(
            "UPDATE payment.payment_jobs SET status = 'REVIEW_REQUIRED', attempts = 10,"
                + " last_error_code = 'PAYMENT_APPROVAL_ATTEMPTS_EXHAUSTED'");
    PaymentJobView reviewed = jobs.list(0, 20).items().getFirst();

    PaymentJobView replayed =
        jobs.replay(OPERATOR, reviewed.id(), "replay-key-0001", reviewed.version(), "PG_RECOVERED");

    assertThat(replayed.status()).isEqualTo(PaymentJobStatus.PENDING);
    assertThat(replayed.attempts()).isZero();
    assertThat(replayed.version()).isEqualTo(reviewed.version() + 1);
    assertThat(replayed.lastErrorCode()).isEqualTo("PAYMENT_APPROVAL_ATTEMPTS_EXHAUSTED");
    assertThat(
            jdbc()
                .queryForMap(
                    "SELECT actor_kind, actor_ref, action, before_status, after_status, reason_code"
                        + " FROM payment.audit_logs"))
        .containsEntry("actor_kind", "OPERATOR")
        .containsEntry("actor_ref", OPERATOR)
        .containsEntry("action", "PAYMENT_JOB_REPLAYED")
        .containsEntry("before_status", "REVIEW_REQUIRED")
        .containsEntry("after_status", "PENDING")
        .containsEntry("reason_code", "PG_RECOVERED");

    // 같은 키·본문의 재요청은 작업을 다시 바꾸지 않고 현재 작업을 돌려준다.
    assertThat(
            jobs.replay(
                OPERATOR, reviewed.id(), "replay-key-0001", reviewed.version(), "PG_RECOVERED"))
        .isEqualTo(replayed);
    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.audit_logs", Long.class))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                jobs.replay(
                    OPERATOR, reviewed.id(), "replay-key-0001", reviewed.version(), "OTHER_REASON"))
        .isInstanceOf(IdempotencyConflictException.class);
    assertThatThrownBy(
            () ->
                jobs.replay(
                    OPERATOR, reviewed.id(), "replay-key-0002", reviewed.version(), "PG_RECOVERED"))
        .isInstanceOf(VersionConflictException.class);

    gateway.lookup = r -> new Result.Succeeded(UUID.randomUUID(), NOW);
    assertThat(worker.runOnce()).isTrue();

    assertThat(jdbc().queryForObject("SELECT status FROM payment.payments", String.class))
        .isEqualTo("CONFIRMED");
    assertThat(gateway.approvals).hasSize(1);
    assertThat(gateway.lookups).hasSize(1);
    assertThat(gateway.lookups.getFirst().businessKey())
        .isEqualTo(gateway.approvals.getFirst().businessKey());
  }

  @Test
  @DisplayName("완료한 작업, 유효한 임대 중인 작업, 없는 작업은 재처리하지 않는다")
  void rejectsReplayOfDoneLeasedOrMissingJob() {
    request(10_000, 0, "CREDIT_CARD");
    jdbc()
        .update(
            "UPDATE payment.payment_jobs SET status = 'RUNNING', lease_owner = 'other',"
                + " lease_token = gen_random_uuid(),"
                + " lease_until = clock_timestamp() + interval '1 minute'");
    PaymentJobView leased = jobs.list(0, 20).items().getFirst();

    assertThatThrownBy(
            () -> jobs.replay(OPERATOR, leased.id(), "replay-key-0003", leased.version(), "RETRY"))
        .isInstanceOf(PaymentStateConflictException.class);

    jdbc()
        .update(
            "UPDATE payment.payment_jobs SET status = 'DONE', lease_owner = NULL,"
                + " lease_token = NULL, lease_until = NULL");
    assertThatThrownBy(
            () -> jobs.replay(OPERATOR, leased.id(), "replay-key-0004", leased.version(), "RETRY"))
        .isInstanceOf(PaymentStateConflictException.class);
    assertThatThrownBy(
            () -> jobs.replay(OPERATOR, UUID.randomUUID(), "replay-key-0005", 0, "RETRY"))
        .isInstanceOf(PaymentJobNotFoundException.class);
    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.audit_logs", Long.class))
        .isZero();
    assertThat(
            jdbc().queryForObject("SELECT count(*) FROM payment.idempotency_records", Long.class))
        .isZero();
  }

  @Test
  @DisplayName("같은 키·본문의 동시 재처리는 작업을 한 번만 바꾸고 같은 결과를 반환한다")
  void serializesConcurrentReplayWithSameBody() throws Exception {
    request(10_000, 0, "CREDIT_CARD");
    PaymentJobView job = jobs.list(0, 20).items().getFirst();

    List<Object> results = concurrentReplays(job, job, "PG_RECOVERED", "PG_RECOVERED");

    assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(PaymentJobView.class));
    assertThat(results.get(0)).isEqualTo(results.get(1));
    assertThat(jobs.list(0, 20).items().getFirst().version()).isEqualTo(job.version() + 1);
    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.audit_logs", Long.class))
        .isEqualTo(1);
    assertThat(
            jdbc().queryForObject("SELECT count(*) FROM payment.idempotency_records", Long.class))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("다른 작업에 같은 키를 동시에 쓰면 한 작업만 재처리하고 나머지는 본문 충돌로 거절한다")
  void rejectsConcurrentReplayKeyReuseForDifferentJobs() throws Exception {
    request(10_000, 0, "CREDIT_CARD");
    request(20_000, 0, "CREDIT_CARD");
    List<PaymentJobView> original = jobs.list(0, 20).items();

    List<Object> results = concurrentReplays(original.get(0), original.get(1), "RETRY", "RETRY");

    assertThat(results.stream().filter(PaymentJobView.class::isInstance)).hasSize(1);
    assertThat(results.stream().filter(IdempotencyConflictException.class::isInstance)).hasSize(1);
    assertThat(jdbc().queryForObject("SELECT sum(version) FROM payment.payment_jobs", Long.class))
        .isEqualTo(original.stream().mapToLong(PaymentJobView::version).sum() + 1);
    assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.audit_logs", Long.class))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("미래 마감은 유효 형식의 cursor가 있어도 안전 여유 검사에서 거절한다")
  void rejectsFutureCutoffWithCursor() {
    Instant dbNow = jdbc().queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    Instant cutoff = dbNow.plusSeconds(60);
    Instant snapshot = cutoff.plus(Duration.ofMinutes(10));
    String cursor =
        new ReconciliationQueryService.Cursor(
                snapshot, cutoff, 2, 1, new Position(snapshot, UUID.randomUUID()))
            .encode();

    assertThatThrownBy(() -> reconciliation.read(cutoff, cursor, 2))
        .isInstanceOf(ReconciliationCutoffNotReadyException.class);
  }

  @Test
  @DisplayName("준비된 마감도 미래 snapshotAt을 담은 cursor는 거절한다")
  void rejectsFutureSnapshotForReadyCutoff() {
    Instant dbNow = jdbc().queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    Instant cutoff = dbNow.minus(Duration.ofMinutes(11));
    Instant snapshot = dbNow.plusSeconds(60);
    String cursor =
        new ReconciliationQueryService.Cursor(
                snapshot, cutoff, 2, 1, new Position(snapshot, UUID.randomUUID()))
            .encode();

    assertThatThrownBy(() -> reconciliation.read(cutoff, cursor, 2))
        .isInstanceOf(InvalidReconciliationCursorException.class);
  }

  /** 작업 행을 잠근 동안 두 요청을 시작해, 원래 구현에서 두 멱등 조회가 모두 끝난 경합을 재현한다. */
  private List<Object> concurrentReplays(
      PaymentJobView first, PaymentJobView second, String firstReason, String secondReason)
      throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (Connection lock = dataSource.getConnection();
        var executor = Executors.newFixedThreadPool(2)) {
      lock.setAutoCommit(false);
      try (var statement =
          lock.prepareStatement("SELECT id FROM payment.payment_jobs FOR UPDATE")) {
        statement.executeQuery().close();
      }
      var one = executor.submit(() -> replayAfterStart(first, firstReason, ready, start));
      var two = executor.submit(() -> replayAfterStart(second, secondReason, ready, start));
      try {
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        await()
            .pollInterval(Duration.ofMillis(10))
            .atMost(Duration.ofMillis(900))
            .untilAsserted(
                () ->
                    assertThat(
                            jdbc()
                                .queryForObject(
                                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                                        + " AND wait_event_type = 'Lock'",
                                    Long.class))
                        .isEqualTo(2));
      } finally {
        start.countDown();
        lock.rollback();
      }
      return List.of(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS));
    }
  }

  private Object replayAfterStart(
      PaymentJobView job, String reason, CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    start.await();
    try {
      return jobs.replay(OPERATOR, job.id(), "concurrent-replay-key", job.version(), reason);
    } catch (RuntimeException failure) {
      return failure;
    }
  }

  @Test
  @DisplayName("작업 잠금 대기 상한을 넘으면 멱등·감사 기록을 롤백하고 같은 키로 다시 재처리할 수 있다")
  void rollsBackReplayAfterJobLockTimeout() throws Exception {
    request(10_000, 0, "CREDIT_CARD");
    PaymentJobView job = jobs.list(0, 20).items().getFirst();
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (var statement =
          lock.prepareStatement("SELECT id FROM payment.payment_jobs FOR UPDATE")) {
        statement.executeQuery().close();
      }

      assertThatThrownBy(
              () -> jobs.replay(OPERATOR, job.id(), "timeout-replay-key", job.version(), "RETRY"))
          .isInstanceOf(UncategorizedSQLException.class)
          .rootCause()
          .extracting(failure -> ((SQLException) failure).getSQLState())
          .isEqualTo("55P03");
      assertThat(
              jdbc().queryForObject("SELECT count(*) FROM payment.idempotency_records", Long.class))
          .isZero();
      assertThat(jdbc().queryForObject("SELECT count(*) FROM payment.audit_logs", Long.class))
          .isZero();
      assertThat(jobs.list(0, 20).items().getFirst()).isEqualTo(job);
      lock.rollback();
    }

    assertThat(
            jobs.replay(OPERATOR, job.id(), "timeout-replay-key", job.version(), "RETRY").version())
        .isEqualTo(job.version() + 1);
  }

  private UUID paymentId() {
    return jdbc().queryForObject("SELECT id FROM payment.payments", UUID.class);
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

  /** 대조 원천인 결제 확정 Outbox 행을 원천 시각을 정해 넣는다. */
  private UUID confirmedEvent(Instant occurredAt) {
    UUID eventId = UUID.randomUUID();
    insertOutbox(
        eventId,
        "PaymentConfirmed",
        occurredAt,
        """
        {"paymentId":"%s","externalAmount":9000,"mileageAmount":1000,"platformCouponAmount":0,
         "settlementBaseAmount":10000,"feeAmount":1500,"sellerNetAmount":8500}
        """
            .formatted(UUID.randomUUID()));
    return eventId;
  }

  private void otherEvent(Instant occurredAt) {
    insertOutbox(
        UUID.randomUUID(),
        "PaymentAccepted",
        occurredAt,
        "{\"paymentId\":\"%s\"}".formatted(UUID.randomUUID()));
  }

  private void insertOutbox(UUID eventId, String type, Instant occurredAt, String payload) {
    insertOutbox(eventId, type, occurredAt, eventId, payload);
  }

  private void insertOutbox(
      UUID eventId, String type, Instant occurredAt, UUID aggregateId, String payload) {
    String json =
        """
        {"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"%s",
         "aggregateId":"%s","aggregateVersion":0,"payload":%s}
        """
            .formatted(eventId, type, occurredAt, aggregateId, payload.strip());
    jdbc()
        .update(
            "INSERT INTO payment.outbox (id, event_type, event_version, aggregate_id,"
                + " aggregate_version, topic, occurred_at, message_key, payload, raw_body)"
                + " VALUES (?, ?, 1, ?, 0, 'commerce-settle.payment.v1', ?, ?, CAST(? AS jsonb), ?)",
            eventId,
            type,
            aggregateId,
            Timestamp.from(occurredAt),
            aggregateId.toString(),
            json,
            json.getBytes(StandardCharsets.UTF_8));
  }

  /** 쿠폰 없는 결제 요청을 Inbox에 넣고 처리해 결제·승인 작업을 만든다. */
  private void request(long gross, long mileageAmount, String method) {
    UUID orderId = UUID.randomUUID();
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
            "sellerId": "%s",
            "paymentCycleId": "%s",
            "productId": "00000000-0000-0000-0000-0000000000a5",
            "orderVersion": 0,
            "status": "PAYMENT_PENDING",
            "policyVersion": "test-v1",
            "paymentWaitUntil": "2026-01-01T00:10:00Z",
            "externalMethod": "%s",
            "methodAccountRef": "card-ref",
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
                SELLER_ID,
                UUID.randomUUID(),
                method,
                gross,
                gross,
                mileageAmount,
                gross - mileageAmount,
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
    jdbc().update("UPDATE payment.payment_jobs SET next_run_at = clock_timestamp()");
  }

  /** 결과를 시험마다 정하는 PG 대역. 기본은 즉시 승인이다. */
  static final class FakeGateway implements PaymentGateway {
    final List<ApprovalRequest> approvals = new ArrayList<>();
    final List<ApprovalRequest> lookups = new ArrayList<>();
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
    RefundEvents.class,
    RefundSettlementReplyService.class,
    ApprovalWorker.class,
    ApprovalResultRecorder.class,
    MileageQueryService.class,
    PaymentAttemptQueryService.class,
    PaymentQueryService.class,
    ReconciliationQueryService.class,
    PaymentJobOperationService.class
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
