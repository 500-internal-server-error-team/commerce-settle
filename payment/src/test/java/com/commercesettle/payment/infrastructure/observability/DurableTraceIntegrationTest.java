package com.commercesettle.payment.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercesettle.observability.AsyncObservation;
import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.event.PaymentAcceptedPayload;
import com.commercesettle.payment.application.port.InboxClaim;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor;
import com.commercesettle.payment.application.port.OutboxStore;
import com.commercesettle.payment.application.port.PaymentEventOutbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DurableTraceIntegrationTest {
  private static final String PARENT = "00-11111111111111111111111111111111-2222222222222222-01";
  private static final String OTHER = "00-33333333333333333333333333333333-4444444444444444-01";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/payment.sql");

  @Test
  void preservesInboxParentAndOriginalAcrossDuplicateAndAdapterRestart() throws Exception {
    UUID eventId;
    byte[] original;
    try (var context = new AnnotationConfigApplicationContext(Config.class)) {
      var inbox = context.getBean(InboxStore.class);
      var mapper = context.getBean(ObjectMapper.class);
      var observations = context.getBean(AsyncObservation.class);
      var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
      UUID order = UUID.randomUUID();
      var payload =
          new OrderPaymentRequestedPayload(
              order,
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              0,
              OrderPaymentRequestedPayload.Status.PAYMENT_PENDING,
              "v1",
              OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30),
              OrderPaymentRequestedPayload.ExternalMethod.CREDIT_CARD,
              "test-account",
              1000,
              0,
              1000,
              0,
              1000,
              0,
              0,
              1000);
      var event =
          new EventEnvelope<>(
              UUID.randomUUID(),
              "OrderPaymentRequested",
              1,
              OffsetDateTime.now(ZoneOffset.UTC),
              order,
              0,
              payload);
      byte[] body = mapper.writeValueAsBytes(event);
      original = body;
      eventId = event.eventId();
      when(observations.capture()).thenReturn(PARENT);
      transaction.executeWithoutResult(
          status -> {
            inbox.saveWaiting(
                event,
                new OrderPaymentRequestAcceptor.Source("commerce-settle.commerce.v1", 0, 1, body));
          });
      when(observations.capture()).thenReturn(OTHER);
      transaction.executeWithoutResult(
          status -> {
            inbox.saveWaiting(
                event,
                new OrderPaymentRequestAcceptor.Source("commerce-settle.commerce.v1", 0, 1, body));
          });
      var jdbc = context.getBean(JdbcTemplate.class);
      assertThat(
              jdbc.queryForObject(
                  "SELECT trace_parent FROM payment.inbox WHERE event_id=?", String.class, eventId))
          .isEqualTo(PARENT);
      assertThat(
              jdbc.queryForObject(
                  "SELECT raw_body FROM payment.inbox WHERE event_id=?", byte[].class, eventId))
          .containsExactly(body);
    }
    try (var restarted = new AnnotationConfigApplicationContext(Config.class)) {
      var inbox = restarted.getBean(InboxStore.class);
      var jdbc = restarted.getBean(JdbcTemplate.class);
      var transaction =
          new TransactionTemplate(restarted.getBean(PlatformTransactionManager.class));
      transaction.executeWithoutResult(
          status -> {
            var selected =
                (InboxClaim.Claimed)
                    inbox
                        .claimWaitingEvent(
                            eventId,
                            Instant.now().plusSeconds(1),
                            Instant.now().plusSeconds(31),
                            10)
                        .orElseThrow();
            assertThat(selected.event().traceParent()).isEqualTo(PARENT);
            assertThat(selected.event().event().eventId()).isEqualTo(eventId);
          });
      assertThat(
              jdbc.queryForObject(
                  "SELECT raw_body FROM payment.inbox WHERE event_id=?", byte[].class, eventId))
          .containsExactly(original);
    }
  }

  @Test
  void differentiatesRetryBackoffLeaseRecoveryAndLegacyUnknownWithoutChangingEligibility()
      throws Exception {
    try (var context = new AnnotationConfigApplicationContext(Config.class)) {
      var inbox = context.getBean(InboxStore.class);
      var jdbc = context.getBean(JdbcTemplate.class);
      var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
      var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
      UUID eventId = UUID.randomUUID();
      var event =
          new EventEnvelope<>(
              eventId,
              "PaymentCancellationRequested",
              1,
              now.atOffset(ZoneOffset.UTC),
              UUID.randomUUID(),
              0,
              new com.commercesettle.payment.application.event.PaymentCancellationRequestedPayload(
                  UUID.randomUUID(), 0, UUID.randomUUID(), UUID.randomUUID()));
      byte[] body = context.getBean(ObjectMapper.class).writeValueAsBytes(event);
      tx.executeWithoutResult(
          status ->
              inbox.saveWaiting(
                  event,
                  new OrderPaymentRequestAcceptor.Source(
                      "commerce-settle.commerce.v1", 0, 77, body)));
      jdbc.update(
          "UPDATE payment.inbox SET next_run_at=? WHERE event_id=?",
          java.sql.Timestamp.from(now),
          eventId);
      var first =
          tx.execute(
                  status ->
                      (InboxClaim.Claimed)
                          inbox
                              .claimWaitingEvent(
                                  eventId, now.plusSeconds(1), now.plusSeconds(31), 10)
                              .orElseThrow())
              .event();
      assertThat(first.timing().attempts()).isEqualTo(1);
      assertThat(first.timing().previousStatus()).isNull();
      assertThat(
              tx.<java.util.Optional<InboxClaim>>execute(
                  status ->
                      inbox.claimWaitingEvent(
                          eventId, now.plusSeconds(2), now.plusSeconds(32), 10)))
          .isEmpty();
      // Reclaim a committed lease whose worker died before result recording.
      var recovery =
          tx.execute(
                  status ->
                      (InboxClaim.Claimed)
                          inbox
                              .claimWaitingEvent(
                                  eventId, now.plusSeconds(32), now.plusSeconds(62), 10)
                              .orElseThrow())
              .event();
      assertThat(recovery.timing().previousStatus()).isEqualTo("CLAIMED");
      assertThat(recovery.timing().eligibleAt()).isEqualTo(now.plusSeconds(31));
      assertThat(
              tx.<Boolean>execute(
                  status ->
                      inbox.recordFailure(
                          recovery.inboxId(),
                          recovery.version(),
                          "TEMPORARY",
                          now.plusSeconds(50),
                          now.plusSeconds(33))))
          .isTrue();
      var retry =
          tx.execute(
                  status ->
                      (InboxClaim.Claimed)
                          inbox
                              .claimWaitingEvent(
                                  eventId, now.plusSeconds(51), now.plusSeconds(81), 10)
                              .orElseThrow())
              .event();
      assertThat(retry.timing().previousStatus()).isEqualTo("RETRY");
      assertThat(retry.timing().changedAt()).isEqualTo(now.plusSeconds(33));
      assertThat(retry.timing().eligibleAt()).isEqualTo(now.plusSeconds(50));
      // Pre-upgrade attempts have no classification. Keep it unknown rather than claiming a
      // backoff.
      jdbc.update("UPDATE payment.inbox SET observation_state=NULL WHERE event_id=?", eventId);
      var legacy =
          tx.execute(
                  status ->
                      (InboxClaim.Claimed)
                          inbox
                              .claimWaitingEvent(
                                  eventId, now.plusSeconds(82), now.plusSeconds(112), 10)
                              .orElseThrow())
              .event();
      assertThat(legacy.timing().previousStatus()).isNull();
      assertThat(legacy.timing().attempts()).isEqualTo(4);
    }
  }

  @Test
  void retainsOutboxParentAndBytesAcrossDuplicateAndAdapterRestart() throws Exception {
    UUID eventId;
    byte[] original;
    try (var context = new AnnotationConfigApplicationContext(Config.class)) {
      var outbox = context.getBean(PaymentEventOutbox.class);
      var observations = context.getBean(AsyncObservation.class);
      var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
      UUID key = UUID.randomUUID();
      var event =
          new EventEnvelope<>(
              UUID.randomUUID(),
              "PaymentAccepted",
              1,
              OffsetDateTime.now(ZoneOffset.UTC),
              key,
              0,
              new PaymentAcceptedPayload(key, UUID.randomUUID(), 0, UUID.randomUUID()));
      eventId = event.eventId();
      original = context.getBean(ObjectMapper.class).writeValueAsBytes(event);
      when(observations.capture()).thenReturn(PARENT);
      transaction.executeWithoutResult(
          status -> {
            outbox.appendPaymentAccepted(event);
          });
      when(observations.capture()).thenReturn(OTHER);
      transaction.executeWithoutResult(
          status -> {
            outbox.appendPaymentAccepted(event);
          });
    }
    try (var restarted = new AnnotationConfigApplicationContext(Config.class)) {
      var transaction =
          new TransactionTemplate(restarted.getBean(PlatformTransactionManager.class));
      transaction.executeWithoutResult(
          status -> {
            var store = restarted.getBean(OutboxStore.class);
            var row =
                store.claim(10, 240000, 10).stream()
                    .filter(d -> d.id().equals(eventId))
                    .findFirst()
                    .orElseThrow();
            assertThat(row.traceParent()).isEqualTo(PARENT);
            assertThat(row.body()).containsExactly(original);
            assertThat(row.timing().attempts()).isEqualTo(1);
            assertThat(row.timing().previousStatus()).isEqualTo("PENDING");
            assertThat(
                    store.markPublished(
                        List.of(
                            new OutboxStore.Published(
                                row, new OutboxStore.Publication(row.topic(), 0, 42)))))
                .containsExactly(eventId);
          });
      verifiesLegacyUpgrade(restarted.getBean(JdbcTemplate.class));
    }
  }

  private void verifiesLegacyUpgrade(JdbcTemplate jdbc) throws Exception {
    var tables = List.of("outbox", "inbox", "payment_jobs");
    var snapshots = new LinkedHashMap<String, String>();
    for (String table : tables) {
      snapshots.put(table, snapshot(jdbc, table));
      jdbc.execute("ALTER TABLE payment." + table + " DROP COLUMN trace_parent");
    }
    jdbc.execute("ALTER TABLE payment.inbox DROP COLUMN observation_state");
    String upgrade =
        new ClassPathResource("db/payment-tracing.sql").getContentAsString(StandardCharsets.UTF_8);
    for (int pass = 0; pass < 2; pass++) {
      jdbc.execute(
          (ConnectionCallback<Void>)
              connection -> {
                try (var statement = connection.createStatement()) {
                  statement.execute(upgrade);
                }
                return null;
              });
      for (String table : tables) {
        assertThat(snapshot(jdbc, table)).isEqualTo(snapshots.get(table));
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM payment." + table + " WHERE trace_parent IS NOT NULL",
                    Long.class))
            .isZero();
      }
    }
  }

  private String snapshot(JdbcTemplate jdbc, String table) {
    return jdbc.queryForObject(
        "SELECT coalesce(jsonb_agg(to_jsonb(r) - 'trace_parent' - 'observation_state' ORDER BY id), '[]'::jsonb)::text FROM payment."
            + table
            + " r",
        String.class);
  }

  @Configuration
  @EnableTransactionManagement
  @ComponentScan(
      basePackages = "com.commercesettle.payment.infrastructure.persistence",
      useDefaultFilters = false,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = {InboxStore.class, PaymentEventOutbox.class, OutboxStore.class}))
  static class Config {
    @Bean
    DataSource dataSource() {
      return new DriverManagerDataSource(
          POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Bean
    NamedParameterJdbcTemplate namedJdbc(DataSource source) {
      return new NamedParameterJdbcTemplate(source);
    }

    @Bean
    JdbcTemplate jdbc(DataSource source) {
      return new JdbcTemplate(source);
    }

    @Bean
    PlatformTransactionManager transactions(DataSource source) {
      return new DataSourceTransactionManager(source);
    }

    @Bean
    ObjectMapper mapper() {
      return new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    AsyncObservation observations() {
      return mock(AsyncObservation.class);
    }
  }
}
