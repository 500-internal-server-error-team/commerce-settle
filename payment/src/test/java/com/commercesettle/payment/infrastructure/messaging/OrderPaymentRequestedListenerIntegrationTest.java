package com.commercesettle.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor;
import com.commercesettle.payment.infrastructure.config.KafkaListenerConfiguration;
import com.commercesettle.payment.infrastructure.config.KafkaTopicConfiguration;
import com.commercesettle.payment.infrastructure.config.OrderPaymentRequestedListenerConfiguration;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.NoOffsetForPartitionException;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
@Timeout(90)
class OrderPaymentRequestedListenerIntegrationTest {
  private static final String TOPIC = "commerce-settle.commerce.v1";
  private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
  private static final String KEY = "00000000-0000-0000-0000-000000000001";

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer("apache/kafka:4.2.2")
          .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m")
          .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(768L * 1024 * 1024))
          .withStartupTimeout(Duration.ofMinutes(2));

  @Test
  @DisplayName("초기화하지 않은 그룹을 거절하고 인계 실패의 offset을 재기동 뒤 다시 전달한다")
  @SuppressWarnings("unchecked")
  void rejectsMissingOffsetAndRedeliversFailureAfterRestart() throws Exception {
    String group = "payment-delivery-test-" + UUID.randomUUID();
    var tracer = new AtomicReference<Tracer>();
    var attemptedTraces = new CopyOnWriteArrayList<TraceContext>();
    var acceptedTraces = new CopyOnWriteArrayList<TraceContext>();
    var attempted = new CopyOnWriteArrayList<OrderPaymentRequestAcceptor.Source>();
    var accepted = new CopyOnWriteArrayList<OrderPaymentRequestAcceptor.Source>();
    byte[] first = OrderPaymentRequestedListenerTest.EVENT_JSON.getBytes(StandardCharsets.UTF_8);
    byte[] second =
        OrderPaymentRequestedListenerTest.EVENT_JSON
            .replace("00000000-0000-0000-0000-000000000006", "00000000-0000-0000-0000-000000000007")
            .getBytes(StandardCharsets.UTF_8);

    var firstRecord = new ProducerRecord<>(TOPIC, 0, KEY, first);
    var secondRecord = new ProducerRecord<>(TOPIC, 0, KEY, second);

    try (var admin =
        Admin.create(
            Map.of(
                "bootstrap.servers",
                KAFKA.getBootstrapServers(),
                "default.api.timeout.ms",
                10000,
                "request.timeout.ms",
                5000))) {
      admin
          .createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1)))
          .all()
          .get(10, TimeUnit.SECONDS);
      var consumerProperties = new HashMap<String, Object>();
      consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
      consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, group);
      consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
      consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
      consumerProperties.put(
          ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
      consumerProperties.put(
          ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
      try (var consumer = new KafkaConsumer<String, byte[]>(consumerProperties)) {
        consumer.assign(List.of(PARTITION));
        assertThatThrownBy(() -> consumer.poll(Duration.ofSeconds(5)))
            .isInstanceOf(NoOffsetForPartitionException.class);
      }
      Map<TopicPartition, OffsetAndMetadata> initial = new HashMap<>();
      for (int partition = 0; partition < 3; partition++) {
        initial.put(new TopicPartition(TOPIC, partition), new OffsetAndMetadata(0));
      }
      admin.alterConsumerGroupOffsets(group, initial).all().get(10, TimeUnit.SECONDS);

      context(group)
          .withBean(
              OrderPaymentRequestAcceptor.class,
              () ->
                  (event, source) -> {
                    attemptedTraces.add(tracer.get().currentSpan().context());
                    attempted.add(source);
                    throw new IllegalStateException("simulated durable handoff failure");
                  })
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                tracer.set(context.getBean(Tracer.class));
                KafkaTemplate<String, byte[]> template = context.getBean(KafkaTemplate.class);
                context.getBean(KafkaListenerEndpointRegistry.class).start();
                template.send(firstRecord).get(10, TimeUnit.SECONDS);
                template.send(secondRecord).get(10, TimeUnit.SECONDS);
                assertThat(tracer.get().currentSpan()).isNull();
                var container =
                    context
                        .getBean(KafkaListenerEndpointRegistry.class)
                        .getListenerContainer("orderPaymentRequested");
                await()
                    .atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                        () -> {
                          assertThat(attempted).hasSize(1);
                          assertTraceParent(attemptedTraces.getFirst(), firstRecord);
                          assertThat(container.isRunning()).isFalse();
                          assertThat(container.isInExpectedState()).isFalse();
                        });
                assertThat(
                        admin
                            .listConsumerGroupOffsets(group)
                            .partitionsToOffsetAndMetadata()
                            .get(10, TimeUnit.SECONDS)
                            .get(PARTITION)
                            .offset())
                    .isZero();
              });

      context(group)
          .withBean(
              OrderPaymentRequestAcceptor.class,
              () ->
                  (event, source) -> {
                    acceptedTraces.add(tracer.get().currentSpan().context());
                    accepted.add(source);
                  })
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                tracer.set(context.getBean(Tracer.class));
                context.getBean(KafkaListenerEndpointRegistry.class).start();
                await()
                    .atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                        () -> {
                          assertThat(accepted).hasSize(2);
                          assertThat(
                                  admin
                                      .listConsumerGroupOffsets(group)
                                      .partitionsToOffsetAndMetadata()
                                      .get(10, TimeUnit.SECONDS)
                                      .get(PARTITION)
                                      .offset())
                              .isEqualTo(2);
                        });
                assertThat(accepted)
                    .extracting(OrderPaymentRequestAcceptor.Source::offset)
                    .containsExactly(0L, 1L);
                assertTraceParent(acceptedTraces.getFirst(), firstRecord);
                assertTraceParent(acceptedTraces.get(1), secondRecord);
                assertThat(acceptedTraces.getFirst().traceId())
                    .isNotEqualTo(acceptedTraces.get(1).traceId());
                assertThat(tracer.get().currentSpan()).isNull();
                assertThat(accepted.getFirst().body()).containsExactly(first);
                assertThat(accepted.get(1).body()).containsExactly(second);
                assertThat(attempted.getFirst().body()).containsExactly(accepted.getFirst().body());
              });
    }
  }

  private static void assertTraceParent(
      TraceContext actual, ProducerRecord<String, byte[]> record) {
    var header = record.headers().lastHeader("traceparent");
    assertThat(header).isNotNull();
    var parts = new String(header.value(), StandardCharsets.UTF_8).split("-");
    assertThat(parts).hasSize(4);
    assertThat(actual.traceId()).isEqualTo(parts[1]);
    assertThat(actual.parentId()).isEqualTo(parts[2]);
  }

  private ApplicationContextRunner context(String group) {
    return new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(
                ObservationAutoConfiguration.class,
                OpenTelemetryAutoConfiguration.class,
                MicrometerTracingAutoConfiguration.class,
                OpenTelemetryTracingAutoConfiguration.class,
                KafkaAutoConfiguration.class,
                JacksonAutoConfiguration.class))
        .withUserConfiguration(
            KafkaTopicConfiguration.class,
            KafkaListenerConfiguration.class,
            OrderPaymentRequestedListenerConfiguration.class)
        .withPropertyValues(
            "spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
            "spring.kafka.consumer.group-id=" + group,
            "management.tracing.sampling.probability=1.0",
            "spring.kafka.admin.fail-fast=true",
            "spring.kafka.listener.auto-startup=false",
            "app.kafka.order-payment-requested.enabled=true");
  }
}
