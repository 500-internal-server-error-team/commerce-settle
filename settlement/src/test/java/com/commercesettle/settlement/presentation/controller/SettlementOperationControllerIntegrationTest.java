package com.commercesettle.settlement.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercesettle.settlement.application.service.SellerSettlementService;
import com.commercesettle.settlement.domain.model.Payout;
import com.commercesettle.settlement.domain.model.PayoutJob;
import com.commercesettle.settlement.domain.model.PayoutRecipient;
import com.commercesettle.settlement.domain.model.SettlementAmounts;
import com.commercesettle.settlement.domain.model.SettlementBatch;
import com.commercesettle.settlement.domain.model.SettlementItem;
import com.commercesettle.settlement.domain.repository.PayoutJobRepository;
import com.commercesettle.settlement.domain.repository.PayoutRepository;
import com.commercesettle.settlement.domain.repository.SettlementBatchRepository;
import com.commercesettle.settlement.domain.repository.SettlementItemRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL과 운영자 자격 판정을 거쳐 지급 작업 조회·재처리와 정산 배치 생성·재개를 확인한다. */
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "spring.kafka.admin.auto-create=false",
      "app.internal-auth.senders.gateway.current="
          + SettlementOperationControllerIntegrationTest.GATEWAY,
      "app.internal-auth.senders[operator\\:ops].current="
          + SettlementOperationControllerIntegrationTest.OPERATOR,
      "app.internal-auth.senders[service\\:commerce].current=test-commerce-retention-credential-0123456789abcdef",
      "settlement.payout.mock-credential=test-settlement-credential-0123456789abcdef0123",
      "settlement.daily-batch.schedule-enabled=false",
      "settlement.payout-worker.enabled=false",
      "settlement.inbox-processor.enabled=false"
    })
@AutoConfigureMockMvc
@Testcontainers
class SettlementOperationControllerIntegrationTest {
  static final String GATEWAY = "test-gateway-credential-0123456789abcdef0123";
  static final String OPERATOR = "test-operator-credential-0123456789abcdef0123";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  private static final Instant T1 = Instant.parse("2026-10-20T01:00:00Z");
  private static final long BASE = 10_000;
  private static final long FEE = BASE * 15 / 100;
  private static final LocalDate DUE_DATE = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired SettlementItemRepository items;
  @Autowired SettlementBatchRepository batches;
  @Autowired PayoutRepository payouts;
  @Autowired PayoutJobRepository payoutJobs;
  @Autowired SellerSettlementService sellers;
  @Autowired TransactionTemplate transactions;

  private final AtomicInteger batchDays = new AtomicInteger();

  @AfterEach
  void deleteRows() {
    for (String table :
        List.of(
            "settlement_items",
            "settlement_batches",
            "payouts",
            "payout_jobs",
            "payout_job_replays",
            "settlement_reservations",
            "idempotency_records",
            "audit_logs")) {
      jdbc.update("DELETE FROM settlement." + table);
    }
  }

  @Test
  @DisplayName("운영자는 지급 작업을 최신순으로 보고, Gateway 자격은 403·자격 없음은 401이다")
  void listsJobsForOperatorOnly() throws Exception {
    Payout older = payout(T1);
    Payout newer = payout(T1.plusSeconds(60));

    mvc.perform(asOperator(get("/internal/payout-jobs")))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[*].resourceId")
                .value(contains(newer.getId().toString(), older.getId().toString())))
        .andExpect(jsonPath("$.items[0].operation").value("PAYOUT"))
        .andExpect(jsonPath("$.items[0].status").value("PENDING"))
        .andExpect(jsonPath("$.items[0].lastErrorCode").value(nullValue()))
        .andExpect(jsonPath("$.totalElements").value(2));
    mvc.perform(get("/internal/payout-jobs").header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY))
        .andExpect(status().isForbidden());
    mvc.perform(get("/internal/payout-jobs")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("결과를 모르는 채 운영 확인에 멈춘 작업을 같은 업무 키로 다시 열고, 이력·감사를 한 번만 남긴다")
  void replaysUnknownPayout() throws Exception {
    Payout payout = payout(T1);
    stop(payout, "UNKNOWN", "UNKNOWN");
    long version = jobVersion(payout);
    String body = replay(version, "대역 장애 복구 확인");

    replay(payout, "replay-key-0001", body)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.attempts").value(0))
        .andExpect(jsonPath("$.version").value(version + 1));

    assertThat(batchStatus(payout)).isEqualTo("PROCESSING");
    Map<String, Object> history = jdbc.queryForMap("SELECT * FROM settlement.payout_job_replays");
    assertThat(history)
        .containsEntry("previous_status", "REVIEW_REQUIRED")
        .containsEntry("previous_attempts", 10)
        .containsEntry("operator_id", "operator:ops")
        .containsEntry("reason", "대역 장애 복구 확인")
        .containsEntry("expected_version", version);
    assertThat(
            jdbc.queryForObject(
                "SELECT business_key FROM settlement.payout_jobs WHERE payout_id = ?",
                UUID.class,
                payout.getId()))
        .isEqualTo(payout.getBusinessKey());

    // 같은 키·본문은 현재 상태를 돌려주고 다시 열지 않는다.
    replay(payout, "replay-key-0001", body)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.version").value(version + 1));
    assertThat(count("payout_job_replays")).isEqualTo(1);
    assertThat(count("audit_logs")).isEqualTo(1);
    // 같은 배치의 생성·재처리 감사를 묶어 볼 수 있게 정산 배치 ID로 추적한다.
    assertThat(auditCorrelation()).isEqualTo(payout.getBatchId().toString());
    replay(payout, "replay-key-0001", replay(version, "다른 사유"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("COMMON_IDEMPOTENCY_CONFLICT"));
    replay(payout, "replay-key-0002", body)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("COMMON_VERSION_CONFLICT"));
  }

  @Test
  @DisplayName("호출 전 지급의 작업을 다시 열면 배치는 예약으로 돌아간다")
  void replaysPendingPayout() throws Exception {
    Payout payout = payout(T1);
    stop(payout, "PENDING", null);

    replay(payout, "replay-key-0003", replay(jobVersion(payout), "재시도 소진 확인"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("PENDING"));

    assertThat(batchStatus(payout)).isEqualTo("RESERVED");
  }

  @Test
  @DisplayName("확정 거절·실행 중·완료 작업과 없는 작업은 바꾸지 않고 거절한다")
  void rejectsWhatCannotBeReplayed() throws Exception {
    Payout declined = payout(T1);
    stop(declined, "DECLINED", "CONFIRMED_REJECTION");
    Payout running = payout(T1.plusSeconds(1));
    jdbc.update(
        "UPDATE settlement.payout_jobs SET status = 'RUNNING', lease_owner = 'w',"
            + " lease_token = gen_random_uuid(), lease_until = now() + interval '1 minute'"
            + " WHERE payout_id = ?",
        running.getId());
    Payout done = payout(T1.plusSeconds(2));
    jdbc.update(
        "UPDATE settlement.payout_jobs SET status = 'DONE' WHERE payout_id = ?", done.getId());

    replay(declined, "replay-key-0004", replay(jobVersion(declined), "확인"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAYOUT_JOB_NOT_REPLAYABLE"));
    replay(running, "replay-key-0005", replay(jobVersion(running), "확인"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAYOUT_JOB_LEASE_ACTIVE"));
    replay(done, "replay-key-0006", replay(jobVersion(done), "확인"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAYOUT_JOB_NOT_REPLAYABLE"));
    mvc.perform(
            asOperator(post("/internal/payout-jobs/{id}/replays", UUID.randomUUID()))
                .header("Idempotency-Key", "replay-key-0007")
                .contentType(MediaType.APPLICATION_JSON)
                .content(replay(0, "확인")))
        .andExpect(status().isNotFound());
    mvc.perform(
            asOperator(post("/internal/payout-jobs/{id}/replays", jobId(declined)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(replay(0, "확인")))
        .andExpect(status().isBadRequest());
    assertThat(count("payout_job_replays")).isZero();
    assertThat(count("idempotency_records")).isZero();
    assertThat(count("audit_logs")).isZero();
  }

  @Test
  @DisplayName("다른 트랜잭션이 작업 행을 잠그고 있으면 상한 안에 503으로 끝나고 아무것도 바꾸지 않으며, 풀린 뒤 같은 키로 다시 열 수 있다")
  void replayGivesUpOnLockedJob() throws Exception {
    Payout payout = payout(T1);
    stop(payout, "UNKNOWN", "UNKNOWN");
    long version = jobVersion(payout);
    String body = replay(version, "대역 장애 복구 확인");
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> holder =
          executor.submit(
              () ->
                  transactions.executeWithoutResult(
                      status -> {
                        jdbc.queryForList(
                            "SELECT id FROM settlement.payout_jobs WHERE payout_id = ? FOR UPDATE",
                            payout.getId());
                        locked.countDown();
                        await(release);
                      }));
      assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
      long started = System.nanoTime();
      Future<ResultActions> request =
          executor.submit(() -> replay(payout, "replay-key-0008", body));
      // 상한이 없으면 잠금이 풀릴 때까지 기다리므로 여기서 시간 초과로 실패한다.
      request
          .get(5, TimeUnit.SECONDS)
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("COMMON_TEMPORARILY_UNAVAILABLE"));
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
      release.countDown();
      holder.get(10, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      executor.shutdownNow();
    }

    assertThat(jobVersion(payout)).isEqualTo(version);
    assertThat(batchStatus(payout)).isEqualTo("REVIEW_REQUIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM settlement.payouts WHERE id = ?", String.class, payout.getId()))
        .isEqualTo("UNKNOWN");
    assertThat(count("payout_job_replays")).isZero();
    assertThat(count("idempotency_records")).isZero();
    assertThat(count("audit_logs")).isZero();

    // 실패한 요청은 멱등 기록을 남기지 않아 같은 키로 다시 요청할 수 있다.
    replay(payout, "replay-key-0008", body)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.version").value(version + 1));
    assertThat(count("payout_job_replays")).isEqualTo(1);
  }

  @Test
  @DisplayName("마감이 지난 판매자 배치를 만들고, 같은 키는 같은 배치·다른 키는 기존 배치를 돌려주며 감사는 생성 한 번이다")
  void createsBatchOnceForSellerAndDate() throws Exception {
    UUID seller = UUID.randomUUID();
    payable(seller);
    payable(seller);
    String body = batch(seller, DUE_DATE, "delivery-daily-v1");

    String id =
        JsonPath.read(
            createBatch("batch-key-0001", body)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.netAmount").value(2 * (BASE - FEE)))
                .andExpect(jsonPath("$.batchDate").value(DUE_DATE.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.id");

    createBatch("batch-key-0001", body)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value(id));
    createBatch("batch-key-0002", body)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value(id));
    assertThat(count("settlement_batches")).isEqualTo(1);
    assertThat(count("payouts")).isEqualTo(1);
    assertThat(count("audit_logs")).isEqualTo(1);
    assertThat(auditCorrelation()).isEqualTo(id);
    createBatch("batch-key-0001", batch(seller, DUE_DATE.minusDays(1), "delivery-daily-v1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("COMMON_IDEMPOTENCY_CONFLICT"));
  }

  @Test
  @DisplayName("마감 전 날짜·지원하지 않는 정책·대상 없음은 배치 없이 409다")
  void rejectsBatchThatCannotBeCreated() throws Exception {
    UUID seller = UUID.randomUUID();
    createBatch(
            "batch-key-0003",
            batch(seller, LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1), "delivery-daily-v1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SETTLEMENT_BATCH_NOT_DUE"));
    createBatch("batch-key-0004", batch(seller, DUE_DATE, "delivery-daily-v0"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SETTLEMENT_POLICY_UNSUPPORTED"));
    createBatch("batch-key-0005", batch(seller, DUE_DATE, "delivery-daily-v1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SETTLEMENT_NO_ELIGIBLE_ITEMS"));
    assertThat(count("settlement_batches")).isZero();
    assertThat(count("idempotency_records")).isZero();
  }

  @Test
  @DisplayName("같은 판매자를 일배치가 먼저 선점하는 동안 요청하면 기다렸다가 그 배치를 돌려주고 항목은 한 배치에만 들어간다")
  void concurrentDailyBatchReturnsCommittedBatch() throws Exception {
    UUID seller = UUID.randomUUID();
    payable(seller);
    CountDownLatch reserved = new CountDownLatch(1);
    CountDownLatch commit = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> daily =
          executor.submit(
              () ->
                  transactions.executeWithoutResult(
                      status -> {
                        assertThat(sellers.settle(seller, DUE_DATE)).isPresent();
                        reserved.countDown();
                        await(commit);
                      }));
      assertThat(reserved.await(10, TimeUnit.SECONDS)).isTrue();
      Future<ResultActions> operator =
          executor.submit(
              () -> createBatch("batch-key-0006", batch(seller, DUE_DATE, "delivery-daily-v1")));
      awaitLockWaitOrDone(operator);
      commit.countDown();
      daily.get(10, TimeUnit.SECONDS);
      operator
          .get(10, TimeUnit.SECONDS)
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.status").value("RESERVED"));
    } finally {
      commit.countDown();
      executor.shutdownNow();
    }
    assertThat(count("settlement_batches")).isEqualTo(1);
    assertThat(count("payouts")).isEqualTo(1);
    assertThat(count("audit_logs")).isZero();
  }

  private MockHttpServletRequestBuilder asOperator(MockHttpServletRequestBuilder request) {
    return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + OPERATOR);
  }

  private ResultActions replay(Payout payout, String key, String body) throws Exception {
    return mvc.perform(
        asOperator(post("/internal/payout-jobs/{id}/replays", jobId(payout)))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions createBatch(String key, String body) throws Exception {
    return mvc.perform(
        asOperator(post("/internal/settlement-batches"))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private static String replay(long expectedVersion, String reason) {
    return "{\"expectedVersion\":%d,\"reason\":\"%s\"}".formatted(expectedVersion, reason);
  }

  private static String batch(UUID seller, LocalDate date, String policy) {
    return "{\"sellerId\":\"%s\",\"batchDate\":\"%s\",\"policyVersion\":\"%s\"}"
        .formatted(seller, date, policy);
  }

  private Payout payout(Instant createdAt) {
    UUID seller = UUID.randomUUID();
    SettlementBatch batch =
        batches.save(
            SettlementBatch.reserve(
                seller,
                LocalDate.of(2026, 9, 1).plusDays(batchDays.getAndIncrement()),
                "delivery-daily-v1",
                BASE - FEE,
                createdAt));
    Payout payout =
        payouts.save(
            Payout.create(
                UUID.randomUUID(),
                batch.getId(),
                seller,
                new PayoutRecipient("acct-" + seller, 1),
                "funding-account",
                "payout-v1",
                BASE - FEE,
                createdAt));
    payoutJobs.save(PayoutJob.schedule(payout, createdAt));
    return payout;
  }

  /** 자동 시도를 다 써서 운영 확인으로 멈춘 상태를 만든다. */
  private void stop(Payout payout, String payoutStatus, String holdReason) {
    jdbc.update(
        "UPDATE settlement.payouts SET status = ?, hold_reason = ? WHERE id = ?",
        payoutStatus,
        holdReason,
        payout.getId());
    jdbc.update(
        "UPDATE settlement.payout_jobs SET status = 'REVIEW_REQUIRED', attempts = 10,"
            + " version = version + 1 WHERE payout_id = ?",
        payout.getId());
    jdbc.update(
        "UPDATE settlement.settlement_batches SET status = 'REVIEW_REQUIRED' WHERE id = ?",
        payout.getBatchId());
  }

  private SettlementItem payable(UUID seller) {
    Instant readyAt = SettlementBatch.cutoffOf(DUE_DATE).minusSeconds(3600);
    SettlementItem item =
        SettlementItem.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
            seller,
            new SettlementAmounts(BASE, 0, BASE, BASE, 0, FEE, BASE - FEE),
            0,
            readyAt.minusSeconds(600));
    item.markPayable(readyAt, readyAt.minusSeconds(300), readyAt);
    return items.save(item);
  }

  private UUID jobId(Payout payout) {
    return jdbc.queryForObject(
        "SELECT id FROM settlement.payout_jobs WHERE payout_id = ?", UUID.class, payout.getId());
  }

  private long jobVersion(Payout payout) {
    return jdbc.queryForObject(
        "SELECT version FROM settlement.payout_jobs WHERE payout_id = ?",
        Long.class,
        payout.getId());
  }

  private String batchStatus(Payout payout) {
    return jdbc.queryForObject(
        "SELECT status FROM settlement.settlement_batches WHERE id = ?",
        String.class,
        payout.getBatchId());
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM settlement." + table, Long.class);
  }

  private String auditCorrelation() {
    return jdbc.queryForObject("SELECT correlation_id FROM settlement.audit_logs", String.class);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** 작업이 행 잠금을 기다리기 시작하거나 기다리지 않고 끝날 때까지 기다린다. */
  private void awaitLockWaitOrDone(Future<?> task) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!task.isDone() && System.nanoTime() < deadline) {
      Long waiting =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity"
                  + " WHERE datname = current_database() AND wait_event_type = 'Lock'",
              Long.class);
      if (waiting != null && waiting > 0) {
        return;
      }
      Thread.sleep(10);
    }
  }
}
