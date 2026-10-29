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
              WaitingInboxEvent event = inbox.claimNextWaiting(LATER).orElseThrow();
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
    assertThat(inTransaction(() -> inbox.claimNextWaiting(LATER))).isEmpty();
  }

  @Test
  @DisplayName("업무상 거절은 결과 참조 없이 REVIEW_REQUIRED로 남긴다")
  void marksReviewRequiredWithoutResult() {
    saveWaiting();

    inTransaction(
        () -> {
          inbox.markReviewRequired(inbox.claimNextWaiting(LATER).orElseThrow().inboxId(), LATER);
          return null;
        });

    assertThat(jdbc.queryForObject("SELECT status FROM payment.inbox", String.class))
        .isEqualTo("REVIEW_REQUIRED");
    assertThat(jdbc.queryForObject("SELECT result_ref IS NULL FROM payment.inbox", Boolean.class))
        .isTrue();
  }

  @Test
  @DisplayName("처리 시각 전의 WAITING은 꺼내지 않는다")
  void skipsWaitingBeforeNextRunAt() {
    saveWaiting();

    assertThat(inTransaction(() -> inbox.claimNextWaiting(Instant.parse("2000-01-01T00:00:00Z"))))
        .isEmpty();
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
                        Optional<WaitingInboxEvent> event = inbox.claimNextWaiting(LATER);
                        claimed.countDown();
                        await(release);
                        return event;
                      }));
      assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();

      Optional<WaitingInboxEvent> second = inTransaction(() -> inbox.claimNextWaiting(LATER));
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
              UUID id = inbox.claimNextWaiting(LATER).orElseThrow().inboxId();
              inbox.markApplied(id, UUID.randomUUID(), LATER);
              return id;
            });

    assertThatThrownBy(
            () ->
                inTransaction(
                    () -> {
                      inbox.markReviewRequired(inboxId, LATER);
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
          inbox.markApplied(
              inbox.claimNextWaiting(LATER).orElseThrow().inboxId(), paymentId, LATER);
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
