package com.commercesettle.payment.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.observability.infrastructure.BusinessMetrics;
import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor;
import com.commercesettle.payment.application.port.OutboxStore.Delivery;
import com.commercesettle.payment.application.service.OutboxDeliveryService;
import com.commercesettle.payment.infrastructure.config.BusinessMetricsConfiguration;
import com.commercesettle.payment.infrastructure.messaging.OrderPaymentRequestedListener;
import com.commercesettle.payment.infrastructure.messaging.OutboxRelay;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class BusinessMetricsIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/payment.sql");

  private final DriverManagerDataSource source =
      new DriverManagerDataSource(
          POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  private final JdbcTemplate jdbc = new JdbcTemplate(source);

  @BeforeEach
  void clear() {
    jdbc.execute("TRUNCATE payment.payments,payment.refunds,payment.refund_sagas");
  }

  @Test
  void countsOnlyCommittedConfirmationAndPreservesFactAfterLaterCancellationAndRestart() {
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    try (var metrics = metrics()) {
      transaction.executeWithoutResult(
          status -> {
            payment("CONFIRMED", 5);
            metrics.refresh();
            assertThat(value(metrics, "completions", "payment.confirmed", 60)).isZero();
          });
      metrics.refresh();
      assertThat(value(metrics, "completions", "payment.confirmed", 60)).isEqualTo(1);
      transaction.executeWithoutResult(
          status -> {
            payment("CONFIRMED", 5);
            status.setRollbackOnly();
          });
      payment("PENDING", 5);
      jdbc.update("UPDATE payment.payments SET status='CANCELLED' WHERE confirmed_at IS NOT NULL");
      metrics.refresh();
      assertThat(value(metrics, "completions", "payment.confirmed", 60)).isEqualTo(1);
    }
    try (var restarted = metrics()) {
      restarted.refresh();
      assertThat(value(restarted, "completions", "payment.confirmed", 60)).isEqualTo(1);
    }
  }

  @Test
  void windowsAndInvalidDurationDoNotTurnNoSamplesIntoZeroLatency() {
    payment("CONFIRMED", 65);
    payment("CONFIRMED", 905);
    payment("CONFIRMED", 5);
    jdbc.update(
        "UPDATE payment.payments SET created_at=confirmed_at+interval '1 second' "
            + "WHERE confirmed_at > statement_timestamp()-interval '60 seconds'");
    try (var metrics = metrics()) {
      metrics.refresh();
      assertThat(value(metrics, "completions", "payment.confirmed", 60)).isEqualTo(1);
      assertThat(value(metrics, "completions", "payment.confirmed", 300)).isEqualTo(2);
      assertThat(value(metrics, "completions", "payment.confirmed", 900)).isEqualTo(2);
      assertThat(value(metrics, "duration_seconds_count", "payment.confirmed", 60)).isZero();
      assertThat(value(metrics, "duration_missing", "payment.confirmed", 60)).isEqualTo(1);
    }
  }

  @Test
  void refundTpsWaitsForOrderRestorationAndAllSagaStages() {
    UUID refund = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO payment.refunds (id,payment_id,order_refund_request_id,external_amount,
          mileage_amount,status,business_key,external_status,external_ref,mileage_restored_at,
          created_at,updated_at,version)
        VALUES (?,gen_random_uuid(),gen_random_uuid(),10000,0,'INTERNAL_PENDING',gen_random_uuid(),
          'SUCCEEDED',gen_random_uuid(),statement_timestamp()-interval '5 seconds',
          statement_timestamp()-interval '30 seconds',statement_timestamp(),0)
        """,
        refund);
    jdbc.update(
        """
        INSERT INTO payment.refund_sagas (id,refund_id,step,reservation_ref,ledger_reversed_at,
          created_at,updated_at,version) VALUES (gen_random_uuid(),?,'ORDER_RESTORE_PENDING',
          gen_random_uuid(),statement_timestamp()-interval '5 seconds',
          statement_timestamp()-interval '30 seconds',statement_timestamp(),0)
        """,
        refund);
    try (var metrics = metrics()) {
      metrics.refresh();
      assertThat(value(metrics, "completions", "refund.completed", 60)).isZero();
      new TransactionTemplate(new DataSourceTransactionManager(source))
          .executeWithoutResult(
              tx -> {
                jdbc.update("UPDATE payment.refunds SET status='COMPLETED' WHERE id=?", refund);
                jdbc.update(
                    "UPDATE payment.refund_sagas SET step='COMPLETED',order_restored_at="
                        + "statement_timestamp()-interval '1 second' WHERE refund_id=?",
                    refund);
              });
      metrics.refresh();
      metrics.refresh();
      assertThat(value(metrics, "completions", "refund.completed", 60)).isEqualTo(1);
      assertThat(value(metrics, "duration_seconds_count", "refund.completed", 60)).isEqualTo(1);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void relayAddsDurableOriginHeaderWithoutChangingOriginalBody() {
    var service = Mockito.mock(OutboxDeliveryService.class);
    var template = Mockito.mock(KafkaTemplate.class);
    var accepted = Instant.parse("2026-11-12T00:00:00Z");
    var delivery =
        new Delivery(
            UUID.randomUUID(), 1, "topic", "key", new byte[] {1, 2, 3}, null, null, accepted);
    Mockito.when(service.claim(1, 240000, 10)).thenReturn(List.of(delivery), List.of());
    var reply = new CompletableFuture<SendResult<String, byte[]>>();
    Mockito.when(template.send(ArgumentMatchers.any(ProducerRecord.class))).thenReturn(reply);
    var coordinator = new ArrayDeque<Runnable>();
    var work = new ArrayDeque<Runnable>();
    var relay = new OutboxRelay(service, template, 1, 240000, 10, coordinator::add, work::add);
    try {
      relay.wakeUp();
      coordinator.remove().run();
      work.remove().run();
      var argument = ArgumentCaptor.forClass(ProducerRecord.class);
      Mockito.verify(template).send(argument.capture());
      var sent = argument.getValue();
      assertThat((byte[]) sent.value()).containsExactly(delivery.body());
      assertThat(
              new String(
                  sent.headers().lastHeader(BusinessMetrics.ORDER_ACCEPTED_AT_HEADER).value(),
                  StandardCharsets.US_ASCII))
          .isEqualTo(accepted.toString());
      reply.completeExceptionally(new IllegalStateException("test send failure"));
      work.remove().run();
    } finally {
      reply.completeExceptionally(new IllegalStateException("test cleanup"));
      while (!work.isEmpty()) work.remove().run();
      relay.stop();
    }
  }

  @Test
  void upgradeIsIdempotentAndRejectsIncompatibleExistingObservationColumn() throws Exception {
    payment("CONFIRMED", 5);
    String before =
        jdbc.queryForObject(
            "SELECT jsonb_agg(to_jsonb(t) ORDER BY id)::text FROM payment.payments t",
            String.class);
    String sql =
        Files.readString(Path.of("../infra/postgres/upgrade/payment-business-metrics.sql"));
    jdbc.execute("ALTER TABLE payment.outbox DROP COLUMN order_accepted_at");
    for (int i = 0; i < 2; i++)
      try (var connection = source.getConnection();
          var statement = connection.createStatement()) {
        statement.execute(sql);
      }
    assertThat(
            jdbc.queryForObject(
                "SELECT jsonb_agg(to_jsonb(t) ORDER BY id)::text FROM payment.payments t",
                String.class))
        .isEqualTo(before);
    jdbc.execute("ALTER TABLE payment.outbox ALTER COLUMN order_accepted_at TYPE text");
    try {
      Assertions.assertThatThrownBy(
              () -> {
                try (var connection = source.getConnection();
                    var statement = connection.createStatement()) {
                  statement.execute(sql);
                }
              })
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("Incompatible order_accepted_at");
    } finally {
      jdbc.execute(
          "ALTER TABLE payment.outbox ALTER COLUMN order_accepted_at TYPE timestamptz USING NULL::timestamptz");
    }
  }

  @Test
  void paymentListenerIgnoresBadOptionalHeadersAndRetainsValidOrigin() throws Exception {
    var mapper =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    UUID order = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
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
            now.plusMinutes(30),
            null,
            null,
            1000,
            0,
            1000,
            1000,
            0,
            0,
            0,
            1000);
    var event =
        new EventEnvelope<>(UUID.randomUUID(), "OrderPaymentRequested", 1, now, order, 0, payload);
    byte[] body = mapper.writeValueAsBytes(event);
    String accepted = "2026-11-12T00:00:00Z";
    String[][] cases = {
      {}, {"bad"}, {"+999999999-01-01T00:00:00Z"}, {accepted, accepted}, {accepted}
    };
    for (String[] headers : cases) {
      var acceptor = Mockito.mock(OrderPaymentRequestAcceptor.class);
      var listener = new OrderPaymentRequestedListener(mapper, acceptor, null, "payment.v1");
      var record =
          new ConsumerRecord<String, byte[]>(
              "commerce-settle.commerce.v1", 0, 1, order.toString(), body);
      for (String header : headers)
        record
            .headers()
            .add(
                BusinessMetrics.ORDER_ACCEPTED_AT_HEADER,
                header.getBytes(StandardCharsets.US_ASCII));
      var ack = Mockito.mock(Acknowledgment.class);
      listener.receive(record, ack);
      var argument = ArgumentCaptor.forClass(OrderPaymentRequestAcceptor.Source.class);
      Mockito.verify(acceptor).accept(ArgumentMatchers.any(), argument.capture());
      Mockito.verify(ack).acknowledge();
      assertThat(argument.getValue().body()).containsExactly(body);
      assertThat(argument.getValue().orderAcceptedAt())
          .isEqualTo(
              headers.length == 1 && headers[0].equals(accepted) ? Instant.parse(accepted) : null);
    }
  }

  private void payment(String status, int age) {
    jdbc.update(
        """
        INSERT INTO payment.payments (id,order_id,buyer_id,seller_id,product_id,current_cycle_id,
          order_version,gross_amount,coupon_amount,seller_coupon_amount,platform_coupon_amount,
          settlement_base_amount,total_amount,mileage_amount,external_amount,fee_amount,
          seller_net_amount,status,confirmed_at,policy_version,created_at,updated_at,version)
        VALUES (gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),
          gen_random_uuid(),gen_random_uuid(),0,10000,0,0,0,10000,10000,0,10000,1500,8500,?,
          CASE WHEN ?='PENDING' THEN NULL ELSE statement_timestamp()-?*interval '1 second' END,
          'v1',statement_timestamp()-1000*interval '1 second',statement_timestamp(),0)
        """,
        status,
        status,
        age);
  }

  private BusinessMetrics metrics() {
    return new BusinessMetrics(source, null, BusinessMetricsConfiguration.sources());
  }

  private static double value(
      BusinessMetrics metrics, String suffix, String operation, int window) {
    for (var metric : metrics.collect())
      if (metric.getMetadata().getName().equals("commerce_settle_business_" + suffix))
        for (var point : ((GaugeSnapshot) metric).getDataPoints())
          if (operation.equals(point.getLabels().get("operation"))
              && Integer.toString(window).equals(point.getLabels().get("window")))
            return point.getValue();
    throw new AssertionError(suffix);
  }
}
