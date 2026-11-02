package com.commercesettle.settlement.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.port.SettlementEventOutbox;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/** Relay를 켠 실제 Kafka·PostgreSQL에서 커밋한 Outbox만 원문 그대로 발행되고 발행 위치가 저장되는지 확인한다. */
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "spring.kafka.admin.auto-create=false",
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123",
      "settlement.payout.mock-credential=test-settlement-credential-0123456789abcdef0123",
      "settlement.daily-batch.schedule-enabled=false",
      "settlement.payout-worker.enabled=false",
      "settlement.inbox-processor.enabled=false",
      "app.outbox.enabled=true"
    })
@Timeout(120)
class SettlementOutboxRelayIntegrationTest {
  private static final String TOPIC = SettlementEventOutbox.TOPIC;

  static final KafkaContainer KAFKA =
      new KafkaContainer("apache/kafka:4.2.2")
          .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m")
          .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(768L * 1024 * 1024))
          .withStartupTimeout(Duration.ofMinutes(2));

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  static {
    KAFKA.start();
    POSTGRES.start();
    try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1)))
          .all()
          .get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired SettlementEventOutbox outbox;
  @Autowired TransactionTemplate transactions;
  @Autowired JdbcTemplate jdbc;

  @Test
  @DisplayName("커밋한 이벤트는 지정 key·저장 원문 그대로 발행하고 위치를 저장하며, 롤백한 이벤트는 발행하지 않는다")
  void publishesCommittedEventsOnly() {
    UUID key = UUID.randomUUID();
    var committed = event("RefundReservationResult");
    var rolledBack = event("RefundReservationReleased");

    transactions.executeWithoutResult(status -> outbox.append(committed, key));
    transactions.executeWithoutResult(
        status -> {
          outbox.append(rolledBack, key);
          status.setRollbackOnly();
        });

    List<ConsumerRecord<String, byte[]>> records = read(1);
    assertThat(records).hasSize(1);
    ConsumerRecord<String, byte[]> record = records.getFirst();
    assertThat(record.key()).isEqualTo(key.toString());
    assertThat(record.value())
        .isEqualTo(
            jdbc.queryForObject(
                "SELECT raw_body FROM settlement.outbox WHERE id = ?",
                byte[].class,
                committed.eventId()));
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForMap(
                            "SELECT status, published_partition, published_offset"
                                + " FROM settlement.outbox WHERE id = ?",
                            committed.eventId()))
                    .containsEntry("status", "PUBLISHED")
                    .containsEntry("published_partition", record.partition())
                    .containsEntry("published_offset", record.offset()));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement.outbox", Long.class))
        .isEqualTo(1);
  }

  private static EventEnvelope<Map<String, Object>> event(String type) {
    return new EventEnvelope<>(
        UUID.randomUUID(),
        type,
        1,
        OffsetDateTime.parse("2026-11-02T10:00:00+09:00"),
        UUID.randomUUID(),
        0,
        Map.of("refundId", UUID.randomUUID().toString()));
  }

  private static List<ConsumerRecord<String, byte[]>> read(int expected) {
    List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
    try (KafkaConsumer<String, byte[]> consumer =
        new KafkaConsumer<>(
            Map.of(
                "bootstrap.servers",
                KAFKA.getBootstrapServers(),
                "group.id",
                "outbox-reader-" + UUID.randomUUID(),
                "auto.offset.reset",
                "earliest"),
            new StringDeserializer(),
            new ByteArrayDeserializer())) {
      consumer.subscribe(List.of(TOPIC));
      await()
          .atMost(Duration.ofSeconds(30))
          .until(
              () -> {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
                return records.size() >= expected;
              });
      // 롤백한 이벤트가 늦게 발행되지 않는지 잠깐 더 읽는다.
      consumer.poll(Duration.ofSeconds(2)).forEach(records::add);
    }
    return records;
  }
}
