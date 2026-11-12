package com.commercesettle.commerce.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.DescribeConsumerGroupsResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KafkaLagMetricsTest {
  private final Admin admin = mock(Admin.class);
  private final AtomicLong end = new AtomicLong(100);
  private long committed = 100;
  private long earliest;
  private boolean advanceOnCommitRead;

  @BeforeEach
  void configureObservations() {
    var topics = mock(DescribeTopicsResult.class);
    Map<String, TopicDescription> descriptions = new HashMap<>();
    for (var subscriptions : KafkaLagMetrics.SUBSCRIPTIONS.values())
      for (String topic : subscriptions)
        descriptions.put(
            topic,
            new TopicDescription(
                topic,
                false,
                List.of(new TopicPartitionInfo(0, Node.noNode(), List.of(), List.of()))));
    when(topics.allTopicNames()).thenReturn(KafkaFuture.completedFuture(descriptions));
    when(admin.describeTopics(anyCollection())).thenReturn(topics);

    var groups = mock(DescribeConsumerGroupsResult.class);
    Map<String, KafkaFuture<ConsumerGroupDescription>> groupDescriptions = new HashMap<>();
    for (String group : KafkaLagMetrics.SUBSCRIPTIONS.keySet()) {
      var description = mock(ConsumerGroupDescription.class);
      when(description.members()).thenReturn(List.of());
      groupDescriptions.put(group, KafkaFuture.completedFuture(description));
    }
    when(groups.describedGroups()).thenReturn(groupDescriptions);
    when(admin.describeConsumerGroups(anyCollection())).thenReturn(groups);
    when(admin.listOffsets(anyMap()))
        .thenAnswer(
            invocation -> {
              Map<TopicPartition, OffsetSpec> requested = invocation.getArgument(0);
              Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> offsets =
                  new HashMap<>();
              requested.forEach(
                  (partition, spec) ->
                      offsets.put(
                          partition,
                          new ListOffsetsResult.ListOffsetsResultInfo(
                              spec instanceof OffsetSpec.EarliestSpec ? earliest : end.get(),
                              -1,
                              Optional.empty())));
              var result = mock(ListOffsetsResult.class);
              when(result.all()).thenReturn(KafkaFuture.completedFuture(offsets));
              return result;
            });
    when(admin.listConsumerGroupOffsets(anyString()))
        .thenAnswer(
            invocation -> {
              if (advanceOnCommitRead) {
                end.set(101);
                committed = 101;
              }
              Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
              for (String topic : KafkaLagMetrics.SUBSCRIPTIONS.get(invocation.getArgument(0)))
                offsets.put(new TopicPartition(topic, 0), new OffsetAndMetadata(committed));
              var result = mock(ListConsumerGroupOffsetsResult.class);
              when(result.partitionsToOffsetAndMetadata())
                  .thenReturn(KafkaFuture.completedFuture(offsets));
              return result;
            });
  }

  @Test
  @DisplayName("관측 중 새 메시지가 처리되어 commit이 전진해도 정상 그룹을 무효화하지 않는다")
  void keepsObservationWhenCommittedOffsetsAdvance() {
    advanceOnCommitRead = true;
    try (var metrics = new KafkaLagMetrics(admin)) {
      metrics.refresh();

      assertThat(metrics.snapshot()).hasSize(3);
      assertThat(metrics.snapshot().values())
          .allSatisfy(
              value -> {
                assertThat(value.records()).isZero();
                assertThat(value.observed()).isPositive();
              });
    }
  }

  @Test
  @DisplayName("여러 구독 토픽의 정상 lag를 합산한다")
  void sumsLagAcrossSubscribedTopics() {
    end.set(120);
    try (var metrics = new KafkaLagMetrics(admin)) {
      metrics.refresh();

      assertThat(metrics.snapshot()).hasSize(3);
      assertThat(metrics.snapshot().get("commerce.v1").records()).isEqualTo(20);
      assertThat(metrics.snapshot().get("payment.v1").records()).isEqualTo(40);
      assertThat(metrics.snapshot().get("settlement.v1").records()).isEqualTo(40);
    }
  }

  @Test
  @DisplayName("보존 시작점보다 뒤처진 commit은 계속 무효로 처리한다")
  void rejectsCommittedOffsetsOutsideRetention() {
    earliest = 110;
    end.set(120);
    try (var metrics = new KafkaLagMetrics(admin)) {
      metrics.refresh();

      assertThat(metrics.snapshot()).isEmpty();
    }
  }
}
