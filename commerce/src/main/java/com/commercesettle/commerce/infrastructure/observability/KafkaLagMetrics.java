package com.commercesettle.commerce.infrastructure.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;

/** 명세의 구독 토픽 전체를 관측한다. 초기 offset·파티션 하나라도 없으면 해당 그룹은 무효다. */
public final class KafkaLagMetrics implements AutoCloseable {
  static final Map<String, Set<String>> SUBSCRIPTIONS =
      Map.of(
          "commerce.v1", Set.of("commerce-settle.payment.v1"),
          "payment.v1", Set.of("commerce-settle.commerce.v1", "commerce-settle.settlement.v1"),
          "settlement.v1", Set.of("commerce-settle.payment.v1", "commerce-settle.commerce.v1"));
  private final Admin admin;
  private final AtomicReference<Map<String, Lag>> snapshot = new AtomicReference<>(Map.of());
  private final ScheduledExecutorService observer =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("financial-kafka-observer-", 0).daemon(true).factory());

  public KafkaLagMetrics(Admin admin) {
    this.admin = admin;
  }

  public void start() {
    observer.scheduleWithFixedDelay(this::refresh, 0, 15, TimeUnit.SECONDS);
  }

  public Map<String, Lag> snapshot() {
    return snapshot.get();
  }

  public void refresh() {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    double observed = Instant.now().toEpochMilli() / 1000.0;
    try {
      Set<String> topics = new HashSet<>();
      SUBSCRIPTIONS.values().forEach(topics::addAll);
      var descriptions = await(admin.describeTopics(topics).allTopicNames(), deadline);
      Map<TopicPartition, OffsetSpec> ends = new HashMap<>();
      Map<TopicPartition, OffsetSpec> starts = new HashMap<>();
      descriptions.forEach(
          (topic, value) ->
              value
                  .partitions()
                  .forEach(
                      partition -> {
                        var key = new TopicPartition(topic, partition.partition());
                        ends.put(key, OffsetSpec.latest());
                        starts.put(key, OffsetSpec.earliest());
                      }));
      if (descriptions.size() != topics.size() || ends.isEmpty())
        throw new IllegalStateException("Missing topics");
      var startOffsets = await(admin.listOffsets(starts).all(), deadline);
      var groups = admin.describeConsumerGroups(SUBSCRIPTIONS.keySet());
      Map<String, Map<TopicPartition, OffsetAndMetadata>> committedByGroup = new HashMap<>();
      for (var subscription : SUBSCRIPTIONS.entrySet()) {
        String group = subscription.getKey();
        try {
          var description = await(groups.describedGroups().get(group), deadline);
          for (var member : description.members())
            for (var partition : member.assignment().topicPartitions()) {
              if (!subscription.getValue().contains(partition.topic()))
                throw new IllegalStateException("Unexpected subscription");
            }
          committedByGroup.put(
              group,
              await(
                  admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata(), deadline));
        } catch (Exception exception) {
          if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
          // 다른 그룹의 성공은 유지하고 실패한 그룹의 원본 시각은 내보내지 않는다.
        }
      }
      // commit 이후 끝 offset을 읽어 정상적인 소비 진행을 범위 오류로 오인하지 않는다.
      var endOffsets = await(admin.listOffsets(ends).all(), deadline);
      Map<String, Lag> current = new HashMap<>();
      for (var groupOffsets : committedByGroup.entrySet()) {
        String group = groupOffsets.getKey();
        try {
          var committed = groupOffsets.getValue();
          long lag = 0;
          int partitions = 0;
          for (var partition : ends.keySet()) {
            if (!SUBSCRIPTIONS.get(group).contains(partition.topic())) continue;
            var offset = committed.get(partition);
            var end = endOffsets.get(partition);
            var start = startOffsets.get(partition);
            if (offset == null
                || end == null
                || start == null
                || offset.offset() < start.offset()
                || offset.offset() > end.offset())
              throw new IllegalStateException("Missing or out of range offset");
            lag = Math.addExact(lag, end.offset() - offset.offset());
            partitions++;
          }
          if (partitions == 0) throw new IllegalStateException("Missing partitions");
          current.put(group, new Lag(lag, observed));
        } catch (Exception exception) {
          if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
          // 다른 그룹의 성공은 유지하고 실패한 그룹의 원본 시각은 내보내지 않는다.
        }
      }
      snapshot.set(Map.copyOf(current));
    } catch (Exception exception) {
      if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
      snapshot.set(Map.of());
    }
  }

  private static <T> T await(KafkaFuture<T> future, long deadline) throws Exception {
    return future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
  }

  @Override
  public void close() {
    observer.shutdownNow();
    admin.close(Duration.ofSeconds(2));
  }

  public record Lag(double records, double observed) {}
}
