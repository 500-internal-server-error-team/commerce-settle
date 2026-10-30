package com.commercesettle.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.commercesettle.payment.application.port.QuarantineStore.Source;
import com.commercesettle.payment.application.service.QuarantineService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "app.kafka.order-payment-requested.enabled=true",
      "app.kafka.quarantine.enabled=true",
      "app.kafka.quarantine.interval-ms=3600000",
      "spring.kafka.listener.auto-startup=false",
      "spring.kafka.consumer.group-id=quarantine-integration",
      "management.tracing.sampling.probability=0",
      "spring.kafka.producer.properties.max.block.ms=1000",
      "spring.kafka.producer.properties.delivery.timeout.ms=2000",
      "spring.kafka.producer.properties.request.timeout.ms=500"
    })
@Testcontainers
@Timeout(90)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuarantineIntegrationTest {
  private static final String TOPIC = "commerce-settle.commerce.v1";
  private static final String GROUP = "quarantine-integration";
  private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3")
          .withInitScript("db/payment.sql")
          .withCreateContainerCmdModifier(
              cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024));

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer("apache/kafka:4.2.2")
          .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m")
          .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
          .withCreateContainerCmdModifier(
              cmd -> cmd.getHostConfig().withMemory(768L * 1024 * 1024));

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }

  @Autowired private JdbcTemplate jdbc;
  @Autowired private QuarantineService quarantine;
  @Autowired private QuarantineRelay relay;
  @Autowired private KafkaTemplate<String, byte[]> kafka;
  @Autowired private KafkaListenerEndpointRegistry listeners;
  @Autowired private DataSource dataSource;

  @BeforeEach
  void reset() throws Exception {
    var listener = listeners.getListenerContainer("orderPaymentRequested");
    if (listener.isRunning()) {
      var stopped = new CountDownLatch(1);
      listener.stop(stopped::countDown);
      assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue();
    }
    jdbc.update("DELETE FROM payment.inbox_conflicts");
    jdbc.update("DELETE FROM payment.inbox");
    jdbc.update("DELETE FROM payment.inbox_quarantine_resumes");
    jdbc.update("DELETE FROM payment.inbox_quarantine_deliveries");
    jdbc.update("DELETE FROM payment.inbox_quarantines");
    try (var admin = admin()) {
      if (!admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(TOPIC)) {
        admin
            .createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1)))
            .all()
            .get(10, TimeUnit.SECONDS);
      }
      long end =
          admin
              .listOffsets(Map.of(PARTITION, OffsetSpec.latest()))
              .all()
              .get(10, TimeUnit.SECONDS)
              .get(PARTITION)
              .offset();
      admin
          .alterConsumerGroupOffsets(GROUP, Map.of(PARTITION, new OffsetAndMetadata(end)))
          .all()
          .get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  @DisplayName("오류 원문을 커밋한 뒤 다음 정상 메시지를 처리하고 DLT·재처리에도 원문을 유지한다")
  void quarantinesPublishesAndRevalidatesOriginalBytes() throws Exception {
    byte[] invalid = "{invalid-json".getBytes(StandardCharsets.UTF_8);
    listeners.start();
    kafka.send(new ProducerRecord<>(TOPIC, 0, "invalid-key", invalid)).get(10, TimeUnit.SECONDS);
    var valid = OrderPaymentRequestedListenerTest.EVENT_JSON.getBytes(StandardCharsets.UTF_8);
    kafka
        .send(new ProducerRecord<>(TOPIC, 0, "00000000-0000-0000-0000-000000000001", valid))
        .get(10, TimeUnit.SECONDS);
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> {
              assertThat(count("inbox_quarantines")).isEqualTo(1);
              assertThat(count("inbox")).isEqualTo(1);
            });
    UUID id = jdbc.queryForObject("SELECT id FROM payment.inbox_quarantines", UUID.class);
    assertThat(jdbc.queryForObject("SELECT raw_body FROM payment.inbox_quarantines", byte[].class))
        .containsExactly(invalid);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM payment.inbox_quarantine_deliveries", String.class))
        .isEqualTo("PENDING");

    relay.run();

    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM payment.inbox_quarantine_deliveries", String.class))
        .isEqualTo("PUBLISHED");
    var config = new HashMap<String, Object>();
    config.put("bootstrap.servers", KAFKA.getBootstrapServers());
    config.put("group.id", "inspect-quarantine-" + UUID.randomUUID());
    config.put("enable.auto.commit", false);
    config.put("auto.offset.reset", "earliest");
    try (var consumer =
        new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer())) {
      consumer.subscribe(List.of("commerce-settle.payment.v1.dlt"));
      await()
          .atMost(Duration.ofSeconds(15))
          .untilAsserted(
              () -> {
                var matching =
                    java.util.stream.StreamSupport.stream(
                            consumer
                                .poll(Duration.ofMillis(250))
                                .records("commerce-settle.payment.v1.dlt")
                                .spliterator(),
                            false)
                        .filter(
                            record ->
                                id.toString()
                                    .equals(
                                        new String(
                                            record.headers().lastHeader("quarantine-id").value(),
                                            StandardCharsets.UTF_8)))
                        .toList();
                assertThat(matching)
                    .singleElement()
                    .satisfies(
                        record -> {
                          assertThat(record.key()).isEqualTo("invalid-key");
                          assertThat(record.value()).containsExactly(invalid);
                        });
              });
    }
    quarantine.requestReplay(id, 0, "experiment", "PARSER_REVIEWED");
    assertThatThrownBy(() -> quarantine.requestReplay(id, 0, "experiment", "PARSER_REVIEWED"))
        .isInstanceOf(IllegalStateException.class);
    relay.run();
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(() -> assertThat(count("inbox_quarantines")).isEqualTo(2));
    assertThat(count("inbox")).isEqualTo(1);
    assertThat(jdbc.queryForList("SELECT raw_body FROM payment.inbox_quarantines", byte[].class))
        .allSatisfy(body -> assertThat(body).containsExactly(invalid));
    assertThat(
            jdbc.queryForObject(
                "SELECT operator_id FROM payment.inbox_quarantine_deliveries WHERE kind='REPLAY'",
                String.class))
        .isEqualTo("experiment");
  }

  @Test
  @DisplayName("같은 원본 위치는 한 번만 격리하며 null과 빈 원문을 구분한다")
  void deduplicatesSourceAndPreservesNull() {
    var first = new Source(GROUP, TOPIC, 0, 700, null, null);
    UUID id = quarantine.capture(first);
    assertThat(quarantine.capture(first)).isEqualTo(id);
    quarantine.capture(new Source(GROUP, TOPIC, 0, 701, "", new byte[0]));
    assertThatThrownBy(
            () -> quarantine.capture(new Source(GROUP, TOPIC, 0, 700, null, new byte[0])))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count("inbox_quarantines")).isEqualTo(2);
    assertThat(count("inbox_quarantine_deliveries")).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT raw_body IS NULL FROM payment.inbox_quarantines WHERE id=?",
                Boolean.class,
                id))
        .isTrue();
    assertThat(quarantine.requestReplay(id, 0, "experiment", "REVIEWED")).isNotNull();
    assertThatThrownBy(() -> quarantine.requestReplay(id, 0, "experiment", "REVIEWED"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("DLT 소진 뒤 운영 재개는 이전 시도·요청 이력을 보존하고 같은 작업을 다시 발행한다")
  void resumesExhaustedDeliveryWithVersionedAudit() throws Exception {
    UUID raw = quarantine.capture(new Source(GROUP, TOPIC, 0, 950, "key", new byte[] {9}));
    for (int i = 0; i < 10; i++) {
      var delivery = quarantine.claim(1, 5000, 10).getFirst();
      assertThat(quarantine.failed(delivery)).isTrue();
      jdbc.update(
          "UPDATE payment.inbox_quarantine_deliveries SET next_run_at=now()-interval '1 second'");
    }
    assertThat(quarantine.claim(1, 5000, 10)).isEmpty();
    UUID id = jdbc.queryForObject("SELECT id FROM payment.inbox_quarantine_deliveries", UUID.class);
    long version =
        jdbc.queryForObject("SELECT version FROM payment.inbox_quarantine_deliveries", Long.class);
    UUID audit = quarantine.resumeDelivery(id, version, "experiment", "BROKER_RECOVERED");
    assertThatThrownBy(
            () -> quarantine.resumeDelivery(id, version, "experiment", "BROKER_RECOVERED"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForMap(
                "SELECT previous_attempts,operator_id FROM payment.inbox_quarantine_resumes WHERE id=?",
                audit))
        .containsEntry("previous_attempts", 10)
        .containsEntry("operator_id", "experiment");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payment.inbox_quarantine_resumes", Long.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT quarantine_id FROM payment.inbox_quarantine_deliveries", UUID.class))
        .isEqualTo(raw);
    relay.run();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM payment.inbox_quarantine_deliveries WHERE id=?",
                String.class,
                id))
        .isEqualTo("PUBLISHED");
  }

  @Test
  @DisplayName("결과 저장이 행 잠금을 기다리는 동안 임대가 끝나면 늦은 결과를 거절한다")
  void rejectsResultWhoseLeaseExpiresWhileWaitingForLock() throws Exception {
    quarantine.capture(new Source(GROUP, TOPIC, 0, 951, "key", new byte[] {9}));
    var delivery = quarantine.claim(1, 900, 10).getFirst();
    var executor = Executors.newSingleThreadExecutor();
    try (var held = dataSource.getConnection()) {
      held.setAutoCommit(false);
      try (var statement =
          held.prepareStatement(
              "SELECT id FROM payment.inbox_quarantine_deliveries WHERE id=? FOR UPDATE")) {
        statement.setObject(1, delivery.id());
        statement.executeQuery().close();
      }
      var result = executor.submit(() -> quarantine.published(delivery));
      try {
        await()
            .pollInterval(Duration.ofMillis(10))
            .atMost(Duration.ofSeconds(1))
            .until(
                () ->
                    jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%inbox_quarantine_deliveries%'",
                            Long.class)
                        > 0);
        assertThat(
                jdbc.queryForObject(
                    "SELECT clock_timestamp()<lease_until FROM payment.inbox_quarantine_deliveries WHERE id=?",
                    Boolean.class,
                    delivery.id()))
            .isTrue();
        await()
            .pollInterval(Duration.ofMillis(10))
            .atMost(Duration.ofSeconds(1))
            .until(
                () ->
                    jdbc.queryForObject(
                        "SELECT clock_timestamp()>=lease_until FROM payment.inbox_quarantine_deliveries WHERE id=?",
                        Boolean.class,
                        delivery.id()));
      } finally {
        held.rollback();
      }
      assertThat(result.get(2, TimeUnit.SECONDS)).isFalse();
      var reclaimed = quarantine.claim(1, 5000, 10).getFirst();
      assertThat(reclaimed.id()).isEqualTo(delivery.id());
      assertThat(reclaimed.version()).isGreaterThan(delivery.version());
      assertThat(quarantine.failed(delivery)).isFalse();
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("DLT 발행 실패는 원문과 재시도 작업을 보존하고 복구 뒤 발행한다")
  void retriesDltFromDurableRecord() throws Exception {
    quarantine.capture(new Source(GROUP, TOPIC, 0, 900, "key", new byte[] {7, 8}));
    String topic = "commerce-settle.payment.v1.dlt";
    try (var admin = admin()) {
      admin.deleteTopics(List.of(topic)).all().get(10, TimeUnit.SECONDS);
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> !admin.listTopics().names().get(5, TimeUnit.SECONDS).contains(topic));
      relay.run();
      assertThat(count("inbox_quarantines")).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM payment.inbox_quarantine_deliveries", String.class))
          .isEqualTo("RETRY");
      admin
          .createTopics(List.of(new NewTopic(topic, 3, (short) 1)))
          .all()
          .get(10, TimeUnit.SECONDS);
    }
    jdbc.update(
        "UPDATE payment.inbox_quarantine_deliveries SET next_run_at=now()-interval '1 second'");
    relay.run();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM payment.inbox_quarantine_deliveries", String.class))
        .isEqualTo("PUBLISHED");
  }

  @Test
  @DisplayName("격리 DB 저장 실패는 ACK하지 않고 중단하며 복구 뒤 같은 원문을 다시 처리한다")
  void stopsWithoutCommitWhenQuarantineDatabaseFails() throws Exception {
    long before;
    try (var admin = admin()) {
      before =
          admin
              .listConsumerGroupOffsets(GROUP)
              .partitionsToOffsetAndMetadata()
              .get(10, TimeUnit.SECONDS)
              .get(PARTITION)
              .offset();
    }
    jdbc.execute(
        "ALTER TABLE payment.inbox_quarantines ADD CONSTRAINT test_capture_failure CHECK(false) NOT VALID");
    try {
      listeners.start();
      kafka
          .send(new ProducerRecord<>(TOPIC, 0, "invalid", new byte[] {1}))
          .get(10, TimeUnit.SECONDS);
      await()
          .atMost(Duration.ofSeconds(15))
          .until(() -> !listeners.getListenerContainer("orderPaymentRequested").isRunning());
      try (var admin = admin()) {
        assertThat(
                admin
                    .listConsumerGroupOffsets(GROUP)
                    .partitionsToOffsetAndMetadata()
                    .get(10, TimeUnit.SECONDS)
                    .get(PARTITION)
                    .offset())
            .isEqualTo(before);
      }
      assertThat(count("inbox_quarantines")).isZero();
      assertThat(count("inbox_quarantine_deliveries")).isZero();
    } finally {
      jdbc.execute("ALTER TABLE payment.inbox_quarantines DROP CONSTRAINT test_capture_failure");
    }
    listeners.start();
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(() -> assertThat(count("inbox_quarantines")).isEqualTo(1));
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM payment." + table, Long.class);
  }

  @AfterEach
  void stopListener() throws Exception {
    var listener = listeners.getListenerContainer("orderPaymentRequested");
    if (listener.isRunning()) {
      var stopped = new CountDownLatch(1);
      listener.stop(stopped::countDown);
      assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static Admin admin() {
    return Admin.create(
        Map.of(
            "bootstrap.servers",
            KAFKA.getBootstrapServers(),
            "default.api.timeout.ms",
            10000,
            "request.timeout.ms",
            5000));
  }
}
