package com.commercesettle.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.event.PaymentConfirmedPayload;
import com.commercesettle.settlement.application.port.InboxStore.Source;
import com.commercesettle.settlement.application.port.ReconciliationPositions;
import com.commercesettle.settlement.application.port.ReconciliationSources;
import com.commercesettle.settlement.application.port.ReconciliationStore;
import com.commercesettle.settlement.infrastructure.messaging.KafkaReconciliationPositions;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123",
      "app.internal-auth.senders[service\\:commerce].current=test-commerce-retention-credential-0123456789abcdef",
      "app.internal-auth.senders[operator\\:ops].current=test-operator-credential-0123456789abcdef0123",
      "settlement.commerce.credential=test-commerce-settlement-0123456789abcdef012345",
      "settlement.payout.mock-credential=test-settlement-credential-0123456789abcdef0123",
      "spring.kafka.admin.auto-create=false",
      "settlement.daily-batch.schedule-enabled=false",
      "settlement.payout-worker.enabled=false",
      "settlement.inbox-processor.enabled=false"
    })
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReconciliationIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.2");
  @Autowired ReconciliationService service;
  @Autowired ReconciliationStore store;
  @Autowired SettlementEventService events;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired Feed feed;
  @Autowired Positions positions;
  @Autowired MockMvc mvc;
  Instant cutoff;

  @BeforeEach
  void reset() {
    for (String table :
        List.of(
            "reconciliation_events",
            "reconciliation_checks",
            "reconciliation_history",
            "reconciliation_runs",
            "settlement_entries",
            "settlement_items",
            "inbox",
            "inbox_conflicts",
            "inbox_quarantines",
            "payouts",
            "payout_jobs",
            "settlement_batches",
            "settlement_reservations")) jdbc.update("DELETE FROM settlement." + table);
    feed.items.clear();
    feed.fail = false;
    positions.offsets = Map.of();
    positions.fail = false;
    positions.afterRead = null;
    cutoff = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
  }

  private ReconciliationSources.Event payment(boolean published) {
    return new ReconciliationSources.Event(
        UUID.randomUUID(),
        "PaymentConfirmed",
        cutoff,
        cutoff.minusSeconds(1),
        UUID.randomUUID(),
        null,
        9000,
        1000,
        0,
        10000,
        1500,
        8500,
        published ? "PUBLISHED" : "PENDING",
        "commerce-settle.payment.v1",
        published ? 0 : null,
        published ? 5L : null,
        published ? cutoff : null,
        null,
        0,
        null,
        null,
        0,
        0,
        null);
  }

  private void apply(ReconciliationSources.Event e) throws Exception {
    var payload =
        new PaymentConfirmedPayload(
            10000,
            0,
            10000,
            1000,
            9000,
            e.paymentId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1500,
            8500,
            e.occurredAt().atOffset(ZoneOffset.UTC),
            "v1",
            0,
            0,
            10000);
    var envelope =
        new EventEnvelope<>(
            e.eventId(),
            e.eventType(),
            1,
            e.occurredAt().atOffset(ZoneOffset.UTC),
            e.paymentId(),
            1,
            payload);
    events.receivePaymentConfirmed(
        envelope, new Source(e.topic(), 0, 5, mapper.writeValueAsBytes(envelope)));
  }

  private void tick(int count) {
    for (int i = 0; i < count; i++) service.tick();
  }

  private String status(UUID id) {
    return store.get(id).get("status").toString();
  }

  private void due(UUID id) {
    jdbc.update(
        "UPDATE settlement.reconciliation_runs SET next_run_at=clock_timestamp() WHERE id=?", id);
  }

  @Test
  @DisplayName("실제 원장 5개 leg와 반영 기록을 검사하고 같은 마감 중복 요청은 한 회차를 반환한다")
  void reconcilesAppliedLedgerOnce() throws Exception {
    var e = payment(false);
    apply(e);
    feed.items.add(e);
    positions.fail = true;
    UUID id = service.request(cutoff, "operator:ops");
    assertThat(service.request(cutoff, "operator:ops")).isEqualTo(id);
    tick(3);
    assertThat(status(id)).isEqualTo("COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM settlement.reconciliation_events WHERE status='MATCH'",
                Long.class))
        .isEqualTo(1);
    assertThat(service.tick()).isFalse();
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM settlement.settlement_entries", Long.class))
        .isEqualTo(5);
  }

  @Test
  @DisplayName("미발행·전달·반영 대기·격리는 금액 누락과 분리하고 원마감으로 재개한다")
  void resumesSameWaitingTarget() throws Exception {
    var e = payment(false);
    feed.items.add(e);
    UUID id = service.request(cutoff, "operator:ops");
    tick(3);
    assertThat(status(id)).isEqualTo("WAITING");
    Instant snapshot = ((java.time.OffsetDateTime) store.get(id).get("snapshotAt")).toInstant();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM settlement.reconciliation_events", String.class))
        .isEqualTo("WAIT_PUBLICATION");
    apply(e);
    due(id);
    tick(3);
    assertThat(status(id)).isEqualTo("COMPLETED");
    assertThat(((java.time.OffsetDateTime) store.get(id).get("snapshotAt")).toInstant())
        .isEqualTo(snapshot);
    assertThat(store.history(id).stream().map(x -> x.get("status")))
        .contains("WAIT_PUBLICATION", "MATCH");
  }

  @Test
  @DisplayName("한 페이지 저장 뒤 조회 장애는 같은 snapshot·cursor에서 재개하며 중복·누락을 만들지 않는다")
  void resumesCommittedPageAfterFailure() {
    for (int i = 0; i < 101; i++) feed.items.add(payment(false));
    UUID id = service.request(cutoff, "operator:ops");
    tick(1);
    Object snapshot = store.get(id).get("snapshotAt");
    Object cursor = store.get(id).get("cursor");
    feed.fail = true;
    tick(1);
    assertThat(status(id)).isEqualTo("UNVERIFIED");
    assertThat(store.get(id)).containsEntry("snapshotAt", snapshot).containsEntry("cursor", cursor);
    var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    var metrics =
        new com.commercesettle.settlement.infrastructure.observability.ReconciliationMetrics(
            jdbc.getDataSource(), meters);
    metrics.refresh();
    assertThat(meters.get("commerce_settle_reconciliation_unverified").gauge().value())
        .isEqualTo(1);
    due(id);
    var retry = store.claim().orElseThrow();
    assertThat(status(id)).isEqualTo("RUNNING");
    metrics.refresh();
    assertThat(meters.get("commerce_settle_reconciliation_unverified").gauge().value())
        .isEqualTo(1);
    store.failed(retry, "SOURCE_UNAVAILABLE");
    feed.fail = false;
    due(id);
    tick(5);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM settlement.reconciliation_events WHERE run_id=?",
                Long.class,
                id))
        .isEqualTo(101);
    assertThat(store.get(id).get("snapshotAt")).isEqualTo(snapshot);
    metrics.refresh();
    assertThat(meters.get("commerce_settle_reconciliation_unverified").gauge().value()).isZero();
    meters.close();
  }

  @Test
  @DisplayName("실제 Kafka의 다음 읽기 offset을 지났는데 수신 기록이 없으면 즉시 유실 결함이다")
  void detectsReceiptLossWithRealKafkaOffset() throws Exception {
    String group = "reconciliation-" + UUID.randomUUID();
    String topic = "reconcile-" + UUID.randomUUID();
    var config = Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers());
    try (var admin = org.apache.kafka.clients.admin.Admin.create(config)) {
      admin
          .createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic(topic, 1, (short) 1)))
          .all()
          .get(10, java.util.concurrent.TimeUnit.SECONDS);
      admin
          .alterConsumerGroupOffsets(
              group,
              Map.of(
                  new org.apache.kafka.common.TopicPartition(topic, 0),
                  new org.apache.kafka.clients.consumer.OffsetAndMetadata(6)))
          .all()
          .get(10, java.util.concurrent.TimeUnit.SECONDS);
      var actual = new KafkaReconciliationPositions(new KafkaAdmin(config), group).committed();
      assertThat(actual.get(topic + ":0")).isEqualTo(6);
      assertThat(
              new KafkaReconciliationPositions(
                      new KafkaAdmin(config), "unregistered-" + UUID.randomUUID())
                  .committed())
          .isEmpty();
      var e = payment(true);
      feed.items.add(
          new ReconciliationSources.Event(
              e.eventId(),
              e.eventType(),
              e.occurredAt(),
              e.createdAt(),
              e.paymentId(),
              null,
              9000,
              1000,
              0,
              10000,
              1500,
              8500,
              "PUBLISHED",
              topic,
              0,
              5L,
              cutoff,
              null,
              0,
              null,
              null,
              0,
              0,
              null));
      positions.offsets = actual;
      UUID id = service.request(cutoff, "operator:ops");
      tick(3);
      assertThat(status(id)).isEqualTo("REVIEW_REQUIRED");
      assertThat(
              jdbc.queryForObject(
                  "SELECT reason FROM settlement.reconciliation_events", String.class))
          .isEqualTo("RECEIPT_LOST_AFTER_COMMIT");
    }
  }

  @Test
  @DisplayName("동시 실행은 한 임대만 획득하고 만료된 실행자는 결과를 덮어쓰지 못한다")
  void fencesConcurrentAndExpiredExecutors() throws Exception {
    service.request(cutoff, "operator:ops");
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () -> {
                start.await();
                return store.claim();
              });
      var b =
          pool.submit(
              () -> {
                start.await();
                return store.claim();
              });
      start.countDown();
      var first = a.get();
      var second = b.get();
      assertThat(first.isPresent() ^ second.isPresent()).isTrue();
      var old = first.orElseGet(second::orElseThrow);
      jdbc.update(
          "UPDATE settlement.reconciliation_runs SET lease_until=clock_timestamp()-interval '1 second'");
      var next = store.claim().orElseThrow();
      assertThatThrownBy(
              () ->
                  store.page(
                      old,
                      new ReconciliationSources.Page(cutoff, Instant.now(), 0, null, List.of())))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      store.page(
          next,
          new ReconciliationSources.Page(
              cutoff, Instant.now().truncatedTo(ChronoUnit.MICROS), 0, null, List.of()));
    }
  }

  /** 시험 중 대상 테이블의 행 또는 문장 단위 갱신 횟수를 센다. */
  private void track(String table, String level) {
    jdbc.execute(
        "CREATE UNLOGGED TABLE IF NOT EXISTS settlement.test_writes(name text NOT NULL, op text NOT NULL)");
    jdbc.execute(
        """
        CREATE OR REPLACE FUNCTION settlement.test_count_write() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN INSERT INTO settlement.test_writes VALUES(TG_TABLE_NAME, TG_OP); RETURN NULL; END $$
        """);
    jdbc.execute(
        "CREATE TRIGGER test_count_write AFTER INSERT OR UPDATE ON settlement."
            + table
            + " FOR EACH "
            + level
            + " EXECUTE FUNCTION settlement.test_count_write()");
  }

  private long writes(String table, String op) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM settlement.test_writes WHERE name=? AND op=?", Long.class, table, op);
  }

  private void untrack(String... tables) {
    for (String table : tables)
      jdbc.execute("DROP TRIGGER IF EXISTS test_count_write ON settlement." + table);
    jdbc.execute("DROP TABLE IF EXISTS settlement.test_writes");
  }

  @Test
  @DisplayName("검사 결과 기록은 회차 행을 이벤트마다 갱신하지 않고 만료된 실행자의 기록을 거절한다")
  void fencesResultsWithoutRewritingRunPerEvent() {
    for (int i = 0; i < 100; i++) feed.items.add(payment(false));
    UUID id = service.request(cutoff, "operator:ops");
    tick(2);
    track("reconciliation_runs", "ROW");
    try {
      service.tick();

      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM settlement.reconciliation_events WHERE run_id=? AND status='WAIT_PUBLICATION'",
                  Long.class,
                  id))
          .isEqualTo(100);
      assertThat(writes("reconciliation_runs", "UPDATE")).isLessThan(10);
    } finally {
      untrack("reconciliation_runs");
    }
    due(id);
    var old = store.claim().orElseThrow();
    jdbc.update(
        "UPDATE settlement.reconciliation_runs SET lease_until=clock_timestamp()-interval '1 second'");
    store.claim().orElseThrow();
    var event = feed.items.getFirst();

    assertThatThrownBy(
            () ->
                store.record(
                    old,
                    Map.of(
                        event.eventId(), ReconciliationStore.Finding.of("x", "DEFECT", "STALE"))))
        .hasMessageContaining("lease lost");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM settlement.reconciliation_events WHERE event_id=?",
                String.class,
                event.eventId()))
        .isEqualTo("WAIT_PUBLICATION");
  }

  @Test
  @DisplayName("한 호출의 여러 이벤트를 묶어 조회·기록해도 이벤트별 판정과 변경 이력은 같다")
  void batchesCheckWithSameFindings() throws Exception {
    var matched = payment(false);
    var defect = payment(false);
    var unpublished = payment(false);
    var quarantined = payment(false);
    apply(matched);
    apply(defect);
    jdbc.update(
        "DELETE FROM settlement.settlement_entries WHERE source_event_id=? AND leg='FEE'",
        defect.eventId());
    jdbc.update(
        "INSERT INTO settlement.inbox_quarantines(id,consumer,source_topic,source_partition,source_offset,raw_body,error_code,created_at) VALUES(?,'settlement.v1',?,0,5,?,'UNSUPPORTED_VERSION',clock_timestamp())",
        UUID.randomUUID(),
        quarantined.topic(),
        ("{\"eventId\":\"" + quarantined.eventId() + "\"}")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    feed.items.addAll(List.of(matched, defect, unpublished, quarantined));
    UUID id = service.request(cutoff, "operator:ops");
    tick(2);
    track("reconciliation_events", "ROW");
    track("reconciliation_history", "STATEMENT");
    try {
      service.tick();

      assertThat(writes("reconciliation_events", "UPDATE")).isEqualTo(4);
      assertThat(writes("reconciliation_history", "INSERT")).isEqualTo(3);
    } finally {
      untrack("reconciliation_events", "reconciliation_history");
    }
    assertThat(status(id)).isEqualTo("REVIEW_REQUIRED");
    assertThat(
            jdbc.queryForList(
                "SELECT event_id::text||':'||status||':'||reason FROM settlement.reconciliation_events WHERE run_id=?",
                String.class,
                id))
        .containsExactlyInAnyOrder(
            matched.eventId() + ":MATCH:APPLIED_MATCH",
            defect.eventId() + ":DEFECT:APPLIED_AMOUNT_OR_REFERENCE_MISMATCH",
            unpublished.eventId() + ":WAIT_PUBLICATION:PUBLICATION_POSITION_UNKNOWN",
            quarantined.eventId() + ":QUARANTINED:SOURCE_REQUIRES_REVIEW");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM settlement.reconciliation_history WHERE run_id=? AND event_id IS NOT NULL",
                Long.class,
                id))
        .isEqualTo(4);
  }

  @Test
  @DisplayName("미검사 이벤트가 남은 호출은 원장 전체 검사를 건너뛰고 모두 검사한 뒤 한 번 기록한다")
  void runsLocalChecksOnlyAfterAllEventsChecked() {
    for (int i = 0; i < 150; i++) feed.items.add(payment(false));
    UUID id = service.request(cutoff, "operator:ops");
    String pending = "SELECT count(*) FROM settlement.reconciliation_events WHERE status='PENDING'";
    for (int i = 0; i < 10; i++) {
      service.tick();
      long checked =
          jdbc.queryForObject(
              "SELECT count(*) FROM settlement.reconciliation_events WHERE status<>'PENDING'",
              Long.class);
      if (checked > 0) break;
    }
    assertThat(jdbc.queryForObject(pending, Long.class)).isEqualTo(50);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM settlement.reconciliation_checks", Long.class))
        .isZero();

    service.tick();
    assertThat(status(id)).isEqualTo("WAITING");
    assertThat(jdbc.queryForObject(pending, Long.class)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM settlement.reconciliation_checks", Long.class))
        .isEqualTo(14);
  }

  @Test
  @DisplayName("원장의 금액·참조 결함과 실제 DB 고아 참조를 탐지한다")
  void detectsLedgerAndReferenceDefects() throws Exception {
    var e = payment(false);
    apply(e);
    feed.items.add(e);
    jdbc.update(
        "DELETE FROM settlement.settlement_entries WHERE source_event_id=? AND leg='FEE'",
        e.eventId());
    UUID id = service.request(cutoff, "operator:ops");
    tick(3);
    assertThat(status(id)).isEqualTo("REVIEW_REQUIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT reason FROM settlement.reconciliation_events", String.class))
        .isEqualTo("APPLIED_AMOUNT_OR_REFERENCE_MISMATCH");
  }

  @Test
  @DisplayName("예약 없는 고아 지급 포인터도 정상 원장의 결함으로 탐지한다")
  void detectsOrphanPayoutPointerWithoutReservation() throws Exception {
    var e = payment(false);
    apply(e);
    feed.items.add(e);
    jdbc.update(
        "UPDATE settlement.settlement_items SET payout_id=? WHERE payment_id=?",
        UUID.randomUUID(),
        e.paymentId());
    UUID id = service.request(cutoff, "operator:ops");
    tick(3);
    assertThat(status(id)).isEqualTo("REVIEW_REQUIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count FROM settlement.reconciliation_checks WHERE run_id=? AND check_key='ITEM_PAYOUT_REFERENCE'",
                Long.class,
                id))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("마감 전 미완료 지급은 전체 건수·대상·최초 생성 시각을 진행 중 검사로 남긴다")
  void recordsInProgressPayoutMembers() {
    var created = cutoff.minusSeconds(60).atOffset(ZoneOffset.UTC);
    UUID payout = UUID.randomUUID();
    jdbc.update(
        """
      INSERT INTO settlement.payouts(id,batch_id,seller_id,recipient_account_ref,recipient_account_version,funding_account_ref,scenario_version,amount,business_key,status,created_at,updated_at,version)
      VALUES(?,?,?,'recipient',0,'funding','normal',8500,?,'PENDING',?,?,0)
      """,
        payout,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        created,
        created);
    UUID id = service.request(cutoff, "operator:ops");

    tick(3);

    var check =
        jdbc.queryForMap(
            "SELECT status,count,evidence::text AS evidence FROM settlement.reconciliation_checks WHERE run_id=? AND check_key='PAYOUT_IN_PROGRESS'",
            id);
    assertThat(check).containsEntry("status", "IN_PROGRESS").containsEntry("count", 1L);
    assertThat(check.get("evidence").toString())
        .contains(payout.toString())
        .contains(
            "\"oldestCreatedAt\": \""
                + created.atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime()
                + "\"")
        .contains("\"truncated\": false");
  }

  @Test
  @DisplayName("운영자만 회차를 요청하며 Gateway 자격과 미인증은 거절한다")
  void enforcesHttpOwnership() throws Exception {
    String body = "{\"cutoffAt\":\"" + cutoff + "\"}";
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/internal/settlement-reconciliations")
                .header("Authorization", "Bearer test-operator-credential-0123456789abcdef0123")
                .contentType("application/json")
                .content("{\"cutoffAt\":\"" + Instant.now() + "\"}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value("RECONCILIATION_CUTOFF_NOT_READY"));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/internal/settlement-reconciliations")
                .contentType("application/json")
                .content(body))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isUnauthorized());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/internal/settlement-reconciliations")
                .header("Authorization", "Bearer test-gateway-credential-0123456789abcdef0123")
                .contentType("application/json")
                .content(body))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/internal/settlement-reconciliations")
                .header("Authorization", "Bearer test-operator-credential-0123456789abcdef0123")
                .contentType("application/json")
                .content(body))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isAccepted());
  }

  @Test
  @DisplayName("다른 트랜잭션의 회차 잠금은 1초 안에 실패하고 페이지 저장을 롤백한다")
  void boundsRowLockAndRollsBackCheckpoint() throws Exception {
    UUID id = service.request(cutoff, "operator:ops");
    var run = store.claim().orElseThrow();
    try (var connection =
        java.sql.DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      connection.setAutoCommit(false);
      try (var statement =
          connection.prepareStatement(
              "SELECT id FROM settlement.reconciliation_runs WHERE id=? FOR UPDATE")) {
        statement.setObject(1, id);
        statement.executeQuery().close();
        long start = System.nanoTime();
        assertThatThrownBy(
                () ->
                    store.page(
                        run,
                        new ReconciliationSources.Page(cutoff, Instant.now(), 0, null, List.of())))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
            .isLessThan(java.time.Duration.ofSeconds(5));
      } finally {
        connection.rollback();
      }
    }
    assertThat(store.get(id).get("snapshotAt")).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM settlement.reconciliation_events", Long.class))
        .isZero();
  }

  @Test
  @DisplayName("같은 마감을 다른 트랜잭션이 등록 중이면 회차 요청은 잠금 상한 안에 실패한다")
  void boundsRequestLockWait() throws Exception {
    try (var connection =
        java.sql.DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      connection.setAutoCommit(false);
      try (var statement =
          connection.prepareStatement(
              "INSERT INTO settlement.reconciliation_runs(id,cutoff_at,requested_by) VALUES(?,?,'other')")) {
        statement.setObject(1, UUID.randomUUID());
        statement.setObject(2, cutoff.atOffset(ZoneOffset.UTC));
        statement.executeUpdate();
        long start = System.nanoTime();

        assertThatThrownBy(() -> store.request(cutoff, "operator:ops"))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);

        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
            .isLessThan(java.time.Duration.ofSeconds(5));
      } finally {
        connection.rollback();
      }
    }
    assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("0");
  }

  @Test
  @DisplayName("지급의 원거래 참조와 실제 차감 건수·금액을 함께 검사한다")
  void comparesPayoutReferenceAndActualDebit() {
    UUID transaction = UUID.randomUUID(), key = UUID.randomUUID();
    var time = cutoff.minusSeconds(1).atOffset(ZoneOffset.UTC);
    jdbc.update(
        """
      INSERT INTO settlement.payouts(id,batch_id,seller_id,recipient_account_ref,recipient_account_version,funding_account_ref,scenario_version,succeeded_at,amount,business_key,external_ref,status,created_at,updated_at,version)
      VALUES(?,?,?,'recipient',0,'funding','normal',?,8500,?,?,'SUCCEEDED',?,?,0)
      """,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        time,
        key,
        transaction,
        time,
        time);
    var good =
        new ReconciliationSources.Event(
            transaction,
            "PAYOUT",
            time.toInstant(),
            time.toInstant(),
            null,
            null,
            0,
            0,
            0,
            0,
            0,
            0,
            null,
            null,
            null,
            null,
            null,
            key,
            8500,
            "SUCCEEDED",
            time.toInstant(),
            -8500,
            1,
            "funding");
    var local = store.local(good, cutoff);
    assertThat(ReconciliationService.payout(good, local, cutoff).status()).isEqualTo("MATCH");
    var missing =
        new ReconciliationSources.Event(
            transaction,
            "PAYOUT",
            time.toInstant(),
            time.toInstant(),
            null,
            null,
            0,
            0,
            0,
            0,
            0,
            0,
            null,
            null,
            null,
            null,
            null,
            key,
            8500,
            "SUCCEEDED",
            time.toInstant(),
            0,
            0,
            "funding");
    assertThat(ReconciliationService.payout(missing, local, cutoff).status()).isEqualTo("DEFECT");
    var late =
        new ReconciliationSources.Event(
            transaction,
            "PAYOUT",
            time.toInstant(),
            time.toInstant(),
            null,
            null,
            0,
            0,
            0,
            0,
            0,
            0,
            null,
            null,
            null,
            null,
            null,
            key,
            8500,
            "SUCCEEDED",
            cutoff.plusSeconds(1),
            0,
            0,
            "funding");
    assertThat(ReconciliationService.payout(late, local, cutoff).reason())
        .isEqualTo("PAYOUT_SUCCEEDED_AFTER_CUTOFF");
  }

  @Test
  @DisplayName("offset 조회 중 Inbox 커밋은 유실로 오인하지 않는다")
  void rechecksInboxAfterCommittedOffset() throws Exception {
    var event = payment(true);
    feed.items.add(event);
    positions.offsets = Map.of(event.topic() + ":0", 6L);
    positions.afterRead =
        () -> {
          try {
            apply(event);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        };
    UUID id = service.request(cutoff, "operator:ops");
    tick(3);
    assertThat(status(id)).isEqualTo("COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM settlement.reconciliation_events WHERE status='DEFECT'",
                Long.class))
        .isZero();
  }

  @Test
  @DisplayName("발행 위치가 미확정이어도 같은 eventId의 격리 원문을 구분한다")
  void correlatesQuarantineBeforePublicationPosition() {
    var event = payment(false);
    feed.items.add(event);
    jdbc.update(
        "INSERT INTO settlement.inbox_quarantines(id,consumer,source_topic,source_partition,source_offset,raw_body,error_code,created_at) VALUES(?,'settlement.v1',?,0,5,?,'UNSUPPORTED_VERSION',clock_timestamp())",
        UUID.randomUUID(),
        event.topic(),
        ("{\"eventId\":\"" + event.eventId() + "\",\"eventVersion\":99}")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    UUID id = service.request(cutoff, "operator:ops");
    tick(3);
    assertThat(status(id)).isEqualTo("WAITING");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM settlement.reconciliation_events", String.class))
        .isEqualTo("QUARANTINED");
  }

  @TestConfiguration
  static class Configuration {
    @Bean
    @Primary
    Feed feed() {
      return new Feed();
    }

    @Bean
    @Primary
    Positions positions() {
      return new Positions();
    }
  }

  static class Feed implements ReconciliationSources {
    final List<Event> items = new ArrayList<>();
    boolean fail;

    public Page payments(Instant cutoff, Instant snapshot, String cursor) {
      if (fail) throw new IllegalStateException("source unavailable");
      int index = cursor == null ? 0 : Integer.parseInt(cursor);
      int end = Math.min(index + 100, items.size());
      return new Page(
          cutoff,
          snapshot == null ? Instant.now().truncatedTo(ChronoUnit.MICROS) : snapshot,
          items.size(),
          end < items.size() ? Integer.toString(end) : null,
          List.copyOf(items.subList(index, end)));
    }

    public Page payouts(Instant cutoff, Instant snapshot, String cursor) {
      return new Page(cutoff, snapshot, 0, null, List.of());
    }
  }

  static class Positions implements ReconciliationPositions {
    Map<String, Long> offsets = Map.of();
    boolean fail;

    Runnable afterRead;

    public Map<String, Long> committed() {
      if (afterRead != null) {
        Runnable action = afterRead;
        afterRead = null;
        action.run();
      }
      if (fail) throw new IllegalStateException("offset unavailable");
      return offsets;
    }
  }
}
