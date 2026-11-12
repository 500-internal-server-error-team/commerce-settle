package com.commercesettle.commerce.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.tracing.enabled=false",
      "app.auth.session-epoch=00000000-0000-4000-8000-000000000000",
      "app.auth.rate-limit.identifier-hmac-key=notice-kafka-test-hmac-key-32bytes-long",
      "app.auth.password-reset.delivery-key=notice-kafka-test-delivery-key-32bytes",
      "app.address.encryption-key=notice-kafka-test-address-key-32bytes-long",
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123",
      "app.notifications.consumer-enabled=true",
      "app.kafka.payment-accepted.enabled=true",
      "spring.kafka.consumer.auto-offset-reset=earliest"
    })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(90)
@Import(NotificationKafkaIntegrationTest.Topics.class)
class NotificationKafkaIntegrationTest {
  private static final String PAYMENT = "commerce-settle.payment.v1";
  private static final String SETTLEMENT = "commerce-settle.settlement.v1";
  private static final String GROUP = "commerce-notifications.v1";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer("apache/kafka:4.2.2").withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m");

  @DynamicPropertySource
  static void kafka(DynamicPropertyRegistry properties) {
    properties.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }

  @Autowired KafkaTemplate<String, byte[]> kafka;
  @Autowired KafkaListenerEndpointRegistry listeners;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  @Test
  @DisplayName("실제 Kafka에서 주문 선행 대기를 영속 저장한 뒤 알림·금융 그룹이 각각 ACK한다")
  void commitsIndependentOffsetsAfterDurableWaitingHandoff() throws Exception {
    ready();
    var event = notice();
    var sent = send(event);
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(
                      jdbc.queryForList(
                          "SELECT status FROM commerce.inbox WHERE consumer=? AND event_id=?",
                          String.class,
                          GROUP,
                          event.eventId()))
                  .containsExactly("WAITING");
              assertThat(offset(GROUP, sent)).isGreaterThan(sent.offset());
              assertThat(offset("commerce.v1", sent)).isGreaterThan(sent.offset());
            });
    assertThat(listeners.getListenerContainer("paymentAccepted").isRunning()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM commerce.notification_items WHERE source_event_id=?",
                Long.class,
                event.eventId()))
        .isZero();
  }

  @Test
  @DisplayName("알림 인계 DB 실패는 자신의 offset만 보존하고 재시작 뒤 같은 메시지를 재개한다")
  void recoversFailedHandoffWithoutStoppingFinancialConsumer() throws Exception {
    ready();
    var consumer = listeners.getListenerContainer("transactionNotifications");
    var event = notice();
    jdbc.execute(
        "CREATE FUNCTION commerce.reject_notification_inbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.consumer='commerce-notifications.v1' THEN RAISE EXCEPTION 'injected'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_notification_inbox BEFORE INSERT ON commerce.inbox FOR EACH ROW EXECUTE FUNCTION commerce.reject_notification_inbox()");
    RecordMetadata sent;
    try {
      sent = send(event);
      await().atMost(Duration.ofSeconds(20)).until(() -> !consumer.isRunning());
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () -> assertThat(offset("commerce.v1", sent)).isGreaterThan(sent.offset()));
      assertThat(offset(GROUP, sent)).isLessThanOrEqualTo(sent.offset());
      assertThat(listeners.getListenerContainer("paymentAccepted").isRunning()).isTrue();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM commerce.inbox WHERE consumer=? AND event_id=?",
                  Long.class,
                  GROUP,
                  event.eventId()))
          .isZero();
    } finally {
      jdbc.execute("DROP TRIGGER reject_notification_inbox ON commerce.inbox");
      jdbc.execute("DROP FUNCTION commerce.reject_notification_inbox()");
      consumer.start();
    }
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(
                      jdbc.queryForObject(
                          "SELECT count(*) FROM commerce.inbox WHERE consumer=? AND event_id=?",
                          Long.class,
                          GROUP,
                          event.eventId()))
                  .isEqualTo(1);
              assertThat(offset(GROUP, sent)).isGreaterThan(sent.offset());
            });
  }

  @Test
  @DisplayName("해석 불가 원문의 bytes·키·위치를 실제 DB에 격리한 뒤 offset을 커밋한다")
  void quarantinesMalformedRecordBeforeKafkaCommit() throws Exception {
    ready();
    byte[] raw = {(byte) 255, 0, 23};
    var sent = kafka.send(SETTLEMENT, "raw-key", raw).get(10, TimeUnit.SECONDS).getRecordMetadata();
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              var row =
                  jdbc.queryForMap(
                      "SELECT raw_body,message_key,error_code FROM commerce.notification_inbox_quarantines WHERE source_topic=? AND source_partition=? AND source_offset=?",
                      sent.topic(),
                      sent.partition(),
                      sent.offset());
              assertThat((byte[]) row.get("raw_body")).isEqualTo(raw);
              assertThat(row.get("message_key")).isEqualTo("raw-key");
              assertThat(offset(GROUP, sent)).isGreaterThan(sent.offset());
            });
    assertThat(listeners.getListenerContainer("transactionNotifications").isRunning()).isTrue();
  }

  private void ready() {
    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () -> {
              var notifications = listeners.getListenerContainer("transactionNotifications");
              var financial = listeners.getListenerContainer("paymentAccepted");
              return notifications.isRunning()
                  && notifications.getAssignedPartitions() != null
                  && notifications.getAssignedPartitions().size() == 6
                  && financial.isRunning()
                  && financial.getAssignedPartitions() != null
                  && financial.getAssignedPartitions().size() == 3;
            });
  }

  private EventEnvelope<JsonNode> notice() throws Exception {
    var canonical = mapper.readTree(getClass().getResourceAsStream("/contracts/openapi.json"));
    var p =
        (ObjectNode)
            canonical
                .path("components")
                .path("schemas")
                .path("PaymentNoticeRequestedPayload")
                .path("examples")
                .get(0)
                .deepCopy();
    return new EventEnvelope<>(
        UUID.randomUUID(),
        "PaymentNoticeRequested",
        1,
        OffsetDateTime.now(ZoneOffset.UTC),
        UUID.randomUUID(),
        1,
        p);
  }

  private RecordMetadata send(EventEnvelope<JsonNode> event) throws Exception {
    return kafka
        .send(PAYMENT, event.payload().path("paymentId").asText(), mapper.writeValueAsBytes(event))
        .get(10, TimeUnit.SECONDS)
        .getRecordMetadata();
  }

  private long offset(String group, RecordMetadata sent) throws Exception {
    try (var admin =
        Admin.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
      var value =
          admin
              .listConsumerGroupOffsets(group)
              .partitionsToOffsetAndMetadata()
              .get(10, TimeUnit.SECONDS)
              .get(new TopicPartition(sent.topic(), sent.partition()));
      return value == null ? 0 : value.offset();
    }
  }

  @TestConfiguration
  static class Topics {
    @Bean
    NewTopic notificationPaymentTopic() {
      return new NewTopic(PAYMENT, 3, (short) 1);
    }

    @Bean
    NewTopic notificationSettlementTopic() {
      return new NewTopic(SETTLEMENT, 3, (short) 1);
    }
  }
}
