package com.commercesettle.settlement.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.event.PaymentConfirmedPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/** 격리를 켠 실제 Kafka·PostgreSQL에서 해석 불가 원문이 격리·DLT로 넘어가고 뒤의 메시지가 계속 처리되는지 확인한다. */
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "spring.kafka.admin.auto-create=false",
      "settlement.daily-batch.schedule-enabled=false",
      "settlement.payout-worker.enabled=false",
      "settlement.inbox-processor.enabled=false",
      "app.kafka.settlement-events.enabled=true",
      "app.kafka.quarantine.enabled=true",
      "app.kafka.quarantine.interval-ms=100"
    })
@Timeout(120)
class SettlementQuarantineIntegrationTest {
  private static final String PAYMENT_TOPIC = SettlementEventListener.PAYMENT_TOPIC;
  private static final String COMMERCE_TOPIC = SettlementEventListener.COMMERCE_TOPIC;
  private static final String DLT_TOPIC = "commerce-settle.settlement.v1.dlt";
  private static final String GROUP = "settlement.v1";

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
    prepareTopicsAndGroup();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired KafkaTemplate<String, byte[]> template;
  @Autowired KafkaListenerEndpointRegistry registry;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  @Test
  @DisplayName("해석할 수 없는 대상 이벤트는 원문을 격리하고 ack하며, DLT에 같은 bytes와 원본 위치가 발행되고 뒤의 메시지도 처리된다")
  void quarantinesInvalidRecordAndContinues() throws Exception {
    String key = UUID.randomUUID().toString();
    byte[] invalid =
        "{\"eventType\":\"PaymentConfirmed\",\"payload\":{}}".getBytes(StandardCharsets.UTF_8);
    byte[] unreadable = "not-json".getBytes(StandardCharsets.UTF_8);

    send(PAYMENT_TOPIC, key, invalid);
    send(PAYMENT_TOPIC, null, unreadable);
    UUID paymentId = UUID.randomUUID();
    send(PAYMENT_TOPIC, paymentId.toString(), payment(paymentId));

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> assertThat(committed(new TopicPartition(PAYMENT_TOPIC, 0))).isEqualTo(3));
    assertThat(registry.getListenerContainer("settlementEvents").isRunning()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM settlement.inbox WHERE aggregate_id = ?",
                String.class,
                paymentId))
        .isEqualTo("APPLIED");
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT source_offset, message_key, raw_body, error_code FROM settlement.inbox_quarantines"
                + " ORDER BY source_offset");
    assertThat(rows).hasSize(2);
    assertThat((byte[]) rows.get(0).get("message_key"))
        .isEqualTo(key.getBytes(StandardCharsets.UTF_8));
    assertThat((byte[]) rows.get(0).get("raw_body")).isEqualTo(invalid);
    assertThat(rows.get(1).get("message_key")).isNull();
    assertThat((byte[]) rows.get(1).get("raw_body")).isEqualTo(unreadable);
    assertThat(rows.get(0).get("error_code")).isEqualTo("SETTLEMENT_EVENT_INVALID");

    List<ConsumerRecord<String, byte[]>> dlt = readDlt(2);
    assertThat(dlt)
        .extracting(ConsumerRecord::value)
        .containsExactlyInAnyOrder(invalid, unreadable);
    ConsumerRecord<String, byte[]> first =
        dlt.stream().filter(r -> key.equals(r.key())).findFirst().orElseThrow();
    assertThat(header(first, "quarantine-source-topic")).isEqualTo(PAYMENT_TOPIC);
    assertThat(header(first, "quarantine-source-partition")).isEqualTo("0");
    assertThat(header(first, "quarantine-source-offset")).isEqualTo("0");
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForList(
                            "SELECT status FROM settlement.inbox_quarantine_deliveries",
                            String.class))
                    .containsExactly("PUBLISHED", "PUBLISHED"));
  }

  private void send(String topic, String key, byte[] body) throws Exception {
    template.send(new ProducerRecord<>(topic, 0, key, body)).get(10, TimeUnit.SECONDS);
  }

  private byte[] payment(UUID paymentId) throws Exception {
    OffsetDateTime at = OffsetDateTime.parse("2026-10-01T12:00:00+09:00");
    return mapper.writeValueAsBytes(
        new EventEnvelope<>(
            UUID.randomUUID(),
            "PaymentConfirmed",
            1,
            at,
            paymentId,
            1,
            new PaymentConfirmedPayload(
                10_000,
                0,
                10_000,
                0,
                10_000,
                paymentId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                3,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1_500,
                8_500,
                at,
                "v1",
                0,
                0,
                10_000)));
  }

  private static List<ConsumerRecord<String, byte[]>> readDlt(int expected) {
    List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
    try (KafkaConsumer<String, byte[]> consumer =
        new KafkaConsumer<>(
            Map.of(
                "bootstrap.servers",
                KAFKA.getBootstrapServers(),
                "group.id",
                "dlt-reader-" + UUID.randomUUID(),
                "auto.offset.reset",
                "earliest"),
            new StringDeserializer(),
            new ByteArrayDeserializer())) {
      consumer.subscribe(List.of(DLT_TOPIC));
      await()
          .atMost(Duration.ofSeconds(30))
          .until(
              () -> {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
                return records.size() >= expected;
              });
    }
    return records;
  }

  private static String header(ConsumerRecord<String, byte[]> record, String name) {
    return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
  }

  private static long committed(TopicPartition partition) throws Exception {
    try (Admin admin = admin()) {
      OffsetAndMetadata offset =
          admin
              .listConsumerGroupOffsets(GROUP)
              .partitionsToOffsetAndMetadata()
              .get(10, TimeUnit.SECONDS)
              .get(partition);
      return offset == null ? -1 : offset.offset();
    }
  }

  private static void prepareTopicsAndGroup() {
    try (Admin admin = admin()) {
      admin
          .createTopics(
              List.of(
                  new NewTopic(PAYMENT_TOPIC, 3, (short) 1),
                  new NewTopic(COMMERCE_TOPIC, 3, (short) 1),
                  new NewTopic(DLT_TOPIC, 3, (short) 1)))
          .all()
          .get(10, TimeUnit.SECONDS);
      Map<TopicPartition, OffsetAndMetadata> initial = new HashMap<>();
      for (String topic : List.of(PAYMENT_TOPIC, COMMERCE_TOPIC)) {
        for (int partition = 0; partition < 3; partition++) {
          initial.put(new TopicPartition(topic, partition), new OffsetAndMetadata(0));
        }
      }
      admin.alterConsumerGroupOffsets(GROUP, initial).all().get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Admin admin() {
    return Admin.create(
        Map.of(
            "bootstrap.servers", KAFKA.getBootstrapServers(),
            "default.api.timeout.ms", 10000,
            "request.timeout.ms", 5000));
  }
}
