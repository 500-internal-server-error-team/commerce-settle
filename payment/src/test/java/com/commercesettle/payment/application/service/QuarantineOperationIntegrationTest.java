package com.commercesettle.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.commercesettle.payment.application.port.OperatorRequestStore;
import com.commercesettle.payment.application.port.QuarantineStore;
import com.commercesettle.payment.application.port.QuarantineStore.DeliveryView;
import com.commercesettle.payment.application.port.QuarantineStore.QuarantineView;
import com.commercesettle.payment.application.port.QuarantineStore.Source;
import com.commercesettle.payment.domain.exception.IdempotencyConflictException;
import com.commercesettle.payment.domain.exception.QuarantineNotFoundException;
import com.commercesettle.payment.domain.exception.QuarantineOperationRejectedException;
import com.commercesettle.payment.domain.exception.QuarantineOperationRejectedException.Reason;
import com.commercesettle.payment.infrastructure.persistence.JdbcOperatorRequestStore;
import com.commercesettle.payment.infrastructure.persistence.inbox.JdbcQuarantineStore;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL에서 격리 원문 운영 조회·원키 재처리·소진 발행 재개의 상태 검사와 멱등·감사 기록을 확인한다. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringJUnitConfig(QuarantineOperationIntegrationTest.Config.class)
@Timeout(60)
class QuarantineOperationIntegrationTest {
  private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
  private static final String OPERATOR = "operator:ops-1";
  private static final byte[] BODY = "{\"broken\":".getBytes(StandardCharsets.UTF_8);

  /** 테스트 DataSource가 준비한 SQL 문장 수. 목록 조회가 원문 수만큼 SQL을 늘리지 않는지 본다. */
  private static final AtomicInteger STATEMENTS = new AtomicInteger();

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3")
          .withInitScript("db/payment.sql")
          .withCreateContainerCmdModifier(
              cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024));

  @Autowired private QuarantineOperationService operations;
  @Autowired private QuarantineStore store;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager manager;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM payment.idempotency_records");
    jdbc.update("DELETE FROM payment.audit_logs");
    jdbc.update("DELETE FROM payment.inbox_quarantine_resumes");
    jdbc.update("DELETE FROM payment.inbox_quarantine_deliveries");
    jdbc.update("DELETE FROM payment.inbox_quarantines");
  }

  @Test
  @DisplayName("조회는 원문 bytes 대신 크기와 SHA-256을 주고, 발행 상태로 원문을 고른다")
  void listsWithoutRawBody() throws Exception {
    UUID first = capture(0);
    UUID second = capture(1);
    jdbc.update(
        "UPDATE payment.inbox_quarantine_deliveries SET status = 'REVIEW_REQUIRED', attempts = 10"
            + " WHERE quarantine_id = ?",
        second);

    QuarantineView view = operations.get(first);
    assertThat(view.bodySize()).isEqualTo(BODY.length);
    assertThat(view.bodySha256())
        .isEqualTo(
            HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(BODY)));
    assertThat(view.deliveries())
        .singleElement()
        .satisfies(d -> assertThat(d.kind()).isEqualTo("DLT"));

    assertThat(operations.list(null, 0, 20).totalElements()).isEqualTo(2);
    assertThat(operations.list("REVIEW_REQUIRED", 0, 20).items())
        .extracting(QuarantineView::id)
        .containsExactly(second);
    assertThatThrownBy(() -> operations.get(UUID.randomUUID()))
        .isInstanceOf(QuarantineNotFoundException.class);
  }

  @Test
  @DisplayName("재처리는 원래 topic으로 REPLAY를 만들고 멱등·감사를 남기며, 같은 키·본문 재요청은 같은 결과를, 다른 본문은 멱등 충돌을 낸다")
  void replaysOnceWithSameKey() {
    UUID id = capture(0);

    QuarantineView replayed = operations.replay(OPERATOR, id, "replay-key-0001", 0, "SCHEMA_FIXED");

    assertThat(replayed.version()).isEqualTo(1);
    DeliveryView replay =
        replayed.deliveries().stream()
            .filter(d -> d.kind().equals("REPLAY"))
            .findFirst()
            .orElseThrow();
    assertThat(replay.status()).isEqualTo("PENDING");
    assertThat(replay.topic()).isEqualTo("commerce-settle.commerce.v1");
    assertThat(replay.operatorId()).isEqualTo(OPERATOR);
    assertThat(replay.reasonCode()).isEqualTo("SCHEMA_FIXED");
    Map<String, Object> audit = jdbc.queryForMap("SELECT * FROM payment.audit_logs");
    assertThat(audit)
        .containsEntry("actor_kind", "OPERATOR")
        .containsEntry("actor_ref", OPERATOR)
        .containsEntry("action", "QUARANTINE_REPLAY_REQUESTED")
        .containsEntry("aggregate_type", "INBOX_QUARANTINE_DELIVERY")
        .containsEntry("aggregate_id", replay.id())
        .containsEntry("after_status", "PENDING")
        .containsEntry("reason_code", "SCHEMA_FIXED")
        .containsEntry("correlation_id", id.toString());
    assertThat(
            jdbc.queryForMap(
                "SELECT actor_kind, actor_id, operation, resource_id, response_status, status"
                    + " FROM payment.idempotency_records"))
        .containsEntry("actor_kind", "OPERATOR")
        .containsEntry("actor_id", OPERATOR)
        .containsEntry("operation", "QUARANTINE_REPLAY")
        .containsEntry("resource_id", id)
        .containsEntry("response_status", 202)
        .containsEntry("status", "COMPLETED");

    assertThat(operations.replay(OPERATOR, id, "replay-key-0001", 0, "SCHEMA_FIXED"))
        .isEqualTo(replayed);
    assertThat(replays(id)).isEqualTo(1);
    assertThat(count("audit_logs")).isEqualTo(1);

    assertThatThrownBy(() -> operations.replay(OPERATOR, id, "replay-key-0001", 0, "OTHER_REASON"))
        .isInstanceOf(IdempotencyConflictException.class);
    assertRejected(
        () -> operations.replay(OPERATOR, id, "replay-key-0002", 0, "SCHEMA_FIXED"),
        Reason.VERSION_CONFLICT);
    assertRejected(
        () -> operations.replay(OPERATOR, id, "replay-key-0003", 1, "SCHEMA_FIXED"),
        Reason.REPLAY_ACTIVE);
    assertThatThrownBy(
            () -> operations.replay(OPERATOR, UUID.randomUUID(), "replay-key-0004", 0, "X"))
        .isInstanceOf(QuarantineNotFoundException.class);
    assertThat(replays(id)).isEqualTo(1);
    assertThat(count("audit_logs")).isEqualTo(1);
    assertThat(count("idempotency_records")).isEqualTo(1);
  }

  @Test
  @DisplayName("같은 키·본문의 재처리가 동시에 오면 REPLAY·감사를 하나만 남기고 두 요청 모두 같은 결과를 받는다")
  void replaysOnceForConcurrentSameKey() throws Exception {
    UUID id = capture(0);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService callers = Executors.newFixedThreadPool(2);
    List<Future<QuarantineView>> results = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      results.add(
          callers.submit(
              () -> {
                start.await();
                return operations.replay(OPERATOR, id, "replay-key-0009", 0, "SCHEMA_FIXED");
              }));
    }
    start.countDown();
    List<QuarantineView> views = new ArrayList<>();
    for (Future<QuarantineView> result : results) {
      views.add(result.get(30, TimeUnit.SECONDS));
    }
    callers.shutdown();

    assertThat(views).allSatisfy(view -> assertThat(view.version()).isEqualTo(1));
    assertThat(replays(id)).isEqualTo(1);
    assertThat(count("audit_logs")).isEqualTo(1);
    assertThat(count("idempotency_records")).isEqualTo(1);
  }

  @Test
  @DisplayName("재개는 시도를 다 쓴 같은 작업을 시도 수 0으로 되돌리고 이전 시도 수를 이력과 감사에 남긴다")
  void resumesExhaustedDelivery() {
    UUID id = capture(0);
    DeliveryView dlt = operations.get(id).deliveries().getFirst();
    assertRejected(
        () -> operations.resume(OPERATOR, dlt.id(), "resume-key-0001", dlt.version(), "KAFKA_BACK"),
        Reason.DELIVERY_NOT_RESUMABLE);
    jdbc.update(
        "UPDATE payment.inbox_quarantine_deliveries SET status = 'REVIEW_REQUIRED', attempts = 10,"
            + " version = version + 1 WHERE id = ?",
        dlt.id());
    long exhaustedVersion = dlt.version() + 1;

    DeliveryView resumed =
        operations.resume(OPERATOR, dlt.id(), "resume-key-0002", exhaustedVersion, "KAFKA_BACK");

    assertThat(resumed.status()).isEqualTo("PENDING");
    assertThat(resumed.attempts()).isZero();
    assertThat(resumed.version()).isEqualTo(exhaustedVersion + 1);
    assertThat(resumed.resumes())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.previousAttempts()).isEqualTo(10);
              assertThat(r.expectedVersion()).isEqualTo(exhaustedVersion);
              assertThat(r.operatorId()).isEqualTo(OPERATOR);
              assertThat(r.reasonCode()).isEqualTo("KAFKA_BACK");
            });
    assertThat(
            operations.resume(
                OPERATOR, dlt.id(), "resume-key-0002", exhaustedVersion, "KAFKA_BACK"))
        .isEqualTo(resumed);
    assertThatThrownBy(
            () ->
                operations.resume(
                    OPERATOR, dlt.id(), "resume-key-0002", exhaustedVersion, "OTHER_REASON"))
        .isInstanceOf(IdempotencyConflictException.class);
    assertRejected(
        () ->
            operations.resume(
                OPERATOR, dlt.id(), "resume-key-0003", exhaustedVersion, "KAFKA_BACK"),
        Reason.VERSION_CONFLICT);
    assertThat(count("inbox_quarantine_resumes")).isEqualTo(1);
    assertThat(
            jdbc.queryForMap(
                "SELECT action, aggregate_id, before_status, after_status, correlation_id"
                    + " FROM payment.audit_logs"))
        .containsEntry("action", "QUARANTINE_DELIVERY_RESUMED")
        .containsEntry("aggregate_id", dlt.id())
        .containsEntry("before_status", "REVIEW_REQUIRED")
        .containsEntry("after_status", "PENDING")
        .containsEntry("correlation_id", id.toString());
  }

  @Test
  @DisplayName("같은 원문의 다른 REPLAY가 발행 중이면 소진 REPLAY 재개를 활성 REPLAY 충돌로 거절하고 이력·감사·멱등 기록을 남기지 않는다")
  void rejectsResumeWhileAnotherReplayIsActive() {
    UUID id = capture(0);
    operations.replay(OPERATOR, id, "replay-key-0101", 0, "SCHEMA_FIXED");
    UUID exhausted = replayIds(id).getFirst();
    jdbc.update(
        "UPDATE payment.inbox_quarantine_deliveries SET status = 'REVIEW_REQUIRED', attempts = 10,"
            + " version = version + 1 WHERE id = ?",
        exhausted);
    operations.replay(OPERATOR, id, "replay-key-0102", 1, "SCHEMA_FIXED_AGAIN");
    long exhaustedVersion =
        operations.get(id).deliveries().stream()
            .filter(d -> d.id().equals(exhausted))
            .findFirst()
            .orElseThrow()
            .version();
    long audits = count("audit_logs");
    long records = count("idempotency_records");

    assertRejected(
        () ->
            operations.resume(
                OPERATOR, exhausted, "resume-key-0101", exhaustedVersion, "KAFKA_BACK"),
        Reason.REPLAY_ACTIVE);

    assertThat(count("inbox_quarantine_resumes")).isZero();
    assertThat(count("audit_logs")).isEqualTo(audits);
    assertThat(count("idempotency_records")).isEqualTo(records);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM payment.inbox_quarantine_deliveries WHERE id = ?",
                String.class,
                exhausted))
        .isEqualTo("REVIEW_REQUIRED");

    // 다른 REPLAY의 발행이 끝나면 같은 키로 다시 요청해 재개할 수 있다.
    jdbc.update(
        "UPDATE payment.inbox_quarantine_deliveries SET status = 'PUBLISHED'"
            + " WHERE quarantine_id = ? AND kind = 'REPLAY' AND id <> ?",
        id,
        exhausted);
    DeliveryView resumed =
        operations.resume(OPERATOR, exhausted, "resume-key-0101", exhaustedVersion, "KAFKA_BACK");
    assertThat(resumed.status()).isEqualTo("PENDING");
    assertThat(count("inbox_quarantine_resumes")).isEqualTo(1);
  }

  @Test
  @DisplayName("목록 조회는 원문 수와 관계없이 같은 수의 SQL로 원문별 작업·재개 이력을 묶는다")
  void listsPageWithConstantQueries() {
    UUID first = capture(0);
    UUID exhausted = operations.get(first).deliveries().getFirst().id();
    jdbc.update(
        "UPDATE payment.inbox_quarantine_deliveries SET status = 'REVIEW_REQUIRED', attempts = 10,"
            + " version = version + 1 WHERE id = ?",
        exhausted);
    operations.resume(OPERATOR, exhausted, "resume-key-0201", 1, "KAFKA_BACK");
    operations.replay(OPERATOR, first, "replay-key-0201", 0, "SCHEMA_FIXED");
    int single = statementsFor(() -> operations.list(null, 0, 20));

    for (int offset = 1; offset <= 5; offset++) {
      UUID id = capture(offset);
      operations.replay(OPERATOR, id, "replay-key-020" + (offset + 1), 0, "SCHEMA_FIXED");
    }
    List<QuarantineView> page = new ArrayList<>();
    int many = statementsFor(() -> page.addAll(operations.list(null, 0, 20).items()));

    assertThat(many).isEqualTo(single).isLessThanOrEqualTo(4);
    assertThat(page).hasSize(6);
    assertThat(page)
        .allSatisfy(
            view -> {
              assertThat(view.deliveries())
                  .extracting(DeliveryView::kind)
                  .containsExactlyInAnyOrder("DLT", "REPLAY");
              assertThat(view.deliveries())
                  .allSatisfy(d -> assertThat(d.quarantineId()).isEqualTo(view.id()));
            });
    QuarantineView withResume =
        page.stream().filter(view -> view.id().equals(first)).findFirst().orElseThrow();
    assertThat(
            withResume.deliveries().stream()
                .filter(d -> d.id().equals(exhausted))
                .findFirst()
                .orElseThrow()
                .resumes())
        .singleElement()
        .satisfies(r -> assertThat(r.previousAttempts()).isEqualTo(10));
    assertThat(page.stream().filter(view -> !view.id().equals(first)))
        .allSatisfy(
            view ->
                assertThat(view.deliveries()).allSatisfy(d -> assertThat(d.resumes()).isEmpty()));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("재처리와 재개가 원문 잠금을 기다리면 먼저 커밋된 활성 REPLAY를 확인하고 중복 기록 없이 거절한다")
  void rejectsReplayAndResumeAfterWaitingForParentLock(boolean resumeFirst) throws Exception {
    UUID id = capture(0);
    operations.replay(OPERATOR, id, "initial-replay-key", 0, "SCHEMA_FIXED");
    UUID exhausted = replayIds(id).getFirst();
    jdbc.update(
        "UPDATE payment.inbox_quarantine_deliveries SET status = 'REVIEW_REQUIRED', attempts = 10,"
            + " version = version + 1 WHERE id = ?",
        exhausted);
    Runnable resume =
        () -> operations.resume(OPERATOR, exhausted, "concurrent-resume-key", 1, "KAFKA_BACK");
    Runnable replay =
        () -> operations.replay(OPERATOR, id, "concurrent-replay-key", 1, "SCHEMA_FIXED_AGAIN");
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch commit = new CountDownLatch(1);
    AtomicInteger waitingPid = new AtomicInteger();
    ExecutorService callers = Executors.newFixedThreadPool(2);

    try {
      Future<?> holder =
          callers.submit(
              () ->
                  new TransactionTemplate(manager)
                      .executeWithoutResult(
                          status -> {
                            (resumeFirst ? resume : replay).run();
                            held.countDown();
                            try {
                              if (!commit.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Commit gate timed out");
                              }
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(e);
                            }
                          }));
      assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
      Future<?> contender =
          callers.submit(
              () ->
                  assertRejected(
                      () ->
                          new TransactionTemplate(manager)
                              .executeWithoutResult(
                                  status -> {
                                    waitingPid.set(
                                        jdbc.queryForObject(
                                            "SELECT pg_backend_pid()", Integer.class));
                                    (resumeFirst ? replay : resume).run();
                                  }),
                      Reason.REPLAY_ACTIVE));
      await()
          .pollDelay(Duration.ZERO)
          .pollInterval(Duration.ofMillis(10))
          .atMost(Duration.ofMillis(800))
          .until(
              () ->
                  jdbc.queryForObject(
                          "SELECT count(*) FROM pg_stat_activity"
                              + " WHERE pid = ? AND wait_event_type = 'Lock'",
                          Integer.class,
                          waitingPid.get())
                      == 1);
      commit.countDown();
      holder.get(5, TimeUnit.SECONDS);
      contender.get(5, TimeUnit.SECONDS);
    } finally {
      commit.countDown();
      callers.shutdownNow();
      assertThat(callers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(replays(id)).isEqualTo(resumeFirst ? 1 : 2);
    assertThat(count("inbox_quarantine_resumes")).isEqualTo(resumeFirst ? 1 : 0);
    assertThat(count("audit_logs")).isEqualTo(2);
    assertThat(count("idempotency_records")).isEqualTo(2);
    assertThat(
            operations.get(id).deliveries().stream()
                .filter(d -> d.kind().equals("REPLAY") && d.status().equals("PENDING")))
        .hasSize(1);
  }

  @Test
  @DisplayName("원문 잠금 대기 상한을 넘으면 변경·감사·멱등 기록 없이 롤백하고 같은 키로 다시 요청할 수 있다")
  void rollsBackReplayWhenParentLockTimesOut() throws Exception {
    UUID id = capture(0);
    try (Connection lock = jdbc.getDataSource().getConnection()) {
      lock.setAutoCommit(false);
      try (var statement =
          lock.prepareStatement("SELECT id FROM payment.inbox_quarantines WHERE id=? FOR UPDATE")) {
        statement.setObject(1, id);
        statement.executeQuery().close();
      }

      long started = System.nanoTime();
      assertThatThrownBy(
              () -> operations.replay(OPERATOR, id, "locked-replay-key", 0, "SCHEMA_FIXED"))
          .isInstanceOf(DataAccessException.class)
          .rootCause()
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("55P03"));
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
      assertThat(operations.get(id).version()).isZero();
      assertThat(replays(id)).isZero();
      assertThat(count("inbox_quarantine_resumes")).isZero();
      assertThat(count("audit_logs")).isZero();
      assertThat(count("idempotency_records")).isZero();
      lock.rollback();
    }

    assertThat(operations.replay(OPERATOR, id, "locked-replay-key", 0, "SCHEMA_FIXED").version())
        .isEqualTo(1);
    assertThat(replays(id)).isEqualTo(1);
    assertThat(count("audit_logs")).isEqualTo(1);
    assertThat(count("idempotency_records")).isEqualTo(1);
  }

  @Test
  @DisplayName("서비스 메서드의 재처리는 멱등·감사 기록 없이 동작하고, 거절은 없는 원문·버전 불일치·진행 중 재처리로 구분한다")
  void serviceMethodsRejectWithReason() {
    UUID id = capture(0);
    QuarantineService service = new QuarantineService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    TransactionTemplate tx = new TransactionTemplate(manager);

    assertRejected(
        () -> tx.executeWithoutResult(s -> service.requestReplay(id, 1, "ops", "SCHEMA_FIXED")),
        Reason.VERSION_CONFLICT);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s -> service.requestReplay(UUID.randomUUID(), 0, "ops", "SCHEMA_FIXED")))
        .isInstanceOf(QuarantineNotFoundException.class);
    tx.executeWithoutResult(s -> service.requestReplay(id, 0, "ops", "SCHEMA_FIXED"));
    assertRejected(
        () -> tx.executeWithoutResult(s -> service.requestReplay(id, 1, "ops", "SCHEMA_FIXED")),
        Reason.REPLAY_ACTIVE);

    assertThat(replays(id)).isEqualTo(1);
    assertThat(count("idempotency_records")).isZero();
    assertThat(count("audit_logs")).isZero();
  }

  private UUID capture(int offset) {
    return new TransactionTemplate(manager)
        .execute(
            s ->
                store.capture(
                    new Source(
                        "payment.v1",
                        "commerce-settle.commerce.v1",
                        0,
                        offset,
                        "key-" + offset,
                        BODY),
                    "PAYMENT_EVENT_INVALID",
                    NOW));
  }

  private long replays(UUID id) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM payment.inbox_quarantine_deliveries"
            + " WHERE quarantine_id = ? AND kind = 'REPLAY'",
        Long.class,
        id);
  }

  private List<UUID> replayIds(UUID id) {
    return jdbc.queryForList(
        "SELECT id FROM payment.inbox_quarantine_deliveries"
            + " WHERE quarantine_id = ? AND kind = 'REPLAY' ORDER BY created_at, id",
        UUID.class,
        id);
  }

  private static int statementsFor(Runnable call) {
    int before = STATEMENTS.get();
    call.run();
    return STATEMENTS.get() - before;
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM payment." + table, Long.class);
  }

  private static void assertRejected(Runnable call, Reason reason) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            QuarantineOperationRejectedException.class,
            e -> assertThat(e.reason()).isEqualTo(reason));
  }

  @EnableTransactionManagement
  static class Config {
    /** 준비한 SQL 문장 수를 세도록 연결을 감싼다. */
    @Bean
    DataSource dataSource() {
      return new DelegatingDataSource(
          new DriverManagerDataSource(
              POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
        @Override
        public Connection getConnection() throws SQLException {
          Connection connection = super.getConnection();
          return (Connection)
              Proxy.newProxyInstance(
                  Connection.class.getClassLoader(),
                  new Class<?>[] {Connection.class},
                  (proxy, method, args) -> {
                    if (method.getName().startsWith("prepare")
                        || method.getName().equals("createStatement")) {
                      STATEMENTS.incrementAndGet();
                    }
                    try {
                      return method.invoke(connection, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                      throw e.getCause();
                    }
                  });
        }
      };
    }

    @Bean
    JdbcTemplate jdbc(DataSource source) {
      return new JdbcTemplate(source);
    }

    @Bean
    NamedParameterJdbcTemplate named(JdbcTemplate jdbc) {
      return new NamedParameterJdbcTemplate(jdbc);
    }

    @Bean
    PlatformTransactionManager manager(DataSource source) {
      return new DataSourceTransactionManager(source);
    }

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    QuarantineStore store(NamedParameterJdbcTemplate jdbc) {
      return new JdbcQuarantineStore(jdbc);
    }

    @Bean
    OperatorRequestStore requests(NamedParameterJdbcTemplate jdbc) {
      return new JdbcOperatorRequestStore(jdbc);
    }

    @Bean
    QuarantineOperationService operations(
        QuarantineStore store, OperatorRequestStore requests, Clock clock) {
      return new QuarantineOperationService(
          new QuarantineService(store, clock), store, requests, clock);
    }
  }
}
