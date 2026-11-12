package com.commercesettle.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.event.PaymentCancellationRequestedPayload;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor;
import com.commercesettle.payment.infrastructure.config.KafkaListenerConfiguration;
import com.commercesettle.payment.infrastructure.config.SettlementReplyListenerConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

/** 실제 Kafka의 Settlement 토픽에서 응답을 받아 인계한 뒤에만 offset을 커밋하는지 확인한다. */
@Testcontainers
@Timeout(90)
class SettlementReplyListenerIntegrationTest {
  private static final String TOPIC = "commerce-settle.settlement.v1";
  private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
  private static final String PAYMENT_ID = "00000000-0000-0000-0000-0000000000b1";

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer("apache/kafka:4.2.2")
          .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m")
          .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(768L * 1024 * 1024))
          .withStartupTimeout(Duration.ofMinutes(2));

  @Test
  @DisplayName("인계 실패의 응답은 재기동 뒤 다시 받고, 받지 않는 종류는 인계 없이 offset만 넘긴다")
  @SuppressWarnings("unchecked")
  void redeliversFailedReplyAfterRestartAndSkipsOtherEvents() throws Exception {
    String group = "payment-settlement-replies-" + UUID.randomUUID();
    byte[] reserved = SettlementReplyListenerTest.RESERVED_JSON.getBytes(StandardCharsets.UTF_8);
    byte[] notice =
        """
        {"eventId":"00000000-0000-0000-0000-0000000000e1","eventType":"SettlementNoticeRequested",
         "eventVersion":1,"occurredAt":"2026-01-01T00:00:00Z",
         "aggregateId":"00000000-0000-0000-0000-0000000000e2","aggregateVersion":0,
         "payload":{"kind":"SETTLEMENT_CREATED"}}
        """
            .getBytes(StandardCharsets.UTF_8);
    byte[] rejected =
        SettlementReplyListenerTest.RESERVED_JSON
            .replace("0000000000d1", "0000000000d2")
            .replace("\"RESERVED\"", "\"REJECTED\"")
            .replace("\"00000000-0000-0000-0000-0000000000c1\"", "null")
            .replace("\"reasonCode\":null", "\"reasonCode\":\"PAYOUT_RESERVED\"")
            .getBytes(StandardCharsets.UTF_8);
    var attempted = new CopyOnWriteArrayList<OrderPaymentRequestAcceptor.Source>();
    var accepted = new CopyOnWriteArrayList<OrderPaymentRequestAcceptor.Source>();

    try (var admin =
        Admin.create(
            Map.of(
                "bootstrap.servers",
                KAFKA.getBootstrapServers(),
                "default.api.timeout.ms",
                10000,
                "request.timeout.ms",
                5000))) {
      // Settlement가 만드는 토픽이다. Payment는 토픽을 만들지 않으므로 시험이 먼저 만든다.
      admin
          .createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1)))
          .all()
          .get(10, TimeUnit.SECONDS);
      Map<TopicPartition, OffsetAndMetadata> initial = new HashMap<>();
      for (int partition = 0; partition < 3; partition++) {
        initial.put(new TopicPartition(TOPIC, partition), new OffsetAndMetadata(0));
      }
      admin.alterConsumerGroupOffsets(group, initial).all().get(10, TimeUnit.SECONDS);

      context(group)
          .withBean(
              OrderPaymentRequestAcceptor.class,
              () ->
                  acceptor(
                      source -> {
                        attempted.add(source);
                        throw new IllegalStateException("simulated durable handoff failure");
                      }))
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                KafkaTemplate<String, byte[]> template = context.getBean(KafkaTemplate.class);
                context.getBean(KafkaListenerEndpointRegistry.class).start();
                template
                    .send(new ProducerRecord<>(TOPIC, 0, PAYMENT_ID, reserved))
                    .get(10, TimeUnit.SECONDS);
                template
                    .send(new ProducerRecord<>(TOPIC, 0, "seller", notice))
                    .get(10, TimeUnit.SECONDS);
                template
                    .send(new ProducerRecord<>(TOPIC, 0, PAYMENT_ID, rejected))
                    .get(10, TimeUnit.SECONDS);
                var container =
                    context
                        .getBean(KafkaListenerEndpointRegistry.class)
                        .getListenerContainer("settlementReplies");
                await()
                    .atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                        () -> {
                          assertThat(attempted).hasSize(1);
                          assertThat(container.isRunning()).isFalse();
                        });
                assertThat(committed(admin, group)).isZero();
              });

      context(group)
          .withBean(OrderPaymentRequestAcceptor.class, () -> acceptor(accepted::add))
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                context.getBean(KafkaListenerEndpointRegistry.class).start();
                await()
                    .atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                        () -> {
                          assertThat(accepted).hasSize(2);
                          assertThat(committed(admin, group)).isEqualTo(3);
                        });
                assertThat(accepted)
                    .extracting(OrderPaymentRequestAcceptor.Source::offset)
                    .containsExactly(0L, 2L);
                assertThat(accepted.getFirst().body()).containsExactly(reserved);
                assertThat(accepted.get(1).body()).containsExactly(rejected);
                assertThat(attempted.getFirst().body()).containsExactly(reserved);
              });
    }
  }

  private static long committed(Admin admin, String group) throws Exception {
    return admin
        .listConsumerGroupOffsets(group)
        .partitionsToOffsetAndMetadata()
        .get(10, TimeUnit.SECONDS)
        .get(PARTITION)
        .offset();
  }

  private ApplicationContextRunner context(String group) {
    return new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(KafkaAutoConfiguration.class, JacksonAutoConfiguration.class))
        .withUserConfiguration(
            KafkaListenerConfiguration.class, SettlementReplyListenerConfiguration.class)
        .withPropertyValues(
            "spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
            "spring.kafka.consumer.group-id=" + group,
            "spring.kafka.listener.auto-startup=false",
            "app.kafka.settlement-replies.enabled=true");
  }

  /** 환불 이벤트 인계만 기록하고 다른 접수는 쓰지 않는 시험용 접수기. */
  private static OrderPaymentRequestAcceptor acceptor(
      Consumer<OrderPaymentRequestAcceptor.Source> handle) {
    return new OrderPaymentRequestAcceptor() {
      @Override
      public void accept(EventEnvelope<OrderPaymentRequestedPayload> event, Source source) {
        throw new AssertionError("not a settlement reply");
      }

      @Override
      public void acceptCancellation(
          EventEnvelope<PaymentCancellationRequestedPayload> event, Source source) {
        throw new AssertionError("not a settlement reply");
      }

      @Override
      public void acceptRefundEvent(EventEnvelope<?> event, Source source) {
        handle.accept(source);
      }
    };
  }
}
