package com.commercesettle.settlement.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercesettle.observability.AsyncObservation;
import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.event.PurchaseConfirmedPayload;
import com.commercesettle.settlement.application.event.RefundReservationResultPayload;
import com.commercesettle.settlement.application.port.InboxStore;
import com.commercesettle.settlement.application.port.OutboxStore;
import com.commercesettle.settlement.application.port.SettlementEventOutbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
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
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

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
      var now = OffsetDateTime.now(ZoneOffset.UTC);
      var event =
          new EventEnvelope<>(
              UUID.randomUUID(),
              "PurchaseConfirmed",
              1,
              now,
              order,
              0,
              new PurchaseConfirmedPayload(
                  UUID.randomUUID(), order, 0, now, now.minusDays(8), now.minusDays(1)));
      byte[] body = mapper.writeValueAsBytes(event);
      original = body;
      eventId = event.eventId();
      when(observations.capture()).thenReturn(PARENT);
      transaction.executeWithoutResult(
          status -> {
            inbox.receive(
                event,
                new InboxStore.Source(
                    "commerce-settle.commerce.v1",
                    0,
                    1,
                    body,
                    Instant.parse("2026-11-01T00:00:00Z")));
          });
      when(observations.capture()).thenReturn(OTHER);
      transaction.executeWithoutResult(
          status -> {
            inbox.receive(event, new InboxStore.Source("commerce-settle.commerce.v1", 0, 1, body));
          });
      var jdbc = context.getBean(JdbcTemplate.class);
      assertThat(
              jdbc.queryForObject(
                      "SELECT order_accepted_at FROM settlement.inbox WHERE event_id=?",
                      Timestamp.class,
                      eventId)
                  .toInstant())
          .isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
      assertThat(
              jdbc.queryForObject(
                  "SELECT trace_parent FROM settlement.inbox WHERE event_id=?",
                  String.class,
                  eventId))
          .isEqualTo(PARENT);
      assertThat(
              jdbc.queryForObject(
                  "SELECT raw_body FROM settlement.inbox WHERE event_id=?", byte[].class, eventId))
          .containsExactly(body);
    }
    try (var restarted = new AnnotationConfigApplicationContext(Config.class)) {
      var inbox = restarted.getBean(InboxStore.class);
      var jdbc = restarted.getBean(JdbcTemplate.class);
      assertThat(
              jdbc.queryForObject(
                      "SELECT order_accepted_at FROM settlement.inbox WHERE event_id=?",
                      Timestamp.class,
                      eventId)
                  .toInstant())
          .isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
      var transaction =
          new TransactionTemplate(restarted.getBean(PlatformTransactionManager.class));
      transaction.executeWithoutResult(
          status -> {
            jdbc.update(
                "UPDATE settlement.inbox SET status='WAITING',next_run_at=clock_timestamp()-interval '1 second' WHERE event_id=?",
                eventId);
            var selected = inbox.claimNextWaiting().orElseThrow();
            assertThat(selected.traceParent()).isEqualTo(PARENT);
          });
      assertThat(
              jdbc.queryForObject(
                  "SELECT raw_body FROM settlement.inbox WHERE event_id=?", byte[].class, eventId))
          .containsExactly(original);
    }
  }

  @Test
  void retainsOutboxParentAndBytesAcrossDuplicateAndAdapterRestart() throws Exception {
    UUID eventId;
    byte[] original;
    try (var context = new AnnotationConfigApplicationContext(Config.class)) {
      var outbox = context.getBean(SettlementEventOutbox.class);
      var observations = context.getBean(AsyncObservation.class);
      var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
      UUID key = UUID.randomUUID();
      var event =
          new EventEnvelope<>(
              UUID.randomUUID(),
              "RefundReservationResult",
              1,
              OffsetDateTime.now(ZoneOffset.UTC),
              key,
              0,
              new RefundReservationResultPayload(
                  key,
                  UUID.randomUUID(),
                  RefundReservationResultPayload.Result.WAITING,
                  null,
                  null));
      eventId = event.eventId();
      original = context.getBean(ObjectMapper.class).writeValueAsBytes(event);
      when(observations.capture()).thenReturn(PARENT);
      transaction.executeWithoutResult(
          status -> {
            outbox.append(event, key);
          });
      when(observations.capture()).thenReturn(OTHER);
      transaction.executeWithoutResult(
          status -> {
            outbox.append(event, key);
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
            assertThat(store.markPublished(row, new OutboxStore.Publication(row.topic(), 0, 42)))
                .isEqualTo(OutboxStore.PublishResult.PUBLISHED);
          });
      verifiesLegacyUpgrade(restarted.getBean(JdbcTemplate.class));
    }
  }

  private void verifiesLegacyUpgrade(JdbcTemplate jdbc) throws Exception {
    var tables = List.of("outbox", "inbox", "payout_jobs");
    var snapshots = new LinkedHashMap<String, String>();
    for (String table : tables) {
      snapshots.put(table, snapshot(jdbc, table));
      jdbc.execute("ALTER TABLE settlement." + table + " DROP COLUMN trace_parent");
    }
    String upgrade =
        new ClassPathResource("db/settlement-tracing.sql")
            .getContentAsString(StandardCharsets.UTF_8);
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
                    "SELECT count(*) FROM settlement." + table + " WHERE trace_parent IS NOT NULL",
                    Long.class))
            .isZero();
      }
    }
  }

  private String snapshot(JdbcTemplate jdbc, String table) {
    return jdbc.queryForObject(
        "SELECT coalesce(jsonb_agg(to_jsonb(r) - 'trace_parent' - 'observation_state' ORDER BY id), '[]'::jsonb)::text FROM settlement."
            + table
            + " r",
        String.class);
  }

  @Configuration
  @EnableTransactionManagement
  @ComponentScan(
      basePackages = "com.commercesettle.settlement.infrastructure.persistence",
      useDefaultFilters = false,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = {InboxStore.class, SettlementEventOutbox.class, OutboxStore.class}))
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
