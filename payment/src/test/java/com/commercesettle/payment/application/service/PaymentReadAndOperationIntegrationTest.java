package com.commercesettle.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import com.commercesettle.payment.application.port.PaymentGateway;
import com.commercesettle.payment.application.port.PaymentGateway.ApprovalRequest;
import com.commercesettle.payment.application.port.PaymentGateway.Result;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
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

// 실제 PostgreSQL에서 구매자 마일리지·승인 시도 조회, Settlement 계약 조회, 운영 작업 재처리를 확인한다.
@DataJpaTest(properties = "app.payment-reconciliation.safety-margin=PT10M")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PaymentReadAndOperationIntegrationTest.Config.class)
@Testcontainers
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
    String json =
        """
        {"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"%s",
         "aggregateId":"%s","aggregateVersion":0,"payload":%s}
        """
            .formatted(eventId, type, occurredAt, eventId, payload.strip());
    jdbc()
        .update(
            "INSERT INTO payment.outbox (id, event_type, event_version, aggregate_id,"
                + " aggregate_version, topic, occurred_at, message_key, payload, raw_body)"
                + " VALUES (?, ?, 1, ?, 0, 'commerce-settle.payment.v1', ?, ?, CAST(? AS jsonb), ?)",
            eventId,
            type,
            eventId,
            Timestamp.from(occurredAt),
            eventId.toString(),
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
    ApplyOrderPaymentRequestService.class,
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
          Duration.ofSeconds(5),
          10,
          "PG_A",
          "payout-funding-default",
          "pg-normal-v1");
    }
  }
}
