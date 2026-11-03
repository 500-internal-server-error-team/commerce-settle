package com.commercesettle.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
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
    @Bean
    DataSource dataSource() {
      return new DriverManagerDataSource(
          POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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
