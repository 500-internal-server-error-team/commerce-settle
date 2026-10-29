package com.commercesettle.commerce.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.commerce.application.port.OrderEventOutbox;
import com.commercesettle.commerce.application.service.OutboxDeliveryService;
import com.commercesettle.commerce.infrastructure.config.KafkaListenerConfiguration;
import com.commercesettle.commerce.infrastructure.config.KafkaTopicConfiguration;
import com.commercesettle.commerce.infrastructure.config.OutboxConfiguration;
import com.commercesettle.commerce.infrastructure.persistence.outbox.JdbcOrderEventOutbox;
import com.commercesettle.commerce.infrastructure.persistence.outbox.JdbcOutboxStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
@Timeout(90)
class OutboxRelayIntegrationTest {
  private static final String TOPIC = "commerce-settle.commerce.v1";

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer("apache/kafka:4.2.2")
          .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m")
          .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(768L * 1024 * 1024))
          .withStartupTimeout(Duration.ofMinutes(2));

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3")
          .withCommand("postgres", "-c", "max_connections=20")
          .withCreateContainerCmdModifier(
              cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024));

  @Test
  @DisplayName("Outbox 원문과 실제 Kafka key·위치 및 토픽 설정을 대조한다")
  void publishesStoredBytesAndPersistsAcknowledgedPosition() {
    context(true)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var jdbc = context.getBean(JdbcTemplate.class);
              byte[] body =
                  append(
                      context.getBean(OrderEventOutbox.class),
                      context.getBean(PlatformTransactionManager.class),
                      context.getBean(ObjectMapper.class),
                      jdbc);
              var properties = consumerProperties(context.getBean(KafkaProperties.class));
              String group = properties.get(ConsumerConfig.GROUP_ID_CONFIG).toString();

              try (var admin = admin();
                  var consumer = new KafkaConsumer<String, byte[]>(properties)) {
                var topics = context.getBeansOfType(NewTopic.class).values();
                assertThat(topics).hasSize(3);
                for (var topic : topics) {
                  var description =
                      admin
                          .describeTopics(List.of(topic.name()))
                          .allTopicNames()
                          .get(10, TimeUnit.SECONDS)
                          .get(topic.name());
                  assertThat(description.partitions())
                      .hasSize(3)
                      .allSatisfy(partition -> assertThat(partition.replicas()).hasSize(1));
                  var resource = new ConfigResource(ConfigResource.Type.TOPIC, topic.name());
                  var actual =
                      admin
                          .describeConfigs(List.of(resource))
                          .all()
                          .get(10, TimeUnit.SECONDS)
                          .get(resource);
                  assertThat(actual.get("cleanup.policy").value()).isEqualTo("delete");
                  assertThat(actual.get("retention.bytes").value()).isEqualTo("-1");
                  assertThat(actual.get("retention.ms").value())
                      .isEqualTo(topic.name().endsWith(".dlt") ? "604800000" : "86400000");
                }
                Map<TopicPartition, OffsetAndMetadata> initial = new HashMap<>();
                for (int partition = 0; partition < 3; partition++) {
                  initial.put(new TopicPartition(TOPIC, partition), new OffsetAndMetadata(0));
                }
                admin.alterConsumerGroupOffsets(group, initial).all().get(10, TimeUnit.SECONDS);
                consumer.assign(initial.keySet());

                // append의 AFTER_COMMIT 신호가 실제 구성의 Relay를 깨운다.
                awaitPublished(jdbc);
                var received = readBody(consumer, body, 1).getFirst();
                var trace = received.headers().lastHeader("traceparent");
                assertThat(trace).isNotNull();
                assertThat(new String(trace.value(), java.nio.charset.StandardCharsets.UTF_8))
                    .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");

                assertThat(received.key())
                    .isEqualTo(
                        jdbc.queryForObject(
                            "SELECT message_key FROM commerce.outbox", String.class));
                assertThat(
                        jdbc.queryForObject(
                            "SELECT published_partition FROM commerce.outbox", Integer.class))
                    .isEqualTo(received.partition());
                assertThat(
                        jdbc.queryForObject(
                            "SELECT published_offset FROM commerce.outbox", Long.class))
                    .isEqualTo(received.offset());
                assertThat(
                        jdbc.queryForObject(
                            "SELECT published_at IS NOT NULL FROM commerce.outbox", Boolean.class))
                    .isTrue();
                var partition = new TopicPartition(TOPIC, received.partition());
                assertThat(
                        admin
                            .listConsumerGroupOffsets(group)
                            .partitionsToOffsetAndMetadata()
                            .get(10, TimeUnit.SECONDS)
                            .get(partition)
                            .offset())
                    .isZero();
                consumer.commitSync(
                    Map.of(partition, new OffsetAndMetadata(received.offset() + 1)));
                assertThat(
                        admin
                            .listConsumerGroupOffsets(group)
                            .partitionsToOffsetAndMetadata()
                            .get(10, TimeUnit.SECONDS)
                            .get(partition)
                            .offset())
                    .isEqualTo(received.offset() + 1);
              }
            });
  }

  @Test
  @DisplayName("Kafka ACK 뒤 DB 저장이 실패하면 원문을 보존하고 임대 회수 후 재발행한다")
  @SuppressWarnings("unchecked")
  void replaysOriginalBytesAfterAcknowledgedResultRollsBack() {
    context(false)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var jdbc = context.getBean(JdbcTemplate.class);
              var service = context.getBean(OutboxDeliveryService.class);
              KafkaTemplate<String, byte[]> template = context.getBean(KafkaTemplate.class);
              byte[] body =
                  append(
                      context.getBean(OrderEventOutbox.class),
                      context.getBean(PlatformTransactionManager.class),
                      context.getBean(ObjectMapper.class),
                      jdbc);
              var attempted = new CountDownLatch(1);
              var observed = mock(OutboxDeliveryService.class, delegatesTo(service));
              doAnswer(
                      invocation -> {
                        try {
                          return service.markPublished(
                              invocation.getArgument(0), invocation.getArgument(1));
                        } finally {
                          attempted.countDown();
                        }
                      })
                  .when(observed)
                  .markPublished(any(), any());
              jdbc.execute(
                  "ALTER TABLE commerce.outbox ADD CONSTRAINT test_reject_publication CHECK(status"
                      + " <> 'PUBLISHED') NOT VALID");

              try (var coordinator = Executors.newSingleThreadExecutor();
                  var executor = Executors.newSingleThreadExecutor();
                  var consumer =
                      new KafkaConsumer<String, byte[]>(
                          consumerProperties(context.getBean(KafkaProperties.class)))) {
                var partitions =
                    List.of(
                        new TopicPartition(TOPIC, 0),
                        new TopicPartition(TOPIC, 1),
                        new TopicPartition(TOPIC, 2));
                consumer.assign(partitions);
                consumer.seekToBeginning(partitions);
                try {
                  var firstRelay =
                      new OutboxRelay(observed, template, 1, 240000, 10, coordinator, executor);
                  firstRelay.wakeUp();
                  assertThat(attempted.await(20, TimeUnit.SECONDS)).isTrue();
                  executor.submit(() -> {}).get(10, TimeUnit.SECONDS);
                  firstRelay.stop();
                  assertThat(
                          jdbc.queryForObject("SELECT status FROM commerce.outbox", String.class))
                      .isEqualTo("RUNNING");
                  assertThat(
                          jdbc.queryForObject(
                              "SELECT published_offset IS NULL FROM commerce.outbox",
                              Boolean.class))
                      .isTrue();
                  assertThat(
                          jdbc.queryForObject("SELECT raw_body FROM commerce.outbox", byte[].class))
                      .containsExactly(body);
                } finally {
                  jdbc.execute(
                      "ALTER TABLE commerce.outbox DROP CONSTRAINT test_reject_publication");
                }
                jdbc.update(
                    "UPDATE commerce.outbox SET lease_until=clock_timestamp()-interval '1 second'");

                var recoveredRelay =
                    new OutboxRelay(service, template, 1, 240000, 10, coordinator, executor);
                recoveredRelay.recover();
                awaitPublished(jdbc);
                var received = readBody(consumer, body, 2);

                assertThat(received)
                    .extracting(ConsumerRecord::key)
                    .containsOnly(
                        jdbc.queryForObject(
                            "SELECT message_key FROM commerce.outbox", String.class));
                assertThat(received.get(1).offset()).isGreaterThan(received.getFirst().offset());
                assertThat(
                        jdbc.queryForObject(
                            "SELECT published_offset FROM commerce.outbox", Long.class))
                    .isEqualTo(received.get(1).offset());
                assertThat(
                        jdbc.queryForObject("SELECT attempts FROM commerce.outbox", Integer.class))
                    .isEqualTo(2);
              }
            });
  }

  @Test
  @DisplayName("롤백한 Outbox는 커밋 알림이나 발행 작업을 만들지 않는다")
  void doesNotWakeRelayBeforeCommitOrAfterRollback() {
    context(true)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var jdbc = context.getBean(JdbcTemplate.class);
              var manager = context.getBean(PlatformTransactionManager.class);
              jdbc.update("DELETE FROM commerce.outbox");
              var coordinator =
                  context
                      .getBean("outboxCoordinator", ThreadPoolTaskExecutor.class)
                      .getThreadPoolExecutor();
              long submitted = coordinator.getTaskCount();

              new TransactionTemplate(manager)
                  .executeWithoutResult(
                      status -> {
                        try {
                          append(
                              context.getBean(OrderEventOutbox.class),
                              manager,
                              context.getBean(ObjectMapper.class),
                              jdbc);
                        } catch (Exception exception) {
                          throw new IllegalStateException(exception);
                        }
                        assertThat(coordinator.getTaskCount()).isEqualTo(submitted);
                        status.setRollbackOnly();
                      });

              assertThat(coordinator.getTaskCount()).isEqualTo(submitted);
              assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.outbox", Long.class))
                  .isZero();
            });
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("커밋 신호를 놓친 대기 이벤트도 시작 또는 복구 조회로 발행한다")
  void recoversStoredEventWithoutLocalSignal(boolean startup) {
    context(true)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var jdbc = context.getBean(JdbcTemplate.class);
              var writer =
                  new JdbcOrderEventOutbox(
                      context.getBean(NamedParameterJdbcTemplate.class),
                      context.getBean(ObjectMapper.class),
                      event -> {});
              byte[] body =
                  append(
                      writer,
                      context.getBean(PlatformTransactionManager.class),
                      context.getBean(ObjectMapper.class),
                      jdbc);
              assertThat(jdbc.queryForObject("SELECT status FROM commerce.outbox", String.class))
                  .isEqualTo("PENDING");

              if (startup) {
                context.publishEvent(
                    new ApplicationReadyEvent(
                        new SpringApplication(Persistence.class),
                        new String[0],
                        context.getSourceApplicationContext(),
                        Duration.ZERO));
              } else {
                context.getBean(OutboxRelay.class).recover();
              }
              awaitPublished(jdbc);

              assertThat(jdbc.queryForObject("SELECT raw_body FROM commerce.outbox", byte[].class))
                  .containsExactly(body);
              assertThat(jdbc.queryForObject("SELECT attempts FROM commerce.outbox", Integer.class))
                  .isEqualTo(1);
            });
  }

  private byte[] append(
      OrderEventOutbox writer,
      PlatformTransactionManager manager,
      ObjectMapper mapper,
      JdbcTemplate jdbc)
      throws Exception {
    assertThat(manager).isInstanceOf(JpaTransactionManager.class);
    jdbc.update("DELETE FROM commerce.outbox");
    var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(EVENT_JSON);
    json.put("eventId", UUID.randomUUID().toString());
    EventEnvelope<OrderPaymentRequestedPayload> event =
        mapper.convertValue(json, new TypeReference<>() {});
    new TransactionTemplate(manager)
        .executeWithoutResult(status -> writer.appendPaymentRequested(event));
    return jdbc.queryForObject("SELECT raw_body FROM commerce.outbox", byte[].class);
  }

  private static void awaitPublished(JdbcTemplate jdbc) {
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(jdbc.queryForObject("SELECT status FROM commerce.outbox", String.class))
                    .isEqualTo("PUBLISHED"));
  }

  private static List<ConsumerRecord<String, byte[]>> readBody(
      KafkaConsumer<String, byte[]> consumer, byte[] body, int expected) {
    var records = new ArrayList<ConsumerRecord<String, byte[]>>();
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (records.size() < expected && System.nanoTime() < deadline) {
      consumer
          .poll(Duration.ofMillis(250))
          .forEach(
              record -> {
                if (Arrays.equals(record.value(), body)) {
                  records.add(record);
                }
              });
    }
    assertThat(records).hasSize(expected);
    return records;
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

  private static Map<String, Object> consumerProperties(KafkaProperties properties) {
    var result = new HashMap<String, Object>(properties.buildConsumerProperties());
    result.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-delivery-test-" + UUID.randomUUID());
    assertThat(result)
        .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
        .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none")
        .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
    return result;
  }

  private ApplicationContextRunner context(boolean enabled) {
    return new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(
                ObservationAutoConfiguration.class,
                OpenTelemetryAutoConfiguration.class,
                MicrometerTracingAutoConfiguration.class,
                OpenTelemetryTracingAutoConfiguration.class,
                DataSourceAutoConfiguration.class,
                JdbcTemplateAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                TransactionAutoConfiguration.class,
                FlywayAutoConfiguration.class,
                KafkaAutoConfiguration.class,
                JacksonAutoConfiguration.class))
        .withUserConfiguration(
            Persistence.class,
            OutboxConfiguration.class,
            KafkaTopicConfiguration.class,
            KafkaListenerConfiguration.class,
            JdbcOrderEventOutbox.class,
            JdbcOutboxStore.class,
            OutboxDeliveryService.class)
        .withPropertyValues(
            "spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
            "management.tracing.sampling.probability=1.0",
            "spring.kafka.admin.fail-fast=true",
            "app.outbox.enabled=" + enabled,
            "app.outbox.max-in-flight=1",
            "app.outbox.recovery-interval-ms=3600000",
            "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "spring.datasource.username=" + POSTGRES.getUsername(),
            "spring.datasource.password=" + POSTGRES.getPassword(),
            "spring.datasource.hikari.maximum-pool-size=4",
            "spring.datasource.hikari.minimum-idle=0");
  }

  @Configuration
  @EntityScan("com.commercesettle.commerce.domain")
  @EnableTransactionManagement
  static class Persistence {}

  private static final String EVENT_JSON =
      """
      {"eventId":"00000000-0000-0000-0000-000000000006","eventType":"OrderPaymentRequested",
       "eventVersion":1,"occurredAt":"2026-01-01T00:00:00.123456789Z",
       "aggregateId":"00000000-0000-0000-0000-000000000001","aggregateVersion":0,
       "payload":{"orderId":"00000000-0000-0000-0000-000000000001",
        "buyerId":"00000000-0000-0000-0000-000000000002","sellerId":"00000000-0000-0000-0000-000000000003",
        "paymentCycleId":"00000000-0000-0000-0000-000000000004","productId":"00000000-0000-0000-0000-000000000005",
        "orderVersion":0,"status":"PAYMENT_PENDING","policyVersion":"test-v1",
        "paymentWaitUntil":"2026-01-01T00:10:00Z","externalMethod":null,"methodAccountRef":null,
        "grossAmount":10000,"couponAmount":0,"totalAmount":10000,"mileageAmount":10000,
        "externalAmount":0,"sellerCouponAmount":0,"platformCouponAmount":0,"settlementBaseAmount":10000}}
      """;
}
