package com.commercesettle.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor;
import com.commercesettle.payment.application.service.ApplyOrderPaymentRequestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(properties = {"management.tracing.enabled=false", "app.dev-fixtures.enabled=true"})
@Testcontainers
class DevFixtureIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/payment.sql");

  @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.2");

  @DynamicPropertySource
  static void kafka(DynamicPropertyRegistry properties) {
    properties.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }

  @Autowired DevFixtureLoader loader;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, byte[]> kafka;
  @Autowired ObjectMapper mapper;
  @Autowired OrderPaymentRequestAcceptor acceptor;
  @Autowired ApplyOrderPaymentRequestService processor;

  @Test
  void createsRealRequestsAndPreservesFinancialEffectsOnRestart() throws Exception {
    assertThat(count("payments")).isEqualTo(14);
    try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      admin
          .describeTopics(List.of(DevFixtureLoader.TOPIC))
          .allTopicNames()
          .get(10, TimeUnit.SECONDS);
    }
    // 첫 요청까지만 커밋한 뒤 전체 시나리오로 재개한다.
    var firstEvent = DevFixtureLoader.event(0);
    publishAndAccept(firstEvent);
    processor.processEvent(firstEvent.eventId());
    assertThat(count("payments")).isEqualTo(15);
    var unrelated =
        new EventEnvelope<>(
            UUID.randomUUID(),
            firstEvent.eventType(),
            1,
            firstEvent.occurredAt(),
            firstEvent.aggregateId(),
            0,
            firstEvent.payload());
    publishAndAccept(unrelated);
    loader.runScenarios();
    assertThat(count("payments")).isEqualTo(17);
    assertThat(count("payment_jobs")).isEqualTo(2);
    assertThat(count("mileage_reservations")).isEqualTo(2);
    assertThat(count("mileage_entries")).isEqualTo(5);
    assertThat(
            jdbc.queryForList(
                "SELECT status FROM payment.inbox WHERE status <> 'WAITING' ORDER BY status",
                String.class))
        .containsExactly("APPLIED", "APPLIED", "APPLIED", "REVIEW_REQUIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT balance FROM payment.mileage_wallets WHERE buyer_id='00000000-0000-4000-8000-000000000001'",
                Long.class))
        .isEqualTo(90_000);
    assertThat(
            jdbc.queryForObject(
                "SELECT reserved_amount FROM payment.mileage_wallets WHERE buyer_id='00000000-0000-4000-8000-000000000001'",
                Long.class))
        .isEqualTo(1_000);
    var before = jdbc.queryForList("SELECT * FROM payment.mileage_entries ORDER BY id");
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                loader.load();
                return null;
              });
      var second =
          executor.submit(
              () -> {
                loader.load();
                return null;
              });
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    }
    loader.runScenarios();
    assertThat(jdbc.queryForList("SELECT * FROM payment.mileage_entries ORDER BY id"))
        .isEqualTo(before);
    assertThat(count("payments")).isEqualTo(17);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM payment.inbox WHERE event_id=?",
                String.class,
                unrelated.eventId()))
        .isEqualTo("WAITING");
    // 잘못된 snapshot ID는 앞선 신규 지갑까지 롤백한다.
    jdbc.update(
        "DELETE FROM payment.mileage_entries WHERE wallet_id IN (SELECT id FROM payment.mileage_wallets WHERE buyer_id='00000000-0000-4000-8000-000000000061')");
    jdbc.update(
        "DELETE FROM payment.mileage_wallets WHERE buyer_id='00000000-0000-4000-8000-000000000061'");
    jdbc.update(
        "UPDATE payment.payments SET id=? WHERE order_id=?",
        java.util.UUID.randomUUID(),
        DevFixtureLoader.id("order-state-PENDING"));
    assertThatThrownBy(loader::load).isInstanceOf(IllegalStateException.class);
    assertThat(count("mileage_wallets")).isEqualTo(3);
  }

  @Test
  void workerNeverLoadsFixturesAndDefaultIsDisabled() {
    var context =
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withUserConfiguration(
                com.commercesettle.payment.infrastructure.config.DevFixtureConfiguration.class)
            .withBean(
                DevFixtureLoader.class, () -> org.mockito.Mockito.mock(DevFixtureLoader.class));
    context.run(c -> assertThat(c).doesNotHaveBean("paymentDevFixtures"));
    context
        .withPropertyValues("app.dev-fixtures.enabled=true", "PAYMENT_ROLE=worker")
        .run(
            c ->
                assertThat(c)
                    .doesNotHaveBean("paymentDevFixtures")
                    .doesNotHaveBean("devFixturePaymentTopic"));
  }

  private void publishAndAccept(
      EventEnvelope<com.commercesettle.payment.application.event.OrderPaymentRequestedPayload>
          event)
      throws Exception {
    byte[] body = mapper.writeValueAsBytes(event);
    var meta =
        kafka
            .send(DevFixtureLoader.TOPIC, event.aggregateId().toString(), body)
            .get(15, TimeUnit.SECONDS)
            .getRecordMetadata();
    acceptor.accept(
        event,
        new OrderPaymentRequestAcceptor.Source(
            meta.topic(), meta.partition(), meta.offset(), body));
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM payment." + table, Integer.class);
  }
}
