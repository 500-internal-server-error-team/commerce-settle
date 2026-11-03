package com.commercesettle.commerce.application.service;

import static com.commercesettle.commerce.support.ConcurrentTransactions.holdTransaction;
import static com.commercesettle.commerce.support.ConcurrentTransactions.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.commerce.domain.model.CouponFunder;
import com.commercesettle.commerce.domain.model.CouponIssueRun;
import com.commercesettle.commerce.domain.model.CouponIssueRunStatus;
import com.commercesettle.commerce.domain.model.Order;
import com.commercesettle.commerce.infrastructure.batch.GradeCouponExecutions;
import com.commercesettle.commerce.infrastructure.batch.GradeCouponLauncher;
import com.commercesettle.commerce.infrastructure.scheduling.GradeCouponScheduler;
import com.commercesettle.commerce.support.ConcurrentTransactions.HeldTransaction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실제 PostgreSQL과 Spring Batch로 월간 우수고객 회차의 대상 선정·검증·발급·재개·만료와, 운영자 HTTP 경로의 회차 조회·집계 오류 교정·실행 잔류
 * 복구를 확인한다. 회차는 혜택 월마다 하나라 시험마다 다른 월을 쓰고 시각을 그 월에 맞춘다.
 */
// 인증 설정은 필수 기동 값이다. 회차는 직접 실행하므로 스케줄러를 끈다.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.tracing.enabled=false",
      "app.coupons.grade-scheduler-enabled=false",
      "app.coupons.expiry-scheduler-enabled=false",
      "app.auth.session-epoch=00000000-0000-4000-8000-000000000000",
      "app.auth.rate-limit.identifier-hmac-key=grade-coupon-test-hmac-key-32bytes-long",
      "app.auth.password-reset.delivery-key=grade-coupon-test-delivery-key-32bytes",
      "app.address.encryption-key=grade-coupon-test-address-key-32bytes-long",
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123",
      "app.internal-auth.senders[operator\\:test].current=" + GradeCouponIntegrationTest.OPERATOR
    })
@Testcontainers
@Import(GradeCouponIntegrationTest.Beans.class)
class GradeCouponIntegrationTest {
  static final String OPERATOR = "test-operator-credential-0123456789abcdef0123";
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private static final String RUNS = "/internal/coupon-issue-runs";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  @Autowired GradeCouponLauncher launcher;
  @Autowired GradeCouponService grades;
  @Autowired CouponService coupons;
  @Autowired JdbcTemplate jdbc;
  @Autowired TestClock clock;
  @Autowired TestRestTemplate http;
  @Autowired ObjectMapper mapper;
  @Autowired GradeCouponExecutions executions;
  @Autowired JobRepository jobs;
  @Autowired TransactionTemplate tx;

  @Test
  @DisplayName("100위 동점자를 모두 포함해 104명을 고정하고 등급별로 발급하며, 같은 월을 다시 실행해도 바꾸지 않는다")
  void selectsTiesAndIssuesOnce() {
    LocalDate month = LocalDate.of(2031, 2, 1);
    List<UUID> ranked = new ArrayList<>();
    for (int i = 0; i < 99; i++) {
      UUID buyer = UUID.randomUUID();
      ranked.add(buyer);
      confirmed(buyer, 600_000 - i * 5_000L, month.minusMonths(1));
    }
    List<UUID> tied = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      UUID buyer = UUID.randomUUID();
      tied.add(buyer);
      confirmed(buyer, 10_000, month.minusMonths(1));
    }
    UUID below = UUID.randomUUID();
    confirmed(below, 9_000, month.minusMonths(1));
    UUID unconfirmed = UUID.randomUUID();
    order(unconfirmed, 900_000, "PAID", null);
    UUID otherMonth = UUID.randomUUID();
    confirmed(otherMonth, 900_000, month);
    clock.at(month, 0, 20);

    assertThat(launcher.run(month)).contains(CouponIssueRunStatus.COMPLETED);

    CouponIssueRun run = grades.currentRun(month).orElseThrow();
    assertThat(run.getTargetCount()).isEqualTo(104);
    assertThat(run.getCutoffSpend()).isEqualTo(10_000L);
    assertThat(count("coupon_issue_targets where run_id = ? and status = 'ISSUED'", run.getId()))
        .isEqualTo(104);
    assertThat(
            jdbc.queryForList(
                "select rank_no from commerce.coupon_issue_targets where run_id = ? and buyer_id = any(?)",
                Integer.class,
                run.getId(),
                tied.toArray(UUID[]::new)))
        .containsOnly(100);
    assertThat(count("buyer_coupons where buyer_id in (?, ?, ?)", below, unconfirmed, otherMonth))
        .isZero();
    assertThat(gradeOf(ranked.get(0), month)).isEqualTo("GOLD");
    assertThat(gradeOf(ranked.get(98), month)).isEqualTo("BRONZE");
    assertThat(
            jdbc.queryForObject(
                "select sum(discount_amount) from commerce.buyer_coupons where kind = 'GRADE'"
                    + " and target_month = ?",
                Long.class,
                month))
        .isEqualTo(grade(ranked, tied));
    assertThat(
            jdbc.queryForObject(
                "select expires_at from commerce.buyer_coupons where buyer_id = ?",
                Timestamp.class,
                ranked.get(0)))
        .isEqualTo(Timestamp.from(CouponIssueRun.monthStart(month.plusMonths(1))));

    long coupons = count("buyer_coupons where kind = 'GRADE' and target_month = ?", month);
    assertThat(launcher.run(month)).contains(CouponIssueRunStatus.COMPLETED);
    assertThat(count("buyer_coupons where kind = 'GRADE' and target_month = ?", month))
        .isEqualTo(coupons);
  }

  @Test
  @DisplayName("발급한 월간 쿠폰은 플랫폼 부담 할인으로 주문 견적에 쓸 수 있다")
  void issuedCouponIsUsableInOrders() {
    LocalDate month = LocalDate.of(2031, 3, 1);
    UUID buyer = UUID.randomUUID();
    confirmed(buyer, 120_000, month.minusMonths(1));
    clock.at(month, 0, 20);
    launcher.run(month);
    UUID coupon =
        jdbc.queryForObject(
            "select id from commerce.buyer_coupons where buyer_id = ?", UUID.class, buyer);

    CouponService.Discount discount =
        coupons.quote(
            buyer,
            coupon,
            UUID.randomUUID(),
            UUID.randomUUID(),
            50_000,
            Order.ExternalMethod.CREDIT_CARD,
            clock.instant());

    assertThat(discount.amount()).isEqualTo(3_000);
    assertThat(discount.funder()).isEqualTo(CouponFunder.PLATFORM);
  }

  @Test
  @DisplayName("전월 구매 확정이 없으면 대상 0명으로 완료한다")
  void completesEmptyMonth() {
    LocalDate month = LocalDate.of(2031, 4, 1);
    clock.at(month, 0, 20);

    assertThat(launcher.run(month)).contains(CouponIssueRunStatus.COMPLETED);

    CouponIssueRun run = grades.currentRun(month).orElseThrow();
    assertThat(run.getTargetCount()).isZero();
    assertThat(run.getCutoffSpend()).isNull();
  }

  @Test
  @DisplayName("저장한 대상이 원자료 순위와 다르면 검증에 실패해 PREPARING을 보존하고 발급하지 않는다")
  void keepsPreparingWhenVerificationFails() {
    LocalDate month = LocalDate.of(2031, 5, 1);
    for (int i = 0; i < 3; i++) {
      confirmed(UUID.randomUUID(), 100_000 + i, month.minusMonths(1));
    }
    clock.at(month, 0, 20);
    UUID runId = grades.currentRun(month).orElseThrow().getId();
    UUID template = UUID.randomUUID();
    jdbc.update(
        "insert into commerce.coupon_templates (id, kind, funder, grade, target_month, name,"
            + " discount_amount, min_order_amount, min_seller_net_amount, allowed_methods,"
            + " pg_cost_ceiling_bps, other_cost_allowance, issued_count, committed_budget,"
            + " consumed_budget, valid_from, valid_until, status, created_at, updated_at, version)"
            + " values (?, 'GRADE', 'PLATFORM', 'BRONZE', ?, '월간 우수고객 BRONZE', 3000, 50000, 0,"
            + " '{CREDIT_CARD}', 400, 1000, 0, 0, 0, ?, ?, 'ACTIVE', now(), now(), 0)",
        template,
        month,
        Timestamp.from(CouponIssueRun.monthStart(month)),
        Timestamp.from(CouponIssueRun.monthStart(month.plusMonths(1))));
    jdbc.update(
        "insert into commerce.coupon_issue_targets (id, run_id, template_id, buyer_id,"
            + " external_spend, rank_no, status, created_at, updated_at, version)"
            + " values (?, ?, ?, ?, 1, 100, 'PENDING', now(), now(), 0)",
        UUID.randomUUID(),
        runId,
        template,
        UUID.randomUUID());

    assertThat(launcher.run(month)).contains(CouponIssueRunStatus.PREPARING);

    assertThat(count("buyer_coupons where kind = 'GRADE' and target_month = ?", month)).isZero();
  }

  @Test
  @DisplayName("일부 발급 뒤 다시 실행하면 확정한 대상을 다시 선정하지 않고 남은 대상만 발급한다")
  void resumesRemainingTargetsOnly() {
    LocalDate month = LocalDate.of(2031, 6, 1);
    for (int i = 0; i < 5; i++) {
      confirmed(UUID.randomUUID(), 200_000 + i, month.minusMonths(1));
    }
    clock.at(month, 0, 20);
    UUID runId = grades.currentRun(month).orElseThrow().getId();
    grades.prepare(runId);
    List<UUID> firstTwo = grades.pendingTargetIds(runId, 2);
    firstTwo.forEach(target -> grades.issue(runId, target));
    Map<String, Object> issuedBefore = couponOfTarget(firstTwo.get(0));
    confirmed(UUID.randomUUID(), 900_000, month.minusMonths(1));

    assertThat(launcher.run(month)).contains(CouponIssueRunStatus.COMPLETED);

    assertThat(count("coupon_issue_targets where run_id = ?", runId)).isEqualTo(5);
    assertThat(count("buyer_coupons where kind = 'GRADE' and target_month = ?", month))
        .isEqualTo(5);
    assertThat(couponOfTarget(firstTwo.get(0))).isEqualTo(issuedBefore);
  }

  @Test
  @DisplayName("원래 기한이 지나면 새 쿠폰 없이 남은 대상과 회차를 EXPIRED로 종결한다")
  void expiresRemainingTargetsAfterDeadline() {
    LocalDate month = LocalDate.of(2031, 7, 1);
    for (int i = 0; i < 3; i++) {
      confirmed(UUID.randomUUID(), 150_000 + i, month.minusMonths(1));
    }
    clock.at(month, 0, 20);
    UUID runId = grades.currentRun(month).orElseThrow().getId();
    grades.prepare(runId);
    grades.issue(runId, grades.pendingTargetIds(runId, 1).get(0));
    clock.at(month.plusMonths(1), 0, 0);

    assertThat(launcher.run(month)).contains(CouponIssueRunStatus.EXPIRED);

    assertThat(count("coupon_issue_targets where run_id = ? and status = 'EXPIRED'", runId))
        .isEqualTo(2);
    assertThat(count("buyer_coupons where kind = 'GRADE' and target_month = ?", month))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("스케줄러는 지난달에 끝나지 않은 회차도 실행해, 원래 기한이 지났으면 새 발급 없이 남은 대상과 회차를 EXPIRED로 종결한다")
  void schedulerExpiresUnfinishedPreviousMonth() {
    LocalDate november = LocalDate.of(2034, 11, 1);
    for (int i = 0; i < 2; i++) {
      confirmed(UUID.randomUUID(), 100_000 + i, november.minusMonths(1));
    }
    clock.at(november, 0, 20);
    UUID run = grades.currentRun(november).orElseThrow().getId();
    grades.prepare(run);
    grades.issue(run, grades.pendingTargetIds(run, 1).get(0));

    clock.at(november.plusMonths(1), 0, 20);
    new GradeCouponScheduler(launcher, clock, Runnable::run).run();

    assertThat(
            jdbc.queryForObject(
                "select status from commerce.coupon_issue_runs where id = ?", String.class, run))
        .isEqualTo("EXPIRED");
    assertThat(count("coupon_issue_targets where run_id = ? and status = 'ISSUED'", run))
        .isEqualTo(1);
    assertThat(count("coupon_issue_targets where run_id = ? and status = 'EXPIRED'", run))
        .isEqualTo(1);
    assertThat(count("buyer_coupons where target_month = ?", java.sql.Date.valueOf(november)))
        .isEqualTo(1);
    assertThat(grades.currentRun(november.plusMonths(1))).isPresent();
  }

  @Test
  @DisplayName("혜택 월이 마감되기 전에는 회차를 만들지 않는다")
  void doesNotOpenBeforeCutoff() {
    LocalDate month = LocalDate.of(2031, 9, 1);
    clock.at(month.minusMonths(1), 12, 0);

    assertThat(launcher.run(month)).isEmpty();
    assertThat(count("coupon_issue_runs where target_month = ?", month)).isZero();
  }

  @Test
  @DisplayName("운영 조회의 목록·단건 응답이 계약 스키마와 맞고, 월 첫날이 아닌 혜택 월과 없는 회차는 거절한다")
  void listsRunsForOperators() throws Exception {
    LocalDate month = LocalDate.of(2032, 1, 1);
    confirmed(UUID.randomUUID(), 120_000, month.minusMonths(1));
    clock.at(month, 0, 20);
    launcher.run(month);
    UUID runId = grades.currentRun(month).orElseThrow().getId();

    JsonNode page = operator(RUNS + "?targetMonth=2032-01-01&status=COMPLETED", null, 200, key());
    JsonNode run = operator(RUNS + "/" + runId, null, 200, key());

    matches("CouponIssueRunPage", page);
    matches("CouponIssueRunPage", operator(RUNS + "?size=1", null, 200, key()));
    matches("CouponIssueRun", run);
    assertThat(page.path("totalElements").asLong()).isEqualTo(1);
    assertThat(run.path("issuedCount").asLong()).isEqualTo(1);
    assertThat(run.path("targetCount").asInt()).isEqualTo(1);
    assertThat(run.path("replacementRunId").isNull()).isTrue();
    operator(RUNS + "?targetMonth=2032-01-15", null, 400, key());
    assertThat(operator(RUNS + "/" + UUID.randomUUID(), null, 404, key()).path("code").asText())
        .isEqualTo("COMMON_RESOURCE_NOT_FOUND");
  }

  @Test
  @DisplayName("발급 전 회차를 교정하면 원회차 취소·대체 회차·감사 2건을 남기고, 같은 키는 같은 결과, 다른 본문은 409이며 새 회차로 발급한다")
  void replacesRunBeforeIssuance() throws Exception {
    LocalDate month = LocalDate.of(2032, 2, 1);
    for (int i = 0; i < 3; i++) {
      confirmed(UUID.randomUUID(), 100_000 + i, month.minusMonths(1));
    }
    clock.at(month, 0, 20);
    CouponIssueRun original = grades.currentRun(month).orElseThrow();
    String key = key();

    JsonNode created = replace(original.getId(), original.getVersion(), 201, key);
    JsonNode replay = replace(original.getId(), original.getVersion(), 201, key);
    JsonNode changed = replace(original.getId(), original.getVersion() + 1, 409, key);
    JsonNode cancelled = operator(RUNS + "/" + original.getId(), null, 200, key());

    matches("CouponIssueRun", created);
    assertThat(replay).isEqualTo(created);
    assertThat(changed.path("code").asText()).isEqualTo("COMMON_IDEMPOTENCY_CONFLICT");
    assertThat(created.path("replacesRunId").asText()).isEqualTo(original.getId().toString());
    assertThat(created.path("status").asText()).isEqualTo("PREPARING");
    assertThat(created.path("cutoffAt").asText()).isEqualTo(cancelled.path("cutoffAt").asText());
    assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
    assertThat(cancelled.path("cancelledBy").asText()).isEqualTo("operator:test");
    assertThat(cancelled.path("cancellationReasonCode").asText()).isEqualTo("AGGREGATION_ERROR");
    assertThat(cancelled.path("replacementRunId")).isEqualTo(created.path("id"));
    assertThat(
            jdbc.queryForList(
                "select action from commerce.audit_logs where correlation_id = ? order by action",
                String.class,
                original.getId().toString()))
        .containsExactly("COUPON_ISSUE_RUN_CREATED", "COUPON_ISSUE_RUN_REPLACED");

    assertThat(launcher.run(month)).contains(CouponIssueRunStatus.COMPLETED);
    assertThat(
            count(
                "coupon_issue_targets where run_id = ? and status = 'ISSUED'",
                UUID.fromString(created.path("id").asText())))
        .isEqualTo(3);
    assertThat(count("coupon_issue_targets where run_id = ?", original.getId())).isZero();
  }

  @Test
  @DisplayName("발급 이력·버전 불일치·기한 경과·이미 취소한 회차의 교정은 각 사유의 409로 거절한다")
  void rejectsReplacementsThatCannotApply() {
    LocalDate month = LocalDate.of(2032, 3, 1);
    for (int i = 0; i < 2; i++) {
      confirmed(UUID.randomUUID(), 100_000 + i, month.minusMonths(1));
    }
    clock.at(month, 0, 20);
    UUID issuing = grades.currentRun(month).orElseThrow().getId();
    grades.prepare(issuing);
    grades.issue(issuing, grades.pendingTargetIds(issuing, 1).get(0));
    long version = grades.currentRun(month).orElseThrow().getVersion();

    assertThat(code(replace(issuing, version, 409, key()))).isEqualTo("COUPON_RUN_NOT_REPLACEABLE");

    LocalDate next = LocalDate.of(2032, 4, 1);
    clock.at(next, 0, 20);
    CouponIssueRun run = grades.currentRun(next).orElseThrow();
    assertThat(code(replace(run.getId(), run.getVersion() + 1, 409, key())))
        .isEqualTo("COUPON_RUN_VERSION_CONFLICT");
    replace(run.getId(), run.getVersion(), 201, key());
    assertThat(code(replace(run.getId(), run.getVersion(), 409, key())))
        .isEqualTo("COUPON_RUN_NOT_REPLACEABLE");

    CouponIssueRun replacement = grades.currentRun(next).orElseThrow();
    clock.at(next.plusMonths(1), 0, 0);
    assertThat(code(replace(replacement.getId(), replacement.getVersion(), 409, key())))
        .isEqualTo("COUPON_RUN_EXPIRED");
  }

  @Test
  @DisplayName("다른 실행자가 회차 잠금을 쥐고 있으면 교정은 EXECUTION_ACTIVE로 거절하고 발급도 시작하지 않는다")
  void rejectsWhileExecutorHoldsLock() {
    LocalDate month = LocalDate.of(2032, 5, 1);
    confirmed(UUID.randomUUID(), 100_000, month.minusMonths(1));
    clock.at(month, 0, 20);
    CouponIssueRun run = grades.currentRun(month).orElseThrow();

    try (var held = executions.tryLock(run.getId()).orElseThrow()) {
      assertThat(code(replace(run.getId(), run.getVersion(), 409, key())))
          .isEqualTo("COUPON_RUN_EXECUTION_ACTIVE");
      assertThat(launcher.run(month)).contains(CouponIssueRunStatus.PREPARING);
    }

    assertThat(launcher.run(month)).contains(CouponIssueRunStatus.COMPLETED);
  }

  @Test
  @DisplayName("강제 종료로 남은 진행 중 실행은 최근 갱신이면 교정·재실행을 막고, 정체되면 FAILED로 정리한 뒤 같은 회차를 재시작해 완료한다")
  void recoversStaleExecution() throws Exception {
    LocalDate month = LocalDate.of(2032, 6, 1);
    confirmed(UUID.randomUUID(), 100_000, month.minusMonths(1));
    clock.at(month, 0, 20);
    CouponIssueRun run = grades.currentRun(month).orElseThrow();
    JobExecution left =
        jobs.createJobExecution(
            "gradeCouponJob",
            new JobParametersBuilder()
                .addLocalDate("targetMonth", month)
                .addString("runId", run.getId().toString())
                .toJobParameters());

    assertThat(code(replace(run.getId(), run.getVersion(), 409, key())))
        .isEqualTo("COUPON_RUN_EXECUTION_ACTIVE");
    assertThat(launcher.run(month)).contains(CouponIssueRunStatus.PREPARING);

    jdbc.update(
        "update commerce.batch_job_execution set create_time = now() - interval '1 hour',"
            + " last_updated = now() - interval '1 hour' where job_execution_id = ?",
        left.getId());

    assertThat(launcher.run(month)).contains(CouponIssueRunStatus.COMPLETED);
    assertThat(
            jdbc.queryForObject(
                "select status from commerce.batch_job_execution where job_execution_id = ?",
                String.class,
                left.getId()))
        .isEqualTo("FAILED");
  }

  @Test
  @Timeout(60)
  @DisplayName("첫 발급이 회차 행을 잡고 있으면 교정은 잠금을 기다렸다가 커밋된 발급을 보고 NOT_REPLACEABLE로 거절한다")
  void rejectsReplacementWaitingOnFirstIssue() throws Exception {
    LocalDate month = LocalDate.of(2033, 1, 1);
    for (int i = 0; i < 2; i++) {
      confirmed(UUID.randomUUID(), 100_000 + i, month.minusMonths(1));
    }
    clock.at(month, 0, 20);
    UUID run = grades.currentRun(month).orElseThrow().getId();
    grades.prepare(run);
    long version = grades.currentRun(month).orElseThrow().getVersion();
    UUID first = grades.pendingTargetIds(run, 1).get(0);

    ExecutorService replacing = Executors.newSingleThreadExecutor();
    try {
      Future<JsonNode> replaced;
      try (HeldTransaction held =
          holdTransaction(tx, () -> assertThat(grades.issue(run, first)).isTrue())) {
        replaced = replacing.submit(() -> replace(run, version, 409, key()));
        awaitLockWaiter(replaced);
      }
      assertThat(code(replaced.get(10, TimeUnit.SECONDS))).isEqualTo("COUPON_RUN_NOT_REPLACEABLE");
    } finally {
      replacing.shutdownNow();
    }

    CouponIssueRun current = grades.currentRun(month).orElseThrow();
    assertThat(current.getId()).isEqualTo(run);
    assertThat(current.getStatus()).isEqualTo(CouponIssueRunStatus.ISSUING);
    assertThat(count("coupon_issue_targets where run_id = ? and status = 'ISSUED'", run))
        .isEqualTo(1);
  }

  @Test
  @Timeout(120)
  @DisplayName("교정과 첫 발급을 동시에 실행하면 둘 중 하나만 반영되어, 취소된 회차에 발급이 남거나 둘 다 거절되지 않는다")
  void serializesReplacementAndFirstIssue() throws Exception {
    for (int round = 0; round < 5; round++) {
      LocalDate month = LocalDate.of(2033, 2 + round, 1);
      for (int i = 0; i < 2; i++) {
        confirmed(UUID.randomUUID(), 100_000 + i, month.minusMonths(1));
      }
      clock.at(month, 0, 20);
      UUID run = grades.currentRun(month).orElseThrow().getId();
      grades.prepare(run);
      long version = grades.currentRun(month).orElseThrow().getVersion();
      UUID first = grades.pendingTargetIds(run, 1).get(0);
      AtomicInteger role = new AtomicInteger();

      List<Object> results =
          runConcurrently(
              2,
              () ->
                  role.getAndIncrement() == 0
                      ? (Object) grades.issue(run, first)
                      : replaceStatus(run, version));

      Object issued = results.stream().filter(Boolean.class::isInstance).findFirst().orElseThrow();
      Object status = results.stream().filter(Integer.class::isInstance).findFirst().orElseThrow();
      long issuedTargets =
          count("coupon_issue_targets where run_id = ? and status = 'ISSUED'", run);
      String runStatus =
          jdbc.queryForObject(
              "select status from commerce.coupon_issue_runs where id = ?", String.class, run);
      if (status.equals(201)) {
        assertThat(issued).as("round %d", round).isEqualTo(false);
        assertThat(issuedTargets).as("round %d", round).isZero();
        assertThat(runStatus).as("round %d", round).isEqualTo("CANCELLED");
      } else {
        assertThat(status).as("round %d", round).isEqualTo(409);
        assertThat(issued).as("round %d", round).isEqualTo(true);
        assertThat(issuedTargets).as("round %d", round).isEqualTo(1);
        assertThat(runStatus).as("round %d", round).isEqualTo("ISSUING");
      }
    }
  }

  private JsonNode replace(UUID runId, long expectedVersion, int expected, String key) {
    ObjectNode body =
        mapper
            .createObjectNode()
            .put("expectedVersion", expectedVersion)
            .put("reasonCode", "AGGREGATION_ERROR");
    return operator(RUNS + "/" + runId + "/replacements", body, expected, key);
  }

  private JsonNode operator(String path, ObjectNode body, int expected, String key) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(OPERATOR);
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", key);
    ResponseEntity<JsonNode> response =
        http.exchange(
            path,
            body == null ? HttpMethod.GET : HttpMethod.POST,
            new HttpEntity<>(body, headers),
            JsonNode.class);
    assertThat(response.getStatusCode().value())
        .as("%s: %s", path, response.getBody())
        .isEqualTo(expected);
    if (expected == 201) {
      assertThat(response.getHeaders().getLocation())
          .hasPath(RUNS + "/" + response.getBody().path("id").asText());
    }
    return response.getBody();
  }

  /** 상태 코드를 단정하지 않고 돌려준다. 동시 실행 결과를 모을 때 쓴다. */
  private int replaceStatus(UUID runId, long expectedVersion) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(OPERATOR);
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", key());
    ObjectNode body =
        mapper
            .createObjectNode()
            .put("expectedVersion", expectedVersion)
            .put("reasonCode", "AGGREGATION_ERROR");
    return http.exchange(
            RUNS + "/" + runId + "/replacements",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            JsonNode.class)
        .getStatusCode()
        .value();
  }

  // 교정이 회차 행 잠금을 기다릴 때까지 기다린다. 잠금 대기 상한(1초)보다 짧은 간격으로 확인한다.
  private void awaitLockWaiter(Future<?> waiting) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (jdbc.queryForObject(
            "SELECT count(*) FROM pg_stat_activity"
                + " WHERE datname = current_database() AND wait_event_type = 'Lock'",
            Long.class)
        == 0) {
      assertThat(waiting).as("잠금 대기 전에 교정이 끝났다").isNotDone();
      assertThat(System.nanoTime()).as("잠금 대기를 확인하지 못했다").isLessThan(deadline);
      Thread.sleep(10);
    }
  }

  private static String code(JsonNode error) {
    return error.path("code").asText();
  }

  private void matches(String name, JsonNode node) throws Exception {
    var canonical = mapper.readTree(getClass().getResourceAsStream("/contracts/openapi.json"));
    var root = mapper.createObjectNode();
    root.set("components", canonical.path("components"));
    root.put("$ref", "#/components/schemas/" + name);
    var schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(
                SchemaLocation.of("urn:test:grade-coupon:" + name),
                root.toString(),
                InputFormat.JSON);
    assertThat(schema.validate(node.toString(), InputFormat.JSON)).isEmpty();
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private long grade(List<UUID> ranked, List<UUID> tied) {
    return jdbc.queryForObject(
        "select sum(case when spend >= 500000 then 7000 when spend >= 300000 then 5000 else 3000"
            + " end) from (select buyer_id, sum(external_amount) as spend from commerce.orders"
            + " where buyer_id = any(?) group by buyer_id) s",
        Long.class,
        (Object)
            java.util.stream.Stream.concat(ranked.stream(), tied.stream()).toArray(UUID[]::new));
  }

  private String gradeOf(UUID buyer, LocalDate month) {
    return jdbc.queryForObject(
        "select t.grade from commerce.buyer_coupons c join commerce.coupon_templates t"
            + " on t.id = c.template_id where c.buyer_id = ? and c.target_month = ?",
        String.class,
        buyer,
        month);
  }

  private Map<String, Object> couponOfTarget(UUID target) {
    return jdbc.queryForMap(
        "select c.id, c.created_at from commerce.coupon_issue_targets t"
            + " join commerce.buyer_coupons c on c.id = t.buyer_coupon_id where t.id = ?",
        target);
  }

  /** 대상 월 중순에 구매 확정된 주문 한 건. */
  private void confirmed(UUID buyer, long externalAmount, LocalDate month) {
    order(
        buyer,
        externalAmount,
        "PURCHASE_CONFIRMED",
        month.withDayOfMonth(15).atTime(12, 0).atZone(KST).toInstant());
  }

  private void order(UUID buyer, long amount, String status, Instant confirmedAt) {
    Instant created =
        confirmedAt == null
            ? Instant.parse("2031-01-10T00:00:00Z")
            : confirmedAt.minusSeconds(864_000);
    Instant delivered = created.plusSeconds(86_400);
    jdbc.update(
        "insert into commerce.orders (id, buyer_id, seller_id, product_id, product_name,"
            + " unit_price, quantity, gross_amount, coupon_amount, seller_coupon_amount,"
            + " platform_coupon_amount, settlement_base_amount, total_amount, mileage_amount,"
            + " external_amount, payment_cycle_id, external_method, method_account_ref, status,"
            + " fulfillment_status, simulation_seed, delivery_business_days,"
            + " delivery_calendar_version, delivery_calendar, shipped_at, delivery_due_at,"
            + " delivered_at, refund_deadline_at, purchase_confirmed_at, closed_at, payment_wait_until,"
            + " policy_version, created_at, updated_at, version)"
            + " values (?, ?, ?, ?, '시험 상품', ?, 1, ?, 0, 0, 0, ?, ?, 0, ?, ?, 'CREDIT_CARD',"
            + " 'test-card', ?, ?, 1, 2, 'test', '{}'::jsonb, ?, ?, ?, ?, ?, ?, ?, 'test', ?, ?, 0)",
        UUID.randomUUID(),
        buyer,
        UUID.randomUUID(),
        UUID.randomUUID(),
        amount,
        amount,
        amount,
        amount,
        amount,
        UUID.randomUUID(),
        status,
        confirmedAt == null ? "UNSHIPPED" : "DELIVERED",
        confirmedAt == null ? null : Timestamp.from(created),
        confirmedAt == null ? null : Timestamp.from(delivered),
        confirmedAt == null ? null : Timestamp.from(delivered),
        confirmedAt == null ? null : Timestamp.from(delivered.plusSeconds(168 * 3_600)),
        confirmedAt == null ? null : Timestamp.from(confirmedAt),
        confirmedAt == null ? null : Timestamp.from(confirmedAt),
        Timestamp.from(created.plusSeconds(600)),
        Timestamp.from(created),
        Timestamp.from(created));
  }

  private long count(String fromWhere, Object... args) {
    return jdbc.queryForObject("select count(*) from commerce." + fromWhere, Long.class, args);
  }

  @TestConfiguration
  static class Beans {
    @Bean
    @Primary
    TestClock testClock() {
      return new TestClock();
    }
  }

  static class TestClock extends Clock {
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

    void at(LocalDate day, int hour, int minute) {
      now.set(LocalDateTime.of(day, java.time.LocalTime.of(hour, minute)).atZone(KST).toInstant());
    }

    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now.get();
    }
  }
}
