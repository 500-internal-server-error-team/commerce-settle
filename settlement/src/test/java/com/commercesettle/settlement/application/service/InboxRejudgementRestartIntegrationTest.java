package com.commercesettle.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.commercesettle.settlement.SettlementApplication;
import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.event.PaymentConfirmedPayload;
import com.commercesettle.settlement.application.event.RefundFinancialCompletedPayload;
import com.commercesettle.settlement.application.event.RefundReservationRequestedPayload;
import com.commercesettle.settlement.application.port.InboxOperationStore.ExpectedTarget;
import com.commercesettle.settlement.application.port.InboxOperationStore.Filter;
import com.commercesettle.settlement.support.SettlementEventTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** 같은 전용 DB/Kafka를 유지하며 실제 앱을 닫고 다시 기동한다. */
@Testcontainers
class InboxRejudgementRestartIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.2");
  private final UUID payment = UUID.randomUUID();
  private final UUID refund = UUID.randomUUID();
  private final OffsetDateTime time = OffsetDateTime.parse("2026-11-03T12:00:00+09:00");

  @Test
  @DisplayName("앱 재기동은 고정 대상을 유지하고 만료 임대를 회수해 원문·원장을 한 번만 반영한다")
  void restartsSameFixedRunAfterAbandonedLease() throws Exception {
    UUID run;
    UUID inboxId;
    String original;
    UUID target;
    try (var first = start(false)) {
      var mapper = first.getBean(ObjectMapper.class);
      var jdbc = first.getBean(JdbcTemplate.class);
      var ops = first.getBean(InboxOperationService.class);
      var paid =
          envelope(
              "PaymentConfirmed",
              new PaymentConfirmedPayload(
                  10000,
                  0,
                  10000,
                  0,
                  10000,
                  payment,
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  1,
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  1500,
                  8500,
                  time,
                  "delivery-daily-v1",
                  0,
                  0,
                  10000));
      send(first, paid, mapper);
      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(
              () ->
                  assertThat(
                          jdbc.queryForObject(
                              "SELECT count(*) FROM settlement.settlement_items", Long.class))
                      .isEqualTo(1));
      var completed =
          envelope(
              "RefundFinancialCompleted",
              new RefundFinancialCompletedPayload(refund, payment, 10000, 0, time));
      send(first, completed, mapper);
      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(
              () ->
                  assertThat(
                          jdbc.queryForObject(
                              "SELECT count(*) FROM settlement.inbox WHERE last_error_code='REFUND_NOT_RESERVED'",
                              Long.class))
                      .isEqualTo(1));
      inboxId =
          jdbc.queryForObject(
              "SELECT id FROM settlement.inbox WHERE event_id=?", UUID.class, completed.eventId());
      original =
          jdbc.queryForObject(
              "SELECT encode(raw_body,'hex') FROM settlement.inbox WHERE id=?",
              String.class,
              inboxId);
      send(
          first,
          envelope(
              "RefundReservationRequested",
              new RefundReservationRequestedPayload(refund, payment, 8500)),
          mapper);
      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(
              () ->
                  assertThat(
                          jdbc.queryForObject(
                              "SELECT count(*) FROM settlement.settlement_reservations",
                              Long.class))
                      .isEqualTo(1));
      run =
          ops.create(
                  "operator:ops",
                  "restart-fixed-run",
                  new Filter(
                      "REFUND_NOT_RESERVED", null, Instant.parse("2099-01-01T00:00:00Z"), inboxId),
                  1,
                  List.of(new ExpectedTarget(inboxId, ops.get(inboxId).version())),
                  "PREDECESSOR_VERIFIED")
              .id();
      target = ops.claim(Duration.ofSeconds(2)).orElseThrow().targetId();
      assertThat(ops.targets(run, 0, 100).items().getFirst().status()).isEqualTo("RUNNING");
    }
    var jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    try (var second = start(true)) {
      await()
          .atMost(Duration.ofSeconds(40))
          .untilAsserted(
              () ->
                  assertThat(
                          jdbc.queryForObject(
                              "SELECT status FROM settlement.inbox_rejudgement_runs WHERE id=?",
                              String.class,
                              run))
                      .isEqualTo("COMPLETED"));
      var ops = second.getBean(InboxOperationService.class);
      assertThat(ops.targets(run, 0, 100).items()).hasSize(1);
      var result = ops.targets(run, 0, 100).items().getFirst();
      assertThat(result.id()).isEqualTo(target);
      assertThat(result.inboxId()).isEqualTo(inboxId);
      assertThat(result.outcome()).isEqualTo("APPLIED");
      assertThat(result.attempts()).isEqualTo(2);
      assertThat(ops.attempts(run, 0, 100).items())
          .extracting(a -> a.outcome())
          .containsExactly("LEASE_EXPIRED", "APPLIED");
      assertThat(
              jdbc.queryForObject(
                  "SELECT encode(raw_body,'hex') FROM settlement.inbox WHERE id=?",
                  String.class,
                  inboxId))
          .isEqualTo(original);
      assertThat(
              jdbc.queryForObject("SELECT count(*) FROM settlement.settlement_entries", Long.class))
          .isEqualTo(10);
      assertThat(
              jdbc.queryForObject(
                  "SELECT sum(amount) FROM settlement.settlement_entries", Long.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM settlement.settlement_reservations", String.class))
          .isEqualTo("CONSUMED");
      assertThat(ops.claim(Duration.ofSeconds(30))).isEmpty();
    }
  }

  private ConfigurableApplicationContext start(boolean worker) {
    return new SpringApplicationBuilder(SettlementApplication.class, SettlementEventTopics.class)
        .run(
            "--server.port=0",
            "--management.server.port=0",
            "--management.tracing.enabled=false",
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
            "--spring.kafka.consumer.auto-offset-reset=earliest",
            "--app.kafka.settlement-events.enabled=true",
            "--app.outbox.enabled=false",
            "--app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123",
            "--app.internal-auth.senders[operator:ops].current=test-operator-credential-0123456789abcdef0123",
            "--app.internal-auth.senders[service:commerce].current=test-commerce-retention-credential-0123456789abcdef",
            "--settlement.payout.mock-credential=test-settlement-credential-0123456789abcdef0123",
            "--settlement.daily-batch.schedule-enabled=false",
            "--settlement.payout-worker.enabled=false",
            "--settlement.inbox-processor.enabled=false",
            "--settlement.inbox-rejudgement.enabled=" + worker);
  }

  @SuppressWarnings("unchecked")
  private void send(
      ConfigurableApplicationContext context, EventEnvelope<?> event, ObjectMapper mapper)
      throws Exception {
    KafkaTemplate<String, byte[]> template =
        (KafkaTemplate<String, byte[]>) context.getBean(KafkaTemplate.class);
    template
        .send("commerce-settle.payment.v1", payment.toString(), mapper.writeValueAsBytes(event))
        .get(20, TimeUnit.SECONDS);
  }

  private <T> EventEnvelope<T> envelope(String type, T payload) {
    return new EventEnvelope<>(UUID.randomUUID(), type, 1, time, payment, 1, payload);
  }
}
