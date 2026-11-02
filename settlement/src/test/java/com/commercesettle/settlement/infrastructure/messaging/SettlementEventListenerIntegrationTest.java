package com.commercesettle.settlement.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.event.PaymentConfirmedPayload;
import com.commercesettle.settlement.application.event.PurchaseConfirmedPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * 실제 Kafka·PostgreSQL에서 수신 경계를 확인한다. 대상이 아닌 이벤트는 저장 없이 지나가고, 두 토픽 사이 순서와 관계없이 항목이 PAYABLE이 되며, 해석할
 * 수 없는 대상 이벤트는 offset을 커밋하지 않고 컨테이너를 멈춘다.
 */
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123",
      "settlement.payout.mock-credential=test-settlement-credential-0123456789abcdef0123",
      "spring.kafka.admin.auto-create=false",
      "settlement.daily-batch.schedule-enabled=false",
      "settlement.payout-worker.enabled=false",
      "app.kafka.settlement-events.enabled=true",
      "settlement.inbox-processor.enabled=true",
      "settlement.inbox-processor.interval-ms=100"
    })
@TestMethodOrder(OrderAnnotation.class)
@Timeout(120)
class SettlementEventListenerIntegrationTest {
  private static final String PAYMENT_TOPIC = SettlementEventListener.PAYMENT_TOPIC;
  private static final String COMMERCE_TOPIC = SettlementEventListener.COMMERCE_TOPIC;
  private static final String GROUP = "settlement.v1";
  private static final OffsetDateTime DELIVERED_AT =
      OffsetDateTime.parse("2026-10-10T12:00:00+09:00");

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
  @Order(1)
  @DisplayName("대상이 아닌 이벤트는 저장 없이 지나가고, 구매확정이 먼저 와도 결제확정 뒤 항목이 PAYABLE이 된다")
  void consumesTargetEventsInAnyOrder() throws Exception {
    UUID paymentId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();

    send(COMMERCE_TOPIC, 0, paymentId, purchase(paymentId, orderId));
    send(PAYMENT_TOPIC, 0, paymentId, paymentAccepted(paymentId));
    send(PAYMENT_TOPIC, 0, paymentId, payment(paymentId, orderId));

    await()
        .atMost(Duration.ofSeconds(30))
        // 항목이 생기기 전에는 행이 없어 예외가 난다.
        .ignoreException(EmptyResultDataAccessException.class)
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForObject(
                            "SELECT status FROM settlement.settlement_items WHERE payment_id = ?",
                            String.class,
                            paymentId))
                    .isEqualTo("PAYABLE"));
    assertThat(
            jdbc.queryForList(
                "SELECT event_type, status FROM settlement.inbox ORDER BY event_type"))
        .extracting(row -> row.get("event_type") + ":" + row.get("status"))
        .containsExactly("PaymentConfirmed:APPLIED", "PurchaseConfirmed:APPLIED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM settlement.settlement_entries WHERE payment_id = ?",
                Long.class,
                paymentId))
        .isEqualTo(5);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(committed(new TopicPartition(PAYMENT_TOPIC, 0))).isEqualTo(2);
              assertThat(committed(new TopicPartition(COMMERCE_TOPIC, 0))).isEqualTo(1);
            });
  }

  @Test
  @Order(2)
  @DisplayName("해석할 수 없는 대상 이벤트는 offset을 커밋하지 않고 수신 컨테이너를 멈춘다")
  void stopsWithoutCommitOnInvalidTargetEvent() throws Exception {
    UUID paymentId = UUID.randomUUID();
    // key가 paymentId와 다르다.
    send(PAYMENT_TOPIC, 1, UUID.randomUUID(), payment(paymentId, UUID.randomUUID()));

    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> !registry.getListenerContainer("settlementEvents").isRunning());
    assertThat(committed(new TopicPartition(PAYMENT_TOPIC, 1))).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM settlement.inbox WHERE aggregate_id = ?",
                Long.class,
                paymentId))
        .isZero();
  }

  private void send(String topic, int partition, UUID key, byte[] body) throws Exception {
    template
        .send(new ProducerRecord<>(topic, partition, key.toString(), body))
        .get(10, TimeUnit.SECONDS);
  }

  private byte[] payment(UUID paymentId, UUID orderId) throws Exception {
    return mapper.writeValueAsBytes(
        new EventEnvelope<>(
            UUID.randomUUID(),
            "PaymentConfirmed",
            1,
            OffsetDateTime.parse("2026-10-01T12:00:00+09:00"),
            paymentId,
            1,
            new PaymentConfirmedPayload(
                10_000,
                0,
                10_000,
                0,
                10_000,
                paymentId,
                orderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                3,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1_500,
                8_500,
                OffsetDateTime.parse("2026-10-01T12:00:00+09:00"),
                "v1",
                0,
                0,
                10_000)));
  }

  private byte[] purchase(UUID paymentId, UUID orderId) throws Exception {
    return mapper.writeValueAsBytes(
        new EventEnvelope<>(
            UUID.randomUUID(),
            "PurchaseConfirmed",
            1,
            OffsetDateTime.parse("2026-10-17T12:10:00+09:00"),
            orderId,
            5,
            new PurchaseConfirmedPayload(
                paymentId,
                orderId,
                4,
                DELIVERED_AT.plusHours(168).plusMinutes(5),
                DELIVERED_AT,
                DELIVERED_AT.plusHours(168))));
  }

  /** Settlement가 처리하지 않는 같은 토픽의 이벤트. 본문은 eventType만 읽는다. */
  private static byte[] paymentAccepted(UUID paymentId) {
    return ("{\"eventId\":\""
            + UUID.randomUUID()
            + "\",\"eventType\":\"PaymentAccepted\","
            + "\"eventVersion\":1,\"occurredAt\":\"2026-10-01T12:00:00+09:00\","
            + "\"aggregateId\":\""
            + paymentId
            + "\",\"aggregateVersion\":0,\"payload\":{}}")
        .getBytes(StandardCharsets.UTF_8);
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

  /** auto.offset.reset=none이므로 개발 환경 절차처럼 빈 토픽에 그룹 offset을 0으로 초기화한다. */
  private static void prepareTopicsAndGroup() {
    try (Admin admin = admin()) {
      admin
          .createTopics(
              List.of(
                  new NewTopic(PAYMENT_TOPIC, 3, (short) 1),
                  new NewTopic(COMMERCE_TOPIC, 3, (short) 1)))
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
