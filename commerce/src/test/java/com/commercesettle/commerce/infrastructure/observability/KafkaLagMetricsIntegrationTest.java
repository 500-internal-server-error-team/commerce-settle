package com.commercesettle.commerce.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
@Timeout(120)
class KafkaLagMetricsIntegrationTest {
  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer("apache/kafka:4.2.2")
          .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m")
          .withCreateContainerCmdModifier(
              command -> command.getHostConfig().withMemory(768L * 1024 * 1024))
          .withStartupTimeout(Duration.ofMinutes(2));

  @Test
  @DisplayName("세 금융 그룹의 모든 파티션을 합산하고 초기 offset·추가 파티션 누락을 무효로 처리한다")
  void observesAllSubscribedPartitionsWithoutInventingMissingOffsets() throws Exception {
    Map<String, Object> settings =
        Map.of(
            "bootstrap.servers",
            KAFKA.getBootstrapServers(),
            "default.api.timeout.ms",
            4000,
            "request.timeout.ms",
            3000);
    try (var admin = Admin.create(settings);
        var metrics = new KafkaLagMetrics(Admin.create(settings))) {
      var topics =
          java.util.List.of(
              "commerce-settle.commerce.v1",
              "commerce-settle.payment.v1",
              "commerce-settle.settlement.v1");
      admin
          .createTopics(topics.stream().map(topic -> new NewTopic(topic, 3, (short) 1)).toList())
          .all()
          .get(15, TimeUnit.SECONDS);
      metrics.refresh();
      assertThat(metrics.snapshot()).isEmpty();

      for (var entry : KafkaLagMetrics.SUBSCRIPTIONS.entrySet()) {
        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        for (String topic : entry.getValue())
          for (int partition = 0; partition < 3; partition++)
            offsets.put(new TopicPartition(topic, partition), new OffsetAndMetadata(0));
        admin.alterConsumerGroupOffsets(entry.getKey(), offsets).all().get(15, TimeUnit.SECONDS);
      }
      metrics.refresh();
      assertThat(metrics.snapshot()).hasSize(3);
      assertThat(metrics.snapshot().values())
          .allSatisfy(
              value -> {
                assertThat(value.records()).isZero();
                assertThat(value.observed()).isPositive();
              });
      var initial = metrics.snapshot();
      try (var producer =
          new KafkaProducer<String, String>(
              Map.of("bootstrap.servers", KAFKA.getBootstrapServers()),
              new StringSerializer(),
              new StringSerializer())) {
        for (int partition = 0; partition < 3; partition++)
          for (int i = 0; i <= partition; i++)
            producer
                .send(new ProducerRecord<>(topics.getFirst(), partition, "key", "value"))
                .get(10, TimeUnit.SECONDS);
      }
      metrics.refresh();
      assertThat(metrics.snapshot().get("payment.v1").records()).isEqualTo(6);
      assertThat(metrics.snapshot().get("settlement.v1").records()).isEqualTo(6);
      assertThat(metrics.snapshot().get("commerce.v1").records()).isZero();
      assertThat(initial.get("payment.v1").records()).isZero();

      admin
          .createPartitions(Map.of(topics.getFirst(), NewPartitions.increaseTo(4)))
          .all()
          .get(15, TimeUnit.SECONDS);
      metrics.refresh();
      assertThat(metrics.snapshot()).containsOnlyKeys("commerce.v1");
      for (String group : java.util.List.of("payment.v1", "settlement.v1"))
        admin
            .alterConsumerGroupOffsets(
                group, Map.of(new TopicPartition(topics.getFirst(), 3), new OffsetAndMetadata(0)))
            .all()
            .get(15, TimeUnit.SECONDS);
      metrics.refresh();
      assertThat(metrics.snapshot()).hasSize(3);
      KAFKA.stop();
      metrics.refresh();
      assertThat(metrics.snapshot()).isEmpty();
    }
  }
}
