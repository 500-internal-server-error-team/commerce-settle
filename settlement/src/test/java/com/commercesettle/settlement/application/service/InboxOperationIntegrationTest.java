package com.commercesettle.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.event.PaymentConfirmedPayload;
import com.commercesettle.settlement.application.event.PurchaseConfirmedPayload;
import com.commercesettle.settlement.application.event.RefundFinancialCompletedPayload;
import com.commercesettle.settlement.application.event.RefundReleaseRequestedPayload;
import com.commercesettle.settlement.application.event.RefundReservationRequestedPayload;
import com.commercesettle.settlement.application.exception.OperationRejectedException;
import com.commercesettle.settlement.application.port.InboxOperationStore;
import com.commercesettle.settlement.application.port.InboxOperationStore.Claim;
import com.commercesettle.settlement.application.port.InboxOperationStore.ExpectedTarget;
import com.commercesettle.settlement.application.port.InboxOperationStore.Filter;
import com.commercesettle.settlement.application.port.InboxStore;
import com.commercesettle.settlement.domain.model.InboxReviewReason;
import com.commercesettle.settlement.infrastructure.observability.FinancialQueueMetrics;
import com.commercesettle.settlement.support.SettlementEventTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123",
      "app.internal-auth.senders[operator\\:ops].current=test-operator-credential-0123456789abcdef0123",
      "app.internal-auth.senders[service\\:commerce].current=test-commerce-retention-credential-0123456789abcdef",
      "settlement.payout.mock-credential=test-settlement-credential-0123456789abcdef0123",
      "settlement.daily-batch.schedule-enabled=false",
      "settlement.payout-worker.enabled=false",
      "settlement.inbox-processor.enabled=false",
      "settlement.inbox-processor.interval-ms=0",
      "settlement.inbox-rejudgement.enabled=false",
      "app.outbox.enabled=false",
      "app.kafka.settlement-events.enabled=true",
      "spring.kafka.consumer.auto-offset-reset=earliest"
    })
@Import(SettlementEventTopics.class)
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InboxOperationIntegrationTest {
  private static final String OPERATOR = "test-operator-credential-0123456789abcdef0123";
  private static final String GATEWAY = "test-gateway-credential-0123456789abcdef0123";
  private static final Instant BEFORE = Instant.parse("2099-01-01T00:00:00Z");
  private static final String RUNS = "/internal/settlement-inbox-rejudgements";
  private static final OffsetDateTime DELIVERED = OffsetDateTime.parse("2026-10-20T00:00:00Z");
  private static final OffsetDateTime CONFIRMED =
      DELIVERED.plusDays(7).plusSeconds(1).plusNanos(123456789);

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.2");

  @DynamicPropertySource
  static void kafka(DynamicPropertyRegistry r) {
    r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }

  @Autowired InboxOperationService ops;
  @Autowired SettlementEventService events;
  @Autowired RefundEventService refunds;
  @Autowired InboxStore inbox;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired MockMvc mvc;
  @Autowired TransactionTemplate tx;
  @Autowired DataSource dataSource;
  @Autowired KafkaTemplate<String, byte[]> template;
  private final AtomicLong offset = new AtomicLong();
  private final UUID payment = UUID.randomUUID();
  private final UUID order = UUID.randomUUID();
  private final UUID seller = UUID.randomUUID();

  @AfterEach
  void clear() {
    jdbc.execute("DROP FUNCTION IF EXISTS settlement.clock_timestamp()");
    jdbc.execute("DROP TRIGGER IF EXISTS fail_rejudgement ON settlement.audit_logs");
    jdbc.execute("DROP FUNCTION IF EXISTS settlement.fail_rejudgement()");
    jdbc.execute("DROP TRIGGER IF EXISTS hold_rejudgement ON settlement.audit_logs");
    jdbc.execute("DROP FUNCTION IF EXISTS settlement.hold_rejudgement()");
    for (String t :
        List.of(
            "inbox_rejudgement_attempts",
            "inbox_rejudgement_targets",
            "inbox_rejudgement_runs",
            "audit_logs",
            "idempotency_records",
            "outbox",
            "settlement_entries",
            "settlement_reservations",
            "settlement_items",
            "inbox_conflicts",
            "inbox")) jdbc.update("DELETE FROM settlement." + t);
  }

  @Test
  @DisplayName("원문 없이 사유·이벤트별 요약과 현재 버전을 조회하고 운영자 이외 자격은 거절한다")
  void observesReasonsAndRestrictsCredentials() throws Exception {
    var e = purchase();
    UUID id = review(e, InboxReviewReason.PAYMENT_NOT_ARRIVED);
    mvc.perform(
            get("/internal/settlement-inbox-summary").header("Authorization", "Bearer " + OPERATOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].reasonCode").value("PAYMENT_NOT_ARRIVED"))
        .andExpect(jsonPath("$[0].category").value("ORDER_DEPENDENT"))
        .andExpect(jsonPath("$[0].count").value(1));
    String body =
        mvc.perform(
                get("/internal/settlement-inboxes")
                    .param("receivedBefore", BEFORE.toString())
                    .header("Authorization", "Bearer " + OPERATOR))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value(id.toString()))
            .andExpect(jsonPath("$.items[0].bodySize").value(mapper.writeValueAsBytes(e).length))
            .andExpect(
                jsonPath("$.items[0].receivedAt").value(org.hamcrest.Matchers.endsWith("+09:00")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("rawBody", "payload", "messageKey", CONFIRMED.toString());
    mvc.perform(
            get("/internal/settlement-inboxes/" + id).header("Authorization", "Bearer " + OPERATOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bodySha256").value(hash(id)));
    mvc.perform(get("/internal/settlement-inbox-summary")).andExpect(status().isUnauthorized());
    mvc.perform(
            get("/internal/settlement-inbox-summary").header("Authorization", "Bearer " + GATEWAY))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/internal/settlement-inbox-summary")
                .header("Authorization", "Bearer " + OPERATOR)
                .header("X-Authenticated-Actor-Kind", "SELLER"))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/internal/settlement-inboxes")
                .param("receivedBefore", BEFORE.toString())
                .param("size", "101")
                .header("Authorization", "Bearer " + OPERATOR))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/internal/settlement-inboxes/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + OPERATOR))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/internal/settlement-inboxes")
                .param("receivedBefore", "2026-11-03T23:00:00")
                .header("Authorization", "Bearer " + OPERATOR))
        .andExpect(status().isBadRequest());
  }

  @ParameterizedTest
  @EnumSource(
      value = InboxReviewReason.class,
      mode = EnumSource.Mode.EXCLUDE,
      names = {
        "PAYMENT_NOT_ARRIVED",
        "REFUND_REQUEST_UNDER_REVIEW",
        "REFUND_NOT_RESERVED",
        "REFUND_RESERVATION_NOT_FOUND",
        "PURCHASE_DURING_REFUND"
      })
  @DisplayName("계약·원천·종결 충돌은 409이고 원문·감사·실행을 바꾸지 않는다")
  void rejectsUnsafeReasons(InboxReviewReason reason) throws Exception {
    UUID id = review(purchase(), reason);
    var before = snapshot(id);
    mvc.perform(
            post(RUNS)
                .header("Authorization", "Bearer " + OPERATOR)
                .header("Idempotency-Key", "reject-unsafe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(id, reason.name(), 1)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SETTLEMENT_INBOX_NOT_REJUDGEABLE"));
    assertThat(snapshot(id)).isEqualTo(before);
    assertThat(count("inbox_rejudgement_runs")).isZero();
    assertThat(count("audit_logs")).isZero();
    assertThat(count("idempotency_records")).isZero();
  }

  @Test
  @DisplayName("사유 미기록은 별도 조회하고 재판정하지 않는다")
  void rejectsUnrecordedReason() throws Exception {
    UUID id = review(purchase(), InboxReviewReason.PAYMENT_NOT_ARRIVED);
    jdbc.update("UPDATE settlement.inbox SET last_error_code=NULL WHERE id=?", id);
    mvc.perform(
            get("/internal/settlement-inboxes")
                .param("receivedBefore", BEFORE.toString())
                .param("reasonCode", "UNRECORDED")
                .header("Authorization", "Bearer " + OPERATOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(
            post(RUNS)
                .header("Authorization", "Bearer " + OPERATOR)
                .header("Idempotency-Key", "unrecorded")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(id, "UNRECORDED", 1)))
        .andExpect(status().isConflict());
    assertThat(ops.get(id).reasonCode()).isNull();
    assertThat(count("inbox_rejudgement_runs")).isZero();
  }

  @Test
  @DisplayName("생성 조건·수·버전과 멱등키를 확인하고 겹치는 실행은 거절한다")
  void freezesTargetsAndReusesIdempotency() throws Exception {
    UUID id = review(purchase(), InboxReviewReason.PAYMENT_NOT_ARRIVED);
    String body = body(id, "PAYMENT_NOT_ARRIVED", 1);
    String first =
        mvc.perform(
                post(RUNS)
                    .header("Authorization", "Bearer " + OPERATOR)
                    .header("Idempotency-Key", "create-run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID run = UUID.fromString(mapper.readTree(first).get("id").asText());
    mvc.perform(
            post(RUNS)
                .header("Authorization", "Bearer " + OPERATOR)
                .header("Idempotency-Key", "create-run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value(run.toString()));
    mvc.perform(
            post(RUNS)
                .header("Authorization", "Bearer " + OPERATOR)
                .header("Idempotency-Key", "create-run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("PREDECESSOR_VERIFIED", "OTHER_REASON")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("COMMON_IDEMPOTENCY_CONFLICT"));
    mvc.perform(
            post(RUNS)
                .header("Authorization", "Bearer " + OPERATOR)
                .header("Idempotency-Key", "overlapping")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SETTLEMENT_INBOX_REJUDGEMENT_ACTIVE"));
    assertThat(count("inbox_rejudgement_runs")).isEqualTo(1);
    assertThat(count("audit_logs")).isEqualTo(1);
    mvc.perform(
            post(RUNS)
                .header("Authorization", "Bearer " + OPERATOR)
                .header("Idempotency-Key", "wrong-count")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("\"expectedCount\":1", "\"expectedCount\":2")))
        .andExpect(status().isBadRequest());
    ops.transition("operator:ops", run, "cancel-created", 0, "CANCEL", "NO_LONGER_NEEDED");
    jdbc.update("UPDATE settlement.inbox SET version=version+1 WHERE id=?", id);
    mvc.perform(
            post(RUNS)
                .header("Authorization", "Bearer " + OPERATOR)
                .header("Idempotency-Key", "stale-version")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SETTLEMENT_INBOX_TARGETS_CHANGED"));
  }

  @Test
  @DisplayName("선행 근거가 없거나 고정 버전이 바뀌면 원 Inbox를 그대로 둔다")
  void preservesUnreadyOrChangedInbox() throws Exception {
    UUID id = review(purchase(), InboxReviewReason.PAYMENT_NOT_ARRIVED);
    var before = snapshot(id);
    UUID run = create(id, "PAYMENT_NOT_ARRIVED");
    assertThat(ops.process(ops.claim(Duration.ofSeconds(30)).orElseThrow())).isTrue();
    assertThat(snapshot(id)).isEqualTo(before);
    assertThat(outcome(run)).isEqualTo("PREREQUISITE_MISSING");
    UUID changed = create(id, "PAYMENT_NOT_ARRIVED");
    jdbc.update("UPDATE settlement.inbox SET version=version+1 WHERE id=?", id);
    var updated = snapshot(id);
    ops.process(ops.claim(Duration.ofSeconds(30)).orElseThrow());
    assertThat(outcome(changed)).isEqualTo("VERSION_CHANGED");
    assertThat(snapshot(id)).isEqualTo(updated);
    assertThat(count("settlement_entries")).isZero();
  }

  @Test
  @DisplayName("늦은 결제와 먼저 받은 환불 원요청의 결과로 순서 의존 두 사유만 재판정한다")
  void rejudgesLatePaymentAndOriginalRefundResult() throws Exception {
    UUID refund = UUID.randomUUID();
    var original = reservation(refund);
    refunds.receiveReservationRequested(original, source(original));
    for (int i = 0; i < 9; i++) events.processNextWaiting();
    UUID originalId = id(original.eventId());
    assertThat(ops.get(originalId).reasonCode()).isEqualTo("PAYMENT_NOT_ARRIVED");
    var duplicate = reservation(refund);
    refunds.receiveReservationRequested(duplicate, source(duplicate));
    UUID duplicateId = id(duplicate.eventId());
    assertThat(ops.get(duplicateId).reasonCode()).isEqualTo("REFUND_REQUEST_UNDER_REVIEW");
    UUID first = create(originalId, "PAYMENT_NOT_ARRIVED");
    UUID second = create(duplicateId, "REFUND_REQUEST_UNDER_REVIEW");
    var paid = payment();
    events.receivePaymentConfirmed(paid, source(paid));
    ops.process(ops.claim(Duration.ofSeconds(30)).orElseThrow());
    ops.process(ops.claim(Duration.ofSeconds(30)).orElseThrow());
    assertThat(outcome(first)).isEqualTo("APPLIED");
    assertThat(outcome(second)).isEqualTo("APPLIED");
    assertThat(count("settlement_reservations")).isEqualTo(1);
    assertThat(count("settlement_entries")).isEqualTo(5);
  }

  @Test
  @DisplayName("실제 Kafka의 선행 환불 예약이 도착하면 기존 원문으로 원장을 한 번만 상쇄한다")
  void rejudgesWithActualKafkaPredecessor() throws Exception {
    var paid = payment();
    send(paid);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(count("settlement_items")).isEqualTo(1));
    UUID refund = UUID.randomUUID();
    var completed = completed(refund);
    refunds.receiveFinancialCompleted(completed, source(completed));
    UUID id = id(completed.eventId());
    var before = snapshot(id);
    UUID run = create(id, "REFUND_NOT_RESERVED");
    var request = reservation(refund);
    send(request);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(count("settlement_reservations")).isEqualTo(1));
    Claim claim = ops.claim(Duration.ofSeconds(30)).orElseThrow();
    assertThat(ops.process(claim)).isTrue();
    assertThat(ops.process(claim)).isFalse();
    assertThat(outcome(run)).isEqualTo("APPLIED");
    assertThat(ops.run(run).status()).isEqualTo("COMPLETED");
    assertImmutable(before, snapshot(id));
    assertThat(count("settlement_entries")).isEqualTo(10);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM settlement.settlement_reservations", String.class))
        .isEqualTo("CONSUMED");
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(amount) FROM settlement.settlement_entries", Long.class))
        .isZero();
  }

  @Test
  @DisplayName("먼저 수신한 예약 해제는 예약 커밋을 확인한 뒤 같은 예약만 해제한다")
  void rejudgesReleaseAfterReservationCommit() throws Exception {
    var paid = payment();
    events.receivePaymentConfirmed(paid, source(paid));
    UUID refund = UUID.randomUUID();
    var request = reservation(refund);
    var reservationId = new CompletableFuture<UUID>();
    var commit = new CountDownLatch(1);
    try (var pool = Executors.newSingleThreadExecutor()) {
      var writer =
          pool.submit(
              () ->
                  tx.execute(
                      s -> {
                        refunds.receiveReservationRequested(request, source(request));
                        reservationId.complete(
                            jdbc.queryForObject(
                                "SELECT id FROM settlement.settlement_reservations", UUID.class));
                        try {
                          if (!commit.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("Commit barrier timed out");
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(e);
                        }
                        return null;
                      }));
      UUID reserved = reservationId.get(10, TimeUnit.SECONDS);
      var release =
          envelope(
              "RefundReleaseRequested",
              new RefundReleaseRequestedPayload(refund, payment, reserved));
      try {
        refunds.receiveReleaseRequested(release, source(release));
        assertThat(ops.get(id(release.eventId())).reasonCode())
            .isEqualTo("REFUND_RESERVATION_NOT_FOUND");
      } finally {
        commit.countDown();
      }
      writer.get(10, TimeUnit.SECONDS);
      UUID run = create(id(release.eventId()), "REFUND_RESERVATION_NOT_FOUND");
      ops.process(ops.claim(Duration.ofSeconds(30)).orElseThrow());
      assertThat(outcome(run)).isEqualTo("APPLIED");
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM settlement.settlement_reservations", String.class))
          .isEqualTo("RELEASED");
    }
  }

  @Test
  @DisplayName("환불 예약 해제 뒤 구매확정은 원 confirmedAt과 원문 bytes를 보존한다")
  void rejudgesPurchaseAfterRefundRelease() throws Exception {
    var paid = payment();
    events.receivePaymentConfirmed(paid, source(paid));
    UUID refund = UUID.randomUUID();
    var requested = reservation(refund);
    refunds.receiveReservationRequested(requested, source(requested));
    var purchase = purchase();
    events.receivePurchaseConfirmed(purchase, source(purchase));
    UUID id = id(purchase.eventId());
    assertThat(ops.get(id).reasonCode()).isEqualTo("PURCHASE_DURING_REFUND");
    var before = snapshot(id);
    UUID run = create(id, "PURCHASE_DURING_REFUND");
    UUID reservation =
        jdbc.queryForObject("SELECT id FROM settlement.settlement_reservations", UUID.class);
    var release =
        envelope(
            "RefundReleaseRequested",
            new RefundReleaseRequestedPayload(refund, payment, reservation));
    refunds.receiveReleaseRequested(release, source(release));
    ops.process(ops.claim(Duration.ofSeconds(30)).orElseThrow());
    assertThat(outcome(run)).isEqualTo("APPLIED");
    assertImmutable(before, snapshot(id));
    assertThat(
            jdbc.queryForObject(
                    "SELECT purchase_confirmed_at FROM settlement.settlement_items",
                    java.sql.Timestamp.class)
                .toInstant())
        .isEqualTo(CONFIRMED.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
  }

  @Test
  @DisplayName("중단·재개는 원래 대상만 처리하고 무효화한 임대와 취소된 실행은 변경하지 못한다")
  void fencesPauseResumeCancelAndVersionRaces() throws Exception {
    UUID id = review(purchase(), InboxReviewReason.PAYMENT_NOT_ARRIVED);
    UUID run = create(id, "PAYMENT_NOT_ARRIVED");
    Claim old = ops.claim(Duration.ofSeconds(30)).orElseThrow();
    assertThat(ops.claim(Duration.ofSeconds(30))).isEmpty();
    mvc.perform(
            post(RUNS + "/" + run + "/pauses")
                .header("Authorization", "Bearer " + OPERATOR)
                .header("Idempotency-Key", "pause-run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":9,\"reasonCode\":\"CHECK_FIRST\"}"))
        .andExpect(status().isConflict());
    ops.transition("operator:ops", run, "pause-key", 0, "PAUSE", "CHECK_FIRST");
    assertThat(ops.process(old)).isFalse();
    assertThat(ops.claim(Duration.ofSeconds(30))).isEmpty();
    assertThatThrownBy(() -> create(id, "PAYMENT_NOT_ARRIVED"))
        .isInstanceOf(OperationRejectedException.class);
    UUID newInbox = review(purchase(), InboxReviewReason.PAYMENT_NOT_ARRIVED);
    ops.transition("operator:ops", run, "resume-key", 1, "RESUME", "CHECKED");
    Claim resumed = ops.claim(Duration.ofSeconds(30)).orElseThrow();
    assertThat(resumed.inboxId()).isEqualTo(id);
    ops.transition("operator:ops", run, "cancel-key", 2, "CANCEL", "NO_LONGER_NEEDED");
    assertThat(ops.process(resumed)).isFalse();
    assertThat(ops.get(newInbox).version()).isEqualTo(1);
    mvc.perform(get(RUNS + "/" + run + "/audits").header("Authorization", "Bearer " + OPERATOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(4))
        .andExpect(jsonPath("$.items[1].actorKind").value("OPERATOR"))
        .andExpect(jsonPath("$.items[1].reasonCode").value("CHECK_FIRST"));
    mvc.perform(get(RUNS + "/" + run + "/audits").header("Authorization", "Bearer " + GATEWAY))
        .andExpect(status().isForbidden());
    assertThat(ops.targets(run, 0, 100).items()).hasSize(1);
    assertThat(ops.attempts(run, 0, 100).items())
        .extracting(InboxOperationStore.AttemptView::outcome)
        .containsExactly("PAUSED", "CANCELLED");
  }

  @Test
  @DisplayName("만료 임대를 회수하고 이전 Worker의 늦은 적용을 차단한다")
  void reclaimsExpiredLeaseWithFence() throws Exception {
    UUID id = review(purchase(), InboxReviewReason.PAYMENT_NOT_ARRIVED);
    UUID run = create(id, "PAYMENT_NOT_ARRIVED");
    Claim old = ops.claim(Duration.ofSeconds(30)).orElseThrow();
    jdbc.update(
        "UPDATE settlement.inbox_rejudgement_targets SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",
        old.targetId());
    Claim fresh = ops.claim(Duration.ofSeconds(30)).orElseThrow();
    assertThat(fresh.inboxId()).isEqualTo(old.inboxId());
    assertThat(fresh.token()).isNotEqualTo(old.token());
    assertThat(ops.process(old)).isFalse();
    ops.process(fresh);
    assertThat(ops.attempts(run, 0, 100).items())
        .extracting(InboxOperationStore.AttemptView::outcome)
        .containsExactly("LEASE_EXPIRED", "PREREQUISITE_MISSING");
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  @DisplayName("이전 스냅샷으로 실행 잠금을 얻어도 유효 임대와 뒤의 미처리 대상을 바꾸지 않는다")
  void preservesValidLeaseAfterClaimSnapshotRace(int targetCount) throws Exception {
    var ids = new java.util.ArrayList<UUID>();
    for (int i = 0; i < targetCount; i++)
      ids.add(review(purchase(), InboxReviewReason.PAYMENT_NOT_ARRIVED));
    UUID run =
        ops.create(
                "operator:ops",
                "snapshot-race",
                new Filter("PAYMENT_NOT_ARRIVED", null, BEFORE, null),
                targetCount,
                ids.stream().map(id -> new ExpectedTarget(id, 1)).toList(),
                "VERIFIED")
            .id();
    Claim expired = ops.claim(Duration.ofSeconds(30)).orElseThrow();
    jdbc.update(
        "UPDATE settlement.inbox_rejudgement_targets SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",
        expired.targetId());
    // Only the second connection resolves this wrapper. Production SQL stays unchanged, and the
    // returned time is real. The gate pauses its first SELECT after its snapshot but before run
    // lock.
    jdbc.execute(
        "CREATE FUNCTION settlement.clock_timestamp() RETURNS timestamptz VOLATILE LANGUAGE plpgsql AS $$ BEGIN WHILE NOT pg_try_advisory_xact_lock(2120212) LOOP PERFORM pg_sleep(0.01); END LOOP; RETURN pg_catalog.clock_timestamp(); END $$");
    var written = new CompletableFuture<Claim>();
    var commit = new CompletableFuture<Void>();
    var backend = new AtomicLong();
    try (var holder = dataSource.getConnection();
        var pool = Executors.newFixedThreadPool(2)) {
      holder.setAutoCommit(false);
      holder.createStatement().execute("SELECT pg_advisory_xact_lock(2120212)");
      try {
        var first =
            pool.submit(
                () ->
                    tx.execute(
                        s -> {
                          Claim c = ops.claim(Duration.ofSeconds(30)).orElseThrow();
                          written.complete(c);
                          commit.orTimeout(5, TimeUnit.SECONDS).join();
                          return c;
                        }));
        Claim owner = written.get(5, TimeUnit.SECONDS);
        var second =
            pool.submit(
                () ->
                    tx.execute(
                        s -> {
                          jdbc.execute("SET LOCAL search_path=settlement,pg_catalog");
                          backend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Long.class));
                          return ops.claim(Duration.ofSeconds(30));
                        }));
        await()
            .pollInterval(Duration.ofMillis(10))
            .atMost(Duration.ofSeconds(3))
            .until(
                () ->
                    backend.get() != 0
                        && jdbc.queryForObject(
                                "SELECT count(*) FROM pg_stat_activity WHERE pid=? AND wait_event='PgSleep' AND query LIKE '%SELECT r.id FROM settlement.inbox_rejudgement_runs%'",
                                Long.class, backend.get())
                            == 1);
        commit.complete(null);
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(owner);
        var before = ops.targets(run, 0, 100).items();
        holder.commit();
        assertThat(second.get(5, TimeUnit.SECONDS)).isEmpty();
        assertThat(ops.targets(run, 0, 100).items()).isEqualTo(before);
        assertThat(ops.attempts(run, 0, 100).items())
            .extracting(InboxOperationStore.AttemptView::outcome)
            .containsExactly("LEASE_EXPIRED", "RUNNING");
        assertThat(ops.process(owner)).isTrue();
      } finally {
        commit.complete(null);
        holder.rollback();
      }
    }
  }

  @Test
  @DisplayName("적용 중 실패하면 금융·Inbox 변경을 롤백하고 실패 시도만 별도 기록한다")
  void rollsBackPartialApplication() throws Exception {
    var paid = payment();
    events.receivePaymentConfirmed(paid, source(paid));
    UUID refund = UUID.randomUUID();
    var completed = completed(refund);
    refunds.receiveFinancialCompleted(completed, source(completed));
    UUID id = id(completed.eventId());
    var request = reservation(refund);
    refunds.receiveReservationRequested(request, source(request));
    UUID run = create(id, "REFUND_NOT_RESERVED");
    var before = snapshot(id);
    long outbox = count("outbox");
    jdbc.execute(
        "CREATE FUNCTION settlement.fail_rejudgement() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='INBOX_REJUDGEMENT_TARGET_FINISHED' AND NEW.reason_code='APPLIED' THEN RAISE EXCEPTION 'synthetic transaction failure'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER fail_rejudgement BEFORE INSERT ON settlement.audit_logs FOR EACH ROW EXECUTE FUNCTION settlement.fail_rejudgement()");
    Claim claim = ops.claim(Duration.ofSeconds(30)).orElseThrow();
    assertThatThrownBy(() -> ops.process(claim)).isInstanceOf(RuntimeException.class);
    assertThat(snapshot(id)).isEqualTo(before);
    assertThat(count("settlement_entries")).isEqualTo(5);
    assertThat(count("outbox")).isEqualTo(outbox);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM settlement.settlement_reservations", String.class))
        .isEqualTo("ACTIVE");
    ops.fail(claim);
    assertThat(outcome(run)).isEqualTo("PROCESSING_FAILED");
    assertThat(ops.run(run).status()).isEqualTo("COMPLETED");
  }

  @Test
  @DisplayName("동시 생성은 한 실행만 소유하고 잠긴 실행은 다른 실행의 선점을 막지 않는다")
  void serializesOverlapsAndSkipsLockedRuns() throws Exception {
    UUID a = review(purchase(), InboxReviewReason.PAYMENT_NOT_ARRIVED);
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures =
          java.util.stream.IntStream.range(0, 2)
              .mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            start.await();
                            try {
                              return create(a, "PAYMENT_NOT_ARRIVED");
                            } catch (OperationRejectedException e) {
                              return null;
                            }
                          }))
              .toList();
      start.countDown();
      var results = new java.util.ArrayList<UUID>();
      for (var f : futures) results.add(f.get(10, TimeUnit.SECONDS));
      assertThat(results).filteredOn(java.util.Objects::nonNull).hasSize(1);
    }
    UUID runA = ops.runs(0, 100).items().getFirst().id();
    UUID b = review(purchase(), InboxReviewReason.PAYMENT_NOT_ARRIVED);
    UUID runB = create(b, "PAYMENT_NOT_ARRIVED");
    try (var holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (var stmt =
          holder.prepareStatement(
              "SELECT id FROM settlement.inbox_rejudgement_runs WHERE id=? FOR UPDATE")) {
        stmt.setObject(1, runA);
        stmt.executeQuery().close();
      }
      Claim claim = ops.claim(Duration.ofSeconds(30)).orElseThrow();
      assertThat(claim.runId()).isEqualTo(runB);
      ops.process(claim);
      holder.rollback();
    }
    assertThat(ops.claim(Duration.ofSeconds(30)).orElseThrow().runId()).isEqualTo(runA);
  }

  @Test
  @DisplayName("실행 잠금 대기는 유한하며 거절 뒤 원 상태·감사·Outbox를 보존한다")
  void boundsBlockedTransitionAndRollsBack() throws Exception {
    UUID id = review(purchase(), InboxReviewReason.PAYMENT_NOT_ARRIVED);
    UUID run = create(id, "PAYMENT_NOT_ARRIVED");
    long audits = count("audit_logs");
    try (var holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (var stmt =
          holder.prepareStatement(
              "SELECT id FROM settlement.inbox_rejudgement_runs WHERE id=? FOR UPDATE")) {
        stmt.setObject(1, run);
        stmt.executeQuery().close();
      }
      long started = System.nanoTime();
      mvc.perform(
              post(RUNS + "/" + run + "/pauses")
                  .header("Authorization", "Bearer " + OPERATOR)
                  .header("Idempotency-Key", "blocked-pause")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"expectedVersion\":0,\"reasonCode\":\"CHECK_FIRST\"}"))
          .andExpect(status().isServiceUnavailable());
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
      holder.rollback();
    }
    assertThat(ops.run(run).status()).isEqualTo("RUNNING");
    assertThat(count("audit_logs")).isEqualTo(audits);
    assertThat(count("outbox")).isZero();
  }

  @Test
  @DisplayName("사유별 건수·나이와 실행 정체를 읽고 중단 실행은 정체에서 제외한다")
  void observesReviewAndRunStall() throws Exception {
    UUID id = review(purchase(), InboxReviewReason.PAYMENT_NOT_ARRIVED);
    UUID run = create(id, "PAYMENT_NOT_ARRIVED");
    jdbc.update(
        "UPDATE settlement.inbox SET updated_at=clock_timestamp()-interval '10 minutes' WHERE id=?",
        id);
    jdbc.update(
        "UPDATE settlement.inbox_rejudgement_runs SET updated_at=clock_timestamp()-interval '6 minutes' WHERE id=?",
        run);
    try (var metrics = new FinancialQueueMetrics(dataSource, null)) {
      metrics.refresh();
      assertThat(
              metric(metrics, "settlement_inbox_review_records", "reason", "PAYMENT_NOT_ARRIVED"))
          .isEqualTo(1);
      assertThat(
              metric(
                  metrics,
                  "settlement_inbox_review_oldest_age_seconds",
                  "reason",
                  "PAYMENT_NOT_ARRIVED"))
          .isGreaterThanOrEqualTo(600);
      assertThat(
              metric(
                  metrics,
                  "settlement_inbox_rejudgement_stalled_seconds",
                  "consumer",
                  "settlement.v1"))
          .isGreaterThanOrEqualTo(360);
      ops.transition("operator:ops", run, "pause-metrics", 0, "PAUSE", "INTENTIONAL_PAUSE");
      metrics.refresh();
      assertThat(
              metric(
                  metrics,
                  "settlement_inbox_rejudgement_stalled_seconds",
                  "consumer",
                  "settlement.v1"))
          .isZero();
    }
  }

  @Test
  @DisplayName("동시 동일 키 요청과 재요청은 실행·감사를 한 번만 만든다")
  void replaysSameConcurrentKey() throws Exception {
    UUID id = review(purchase(), InboxReviewReason.PAYMENT_NOT_ARRIVED);
    var filter = new Filter("PAYMENT_NOT_ARRIVED", null, BEFORE, id);
    var targets = List.of(new ExpectedTarget(id, ops.get(id).version()));
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures =
          java.util.stream.IntStream.range(0, 2)
              .mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            start.await();
                            try {
                              return ops.create(
                                      "operator:ops",
                                      "same-concurrent-key",
                                      filter,
                                      1,
                                      targets,
                                      "VERIFIED")
                                  .id();
                            } catch (OperationRejectedException e) {
                              return null;
                            }
                          }))
              .toList();
      start.countDown();
      var ids = new java.util.HashSet<UUID>();
      for (var future : futures) {
        var result = future.get(10, TimeUnit.SECONDS);
        if (result != null) ids.add(result);
      }
      assertThat(ids).hasSize(1);
      assertThat(
              ops.create("operator:ops", "same-concurrent-key", filter, 1, targets, "VERIFIED")
                  .id())
          .isEqualTo(ids.iterator().next());
      assertThat(count("inbox_rejudgement_runs")).isEqualTo(1);
      assertThat(count("audit_logs")).isEqualTo(1);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"PAUSE", "CANCEL"})
  @DisplayName("적용과 운영 전환이 겹쳐도 완료 결과와 미처리 대상의 경계가 유지된다")
  void serializesProcessingAndOperatorControl(String action) throws Exception {
    UUID a = review(purchase(), InboxReviewReason.PAYMENT_NOT_ARRIVED);
    UUID b = review(purchase(), InboxReviewReason.PAYMENT_NOT_ARRIVED);
    UUID run =
        ops.create(
                "operator:ops",
                "concurrent-control",
                new Filter("PAYMENT_NOT_ARRIVED", null, BEFORE, null),
                2,
                List.of(new ExpectedTarget(a, 1), new ExpectedTarget(b, 1)),
                "VERIFIED")
            .id();
    Claim claim = ops.claim(Duration.ofSeconds(30)).orElseThrow();
    jdbc.execute(
        "CREATE FUNCTION settlement.hold_rejudgement() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='INBOX_REJUDGEMENT_TARGET_FINISHED' THEN PERFORM pg_advisory_xact_lock(212); END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER hold_rejudgement BEFORE INSERT ON settlement.audit_logs FOR EACH ROW EXECUTE FUNCTION settlement.hold_rejudgement()");
    try (var holder = dataSource.getConnection();
        var pool = Executors.newFixedThreadPool(2)) {
      holder.setAutoCommit(false);
      holder.createStatement().execute("SELECT pg_advisory_xact_lock(212)");
      var applying = pool.submit(() -> ops.process(claim));
      await()
          .atMost(Duration.ofSeconds(3))
          .until(
              () ->
                  jdbc.queryForObject(
                          "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND objid=212 AND NOT granted",
                          Long.class)
                      > 0);
      var started = new CountDownLatch(1);
      var control =
          pool.submit(
              () -> {
                started.countDown();
                return ops.transition("operator:ops", run, "control-key", 0, action, "STOP_NOW");
              });
      assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
      holder.commit();
      assertThat(applying.get(5, TimeUnit.SECONDS)).isTrue();
      assertThat(control.get(5, TimeUnit.SECONDS).status())
          .isEqualTo(action.equals("PAUSE") ? "PAUSED" : "CANCELLED");
      var states = ops.targets(run, 0, 100).items();
      assertThat(states).filteredOn(t -> t.status().equals("DONE")).hasSize(1);
      assertThat(states)
          .filteredOn(t -> t.status().equals(action.equals("PAUSE") ? "PENDING" : "CANCELLED"))
          .hasSize(1);
      assertThat(ops.process(claim)).isFalse();
      assertThat(ops.claim(Duration.ofSeconds(30))).isEmpty();
      assertThat(count("settlement_entries")).isZero();
    }
  }

  @Test
  @DisplayName("운영 조회는 호출자가 설정한 더 짧은 DB 대기 상한을 늘리지 않는다")
  void preservesStricterCallerBudgets() {
    var settings =
        tx.execute(
            status -> {
              jdbc.execute("SET LOCAL lock_timeout='100ms'");
              jdbc.execute("SET LOCAL statement_timeout='1s'");
              ops.list(new Filter(null, null, BEFORE, null), 0, 20);
              return jdbc.queryForMap(
                  "SELECT current_setting('lock_timeout') AS lock_budget,current_setting('statement_timeout') AS statement_budget");
            });
    assertThat(settings)
        .containsEntry("lock_budget", "100ms")
        .containsEntry("statement_budget", "1s");
  }

  @Test
  @DisplayName("상한 100건을 한 번에 고정하고 이후 도착한 Inbox는 실행에 추가하지 않는다")
  void freezesMaximumBatchWithoutAddingLaterRows() throws Exception {
    var targets = new java.util.ArrayList<ExpectedTarget>();
    for (int i = 0; i < 100; i++)
      targets.add(new ExpectedTarget(review(purchase(), InboxReviewReason.PAYMENT_NOT_ARRIVED), 1));
    UUID run =
        ops.create(
                "operator:ops",
                "maximum-batch",
                new Filter("PAYMENT_NOT_ARRIVED", null, BEFORE, null),
                100,
                targets,
                "VERIFIED")
            .id();
    review(purchase(), InboxReviewReason.PAYMENT_NOT_ARRIVED);
    assertThat(ops.targets(run, 0, 100).items())
        .extracting(t -> new ExpectedTarget(t.inboxId(), t.expectedVersion()))
        .containsExactlyInAnyOrderElementsOf(targets);
    assertThat(ops.targets(run, 0, 100).totalElements()).isEqualTo(100);
    assertThat(ops.run(run).expectedCount()).isEqualTo(100);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("반복 중단으로 임대 10회를 소진하면 추가 적용 없이 결과와 감사를 남긴다")
  void recordsExhaustedRecoveryAttemptsWithoutAnotherLease(boolean paused) throws Exception {
    UUID id = review(purchase(), InboxReviewReason.PAYMENT_NOT_ARRIVED);
    var before = snapshot(id);
    UUID run = create(id, "PAYMENT_NOT_ARRIVED");
    for (int i = 0; i < 10; i++) {
      var claim = ops.claim(Duration.ofSeconds(30)).orElseThrow();
      jdbc.update(
          "UPDATE settlement.inbox_rejudgement_targets SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",
          claim.targetId());
    }
    if (paused) {
      ops.transition("operator:ops", run, "pause-exhausted", 0, "PAUSE", "CHECK_FIRST");
      ops.transition("operator:ops", run, "resume-exhausted", 1, "RESUME", "CHECKED");
    }
    assertThat(ops.claim(Duration.ofSeconds(30))).isEmpty();
    assertThat(outcome(run)).isEqualTo("ATTEMPTS_EXHAUSTED");
    assertThat(ops.run(run).status()).isEqualTo("COMPLETED");
    assertThat(ops.attempts(run, 0, 100).items()).hasSize(10);
    assertThat(ops.attempts(run, 0, 100).items())
        .filteredOn(a -> a.outcome().equals("LEASE_EXPIRED"))
        .hasSize(paused ? 9 : 10);
    assertThat(ops.audits(run, 0, 100).items())
        .filteredOn(a -> a.reasonCode().equals("ATTEMPTS_EXHAUSTED"))
        .singleElement()
        .satisfies(a -> assertThat(a.beforeStatus()).isEqualTo(paused ? "PENDING" : "RUNNING"));
    assertThat(snapshot(id)).isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM settlement.audit_logs WHERE action='INBOX_REJUDGEMENT_TARGET_FINISHED' AND aggregate_type='INBOX_REJUDGEMENT_TARGET' AND reason_code='ATTEMPTS_EXHAUSTED'",
                Long.class))
        .isEqualTo(1);
  }

  private double metric(FinancialQueueMetrics metrics, String name, String label, String value) {
    double result = 0;
    for (var metric : metrics.collect())
      if (metric.getMetadata().getName().equals(name))
        for (var p : ((GaugeSnapshot) metric).getDataPoints())
          if (value.equals(p.getLabels().get(label))) result += p.getValue();
    return result;
  }

  private UUID review(EventEnvelope<PurchaseConfirmedPayload> e, InboxReviewReason reason)
      throws Exception {
    return tx.execute(
        s -> {
          var id = inbox.receive(e, source(e)).orElseThrow();
          inbox.markReviewRequired(id, reason, Instant.now());
          return id;
        });
  }

  private UUID create(UUID id, String reason) {
    var v = ops.get(id);
    return ops.create(
            "operator:ops",
            UUID.randomUUID().toString(),
            new Filter(reason, null, BEFORE, id),
            1,
            List.of(new ExpectedTarget(id, v.version())),
            "PREDECESSOR_VERIFIED")
        .id();
  }

  private String body(UUID id, String reason, long version) throws Exception {
    return mapper.writeValueAsString(
        Map.of(
            "filter",
            Map.of("reasonCode", reason, "receivedBefore", BEFORE.toString(), "inboxId", id),
            "expectedCount",
            1,
            "targets",
            List.of(Map.of("inboxId", id, "expectedVersion", version)),
            "reasonCode",
            "PREDECESSOR_VERIFIED"));
  }

  private String outcome(UUID run) {
    return ops.targets(run, 0, 100).items().getFirst().outcome();
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM settlement." + table, Long.class);
  }

  private UUID id(UUID event) {
    return jdbc.queryForObject(
        "SELECT id FROM settlement.inbox WHERE event_id=?", UUID.class, event);
  }

  private String hash(UUID id) {
    return jdbc.queryForObject(
        "SELECT body_hash FROM settlement.inbox WHERE id=?", String.class, id);
  }

  private Map<String, Object> snapshot(UUID id) {
    return jdbc.queryForMap(
        "SELECT encode(raw_body,'hex') AS raw_body,body_hash,payload::text,event_id,aggregate_id,aggregate_version,source_topic,source_partition,source_offset,status,last_error_code,version FROM settlement.inbox WHERE id=?",
        id);
  }

  private void assertImmutable(Map<String, Object> before, Map<String, Object> after) {
    for (String key :
        List.of(
            "raw_body",
            "body_hash",
            "payload",
            "event_id",
            "aggregate_id",
            "aggregate_version",
            "source_topic",
            "source_partition",
            "source_offset")) assertThat(after.get(key)).isEqualTo(before.get(key));
  }

  private InboxStore.Source source(EventEnvelope<?> e) {
    try {
      return new InboxStore.Source(
          e.eventType().equals("PurchaseConfirmed")
              ? "commerce-settle.commerce.v1"
              : "commerce-settle.payment.v1",
          0,
          offset.incrementAndGet(),
          mapper.writeValueAsBytes(e));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private void send(EventEnvelope<?> e) throws Exception {
    template
        .send("commerce-settle.payment.v1", payment.toString(), mapper.writeValueAsBytes(e))
        .get(20, TimeUnit.SECONDS);
  }

  private <T> EventEnvelope<T> envelope(String type, T payload) {
    return new EventEnvelope<>(UUID.randomUUID(), type, 1, CONFIRMED, payment, 1, payload);
  }

  private EventEnvelope<PurchaseConfirmedPayload> purchase() {
    return envelope(
        "PurchaseConfirmed",
        new PurchaseConfirmedPayload(
            payment, order, 1, CONFIRMED, DELIVERED, DELIVERED.plusDays(7)));
  }

  private EventEnvelope<RefundReservationRequestedPayload> reservation(UUID refund) {
    return envelope(
        "RefundReservationRequested", new RefundReservationRequestedPayload(refund, payment, 8500));
  }

  private EventEnvelope<RefundFinancialCompletedPayload> completed(UUID refund) {
    return envelope(
        "RefundFinancialCompleted",
        new RefundFinancialCompletedPayload(refund, payment, 10000, 0, CONFIRMED));
  }

  private EventEnvelope<PaymentConfirmedPayload> payment() {
    return envelope(
        "PaymentConfirmed",
        new PaymentConfirmedPayload(
            10000,
            0,
            10000,
            0,
            10000,
            payment,
            order,
            UUID.randomUUID(),
            seller,
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1500,
            8500,
            CONFIRMED,
            "delivery-daily-v1",
            0,
            0,
            10000));
  }
}
