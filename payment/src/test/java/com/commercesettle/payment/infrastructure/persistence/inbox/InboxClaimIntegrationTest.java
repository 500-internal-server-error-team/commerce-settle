package com.commercesettle.payment.infrastructure.persistence.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import com.commercesettle.payment.application.port.WaitingInboxEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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

@Testcontainers
@SpringJUnitConfig(InboxClaimIntegrationTest.Config.class)
@DirtiesContext
@Timeout(60)
class InboxClaimIntegrationTest {

  private static final Instant LATER = Instant.parse("2100-01-01T00:00:00Z");
  private static final Duration LEASE = Duration.ofSeconds(30);

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/payment.sql");

  @Autowired private InboxStore inbox;
  @Autowired private ObjectMapper mapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager manager;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM payment.inbox_conflicts");
    jdbc.update("DELETE FROM payment.inbox");
  }

  @Test
  @DisplayName("처리 시각이 된 WAITING을 원문 이벤트로 꺼내고 완료하면 다시 꺼내지 않는다")
  void claimsWaitingEventAndMarksApplied() {
    EventEnvelope<OrderPaymentRequestedPayload> saved = saveWaiting();
    UUID paymentId = UUID.randomUUID();

    WaitingInboxEvent claimed =
        inTransaction(
            () -> {
              WaitingInboxEvent event = claim(LATER).orElseThrow();
              inbox.markApplied(event.inboxId(), paymentId, LATER);
              return event;
            });

    assertThat(claimed.event()).isEqualTo(saved);
    assertThat(jdbc.queryForObject("SELECT status FROM payment.inbox", String.class))
        .isEqualTo("APPLIED");
    assertThat(jdbc.queryForObject("SELECT result_ref FROM payment.inbox", UUID.class))
        .isEqualTo(paymentId);
    assertThat(jdbc.queryForObject("SELECT next_run_at IS NULL FROM payment.inbox", Boolean.class))
        .isTrue();
    assertThat(inTransaction(() -> claim(LATER))).isEmpty();
  }

  @Test
  @DisplayName("업무상 거절은 결과 참조 없이 거절 사유와 함께 REVIEW_REQUIRED로 남긴다")
  void marksReviewRequiredWithoutResult() {
    saveWaiting();

    inTransaction(
        () -> {
          inbox.markReviewRequired(
              claim(LATER).orElseThrow().inboxId(), "PAYMENT_REQUEST_AMOUNT_MISMATCH", LATER);
          return null;
        });

    assertThat(
            jdbc.queryForMap(
                "SELECT status, last_error_code, attempts, result_ref FROM payment.inbox"))
        .containsEntry("status", "REVIEW_REQUIRED")
        .containsEntry("last_error_code", "PAYMENT_REQUEST_AMOUNT_MISMATCH")
        .containsEntry("attempts", 1)
        .containsEntry("result_ref", null);
  }

  @Test
  @DisplayName("선점이 커밋되면 시도 횟수를 늘리고 임대가 끝날 때까지 다른 처리기가 다시 잡지 못한다")
  void countsAttemptAndHidesRowUntilLeaseEnds() {
    saveWaiting();

    WaitingInboxEvent claimed = inTransaction(() -> claim(LATER)).orElseThrow();

    assertThat(claimed.attempts()).isEqualTo(1);
    assertThat(
            jdbc.queryForMap(
                "SELECT status, attempts, next_run_at = ? AS leased FROM payment.inbox",
                Timestamp.from(LATER.plus(LEASE))))
        .containsEntry("status", "WAITING")
        .containsEntry("attempts", 1)
        .containsEntry("leased", true);
    assertThat(inTransaction(() -> claim(LATER.plus(LEASE).minusMillis(1)))).isEmpty();
    assertThat(inTransaction(() -> inbox.lockClaimed(claimed.inboxId(), claimed.version())))
        .isTrue();
  }

  @Test
  @DisplayName("처리가 롤백된 뒤 실패를 기록하기 전에도 다른 처리기는 다시 잡지 못하고, 기록한 재시도 시각에 다시 잡는다")
  void keepsRowFromOtherProcessorBetweenRollbackAndFailureRecord() {
    saveWaiting();
    WaitingInboxEvent first = inTransaction(() -> claim(LATER)).orElseThrow();
    assertThatThrownBy(
            () ->
                inTransaction(
                    () -> {
                      inbox.lockClaimed(first.inboxId(), first.version());
                      throw new IllegalStateException("processing failed");
                    }))
        .hasMessage("processing failed");

    Optional<WaitingInboxEvent> beforeRecord = inTransaction(() -> claim(LATER));
    boolean recorded =
        inTransaction(
            () ->
                inbox.recordFailure(
                    first.inboxId(), first.version(), "FAILED", LATER.plusSeconds(1), LATER));

    assertThat(beforeRecord).isEmpty();
    assertThat(recorded).isTrue();
    assertThat(jdbc.queryForMap("SELECT status, attempts, last_error_code FROM payment.inbox"))
        .containsEntry("status", "WAITING")
        .containsEntry("attempts", 1)
        .containsEntry("last_error_code", "FAILED");
    assertThat(inTransaction(() -> claim(LATER))).isEmpty();
    assertThat(inTransaction(() -> claim(LATER.plusSeconds(1))))
        .get()
        .extracting(WaitingInboxEvent::attempts)
        .isEqualTo(2);
  }

  @Test
  @DisplayName("재시도 시각 없이 실패를 기록하면 REVIEW_REQUIRED로 남긴다")
  void recordsFailureWithoutRetryAsReview() {
    saveWaiting();
    WaitingInboxEvent claimed = inTransaction(() -> claim(LATER)).orElseThrow();

    inTransaction(
        () -> inbox.recordFailure(claimed.inboxId(), claimed.version(), "FAILED", null, LATER));

    assertThat(jdbc.queryForMap("SELECT status, next_run_at, attempts FROM payment.inbox"))
        .containsEntry("status", "REVIEW_REQUIRED")
        .containsEntry("next_run_at", null)
        .containsEntry("attempts", 1);
  }

  @Test
  @DisplayName("실패를 기록하지 못하고 중단돼도 시도는 횟수에 남고, 10번 시도한 행은 다시 처리하지 않는다")
  void exhaustsAttemptsEvenWhenFailuresAreNeverRecorded() {
    saveWaiting();

    for (int attempt = 1; attempt <= 10; attempt++) {
      Instant at = LATER.plus(LEASE.multipliedBy(attempt - 1));
      assertThat(inTransaction(() -> claim(at)))
          .get()
          .extracting(WaitingInboxEvent::attempts)
          .isEqualTo(attempt);
    }
    Optional<WaitingInboxEvent> eleventh =
        inTransaction(() -> claim(LATER.plus(LEASE.multipliedBy(10))));

    assertThat(eleventh).isEmpty();
    assertThat(jdbc.queryForMap("SELECT status, attempts, last_error_code FROM payment.inbox"))
        .containsEntry("status", "REVIEW_REQUIRED")
        .containsEntry("attempts", 10)
        .containsEntry("last_error_code", "PAYMENT_REQUEST_ATTEMPTS_EXHAUSTED");
  }

  @Test
  @DisplayName("임대가 끝나 다른 처리기가 선점한 행에는 이전 처리기의 결과·실패를 반영하지 않는다")
  void rejectsStaleClaimAfterLeaseExpires() {
    saveWaiting();
    WaitingInboxEvent stale = inTransaction(() -> claim(LATER)).orElseThrow();
    WaitingInboxEvent current = inTransaction(() -> claim(LATER.plus(LEASE))).orElseThrow();

    assertThat(inTransaction(() -> inbox.lockClaimed(stale.inboxId(), stale.version()))).isFalse();
    assertThat(
            inTransaction(
                () -> inbox.recordFailure(stale.inboxId(), stale.version(), "STALE", LATER, LATER)))
        .isFalse();
    assertThat(current.attempts()).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT last_error_code FROM payment.inbox", String.class))
        .isNull();
  }

  @Test
  @DisplayName("원문을 읽을 수 없는 행은 확인 대상으로 넘기고 다음 행을 선점한다")
  void marksUnreadableBodyAsReviewAndClaimsNext() {
    EventEnvelope<OrderPaymentRequestedPayload> readable = saveWaiting();
    UUID unreadableId = insertUnreadableWaiting();

    Optional<WaitingInboxEvent> claimed = inTransaction(() -> claim(LATER));

    assertThat(claimed).get().extracting(WaitingInboxEvent::event).isEqualTo(readable);
    assertThat(
            jdbc.queryForMap(
                "SELECT status, last_error_code FROM payment.inbox WHERE id = ?", unreadableId))
        .containsEntry("status", "REVIEW_REQUIRED")
        .containsEntry("last_error_code", "PAYMENT_REQUEST_BODY_UNREADABLE");
  }

  @Test
  @DisplayName("사유 없는 REVIEW_REQUIRED는 DB가 거절한다")
  void rejectsReviewRequiredWithoutErrorCode() {
    saveWaiting();

    assertThatThrownBy(
            () ->
                jdbc.update("UPDATE payment.inbox SET status='REVIEW_REQUIRED', next_run_at=NULL"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("처리 시각 전의 WAITING은 꺼내지 않는다")
  void skipsWaitingBeforeNextRunAt() {
    saveWaiting();

    assertThat(inTransaction(() -> claim(Instant.parse("2000-01-01T00:00:00Z")))).isEmpty();
  }

  @Test
  @DisplayName("다른 처리기가 잠근 WAITING은 건너뛰어 같은 이벤트를 동시에 처리하지 않는다")
  void skipsEventLockedByAnotherProcessor() throws Exception {
    saveWaiting();
    var claimed = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    try {
      Future<Optional<WaitingInboxEvent>> first =
          executor.submit(
              () ->
                  inTransaction(
                      () -> {
                        Optional<WaitingInboxEvent> event = claim(LATER);
                        claimed.countDown();
                        await(release);
                        return event;
                      }));
      assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();

      Optional<WaitingInboxEvent> second = inTransaction(() -> claim(LATER));
      release.countDown();

      assertThat(first.get(10, TimeUnit.SECONDS)).isPresent();
      assertThat(second).isEmpty();
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("이미 처리한 Inbox를 다시 완료하려 하면 거절한다")
  void rejectsFinishingInboxThatIsNotWaiting() {
    saveWaiting();
    UUID inboxId =
        inTransaction(
            () -> {
              UUID id = claim(LATER).orElseThrow().inboxId();
              inbox.markApplied(id, UUID.randomUUID(), LATER);
              return id;
            });

    assertThatThrownBy(
            () ->
                inTransaction(
                    () -> {
                      inbox.markReviewRequired(inboxId, "FAILED", LATER);
                      return null;
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("SELECT status FROM payment.inbox", String.class))
        .isEqualTo("APPLIED");
  }

  @Test
  @DisplayName("결제를 처음 APPLIED로 만든 요청을 원문에서 다시 읽는다")
  void findsOriginalRequestOfPayment() {
    EventEnvelope<OrderPaymentRequestedPayload> saved = saveWaiting();
    UUID paymentId = UUID.randomUUID();
    inTransaction(
        () -> {
          inbox.markApplied(claim(LATER).orElseThrow().inboxId(), paymentId, LATER);
          return null;
        });

    assertThat(inTransaction(() -> inbox.findOriginalRequest(paymentId))).contains(saved.payload());
    assertThat(inTransaction(() -> inbox.findOriginalRequest(UUID.randomUUID()))).isEmpty();
  }

  private EventEnvelope<OrderPaymentRequestedPayload> saveWaiting() {
    byte[] body = InboxIntegrationTest.EVENT_JSON.getBytes(StandardCharsets.UTF_8);
    try {
      EventEnvelope<OrderPaymentRequestedPayload> event =
          mapper.readValue(body, new TypeReference<>() {});
      inTransaction(
          () -> {
            inbox.saveWaiting(event, new Source("commerce-settle.commerce.v1", 0, 0L, body));
            return null;
          });
      return event;
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private Optional<WaitingInboxEvent> claim(Instant now) {
    return inbox.claimNextWaiting(now, now.plus(LEASE), 10);
  }

  /** 저장 때 검증을 거치지 않은 행. 결제 요청으로 읽을 수 없는 상태 값을 넣고 다른 대기 행보다 먼저 처리되게 한다. */
  private UUID insertUnreadableWaiting() {
    UUID id = UUID.randomUUID();
    String body =
        InboxIntegrationTest.EVENT_JSON
            .replace("00000000-0000-0000-0000-000000000006", id.toString())
            .replace("PAYMENT_PENDING", "UNKNOWN_STATUS");
    jdbc.update(
        """
        INSERT INTO payment.inbox (id,consumer,event_id,event_type,event_version,occurred_at,payload,
            raw_body,body_hash,source_topic,source_partition,source_offset,aggregate_id,
            aggregate_version,status,next_run_at)
        VALUES (?,'payment.v1',?,'OrderPaymentRequested',1,'2026-01-01T00:00:00Z',CAST(? AS jsonb),
            ?,repeat('0',64),'commerce-settle.commerce.v1',0,1,
            '00000000-0000-0000-0000-000000000001',0,'WAITING','2000-01-01T00:00:00Z')
        """,
        id,
        id,
        body,
        body.getBytes(StandardCharsets.UTF_8));
    return id;
  }

  private <T> T inTransaction(Supplier<T> work) {
    return new TransactionTemplate(manager).execute(status -> work.get());
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  @Configuration
  @EnableTransactionManagement
  @Import(JdbcInboxStore.class)
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
    ObjectMapper mapper() {
      return new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
  }
}
