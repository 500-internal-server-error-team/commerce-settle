package com.commercesettle.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercesettle.observability.AsyncObservation;
import com.commercesettle.payment.application.port.OutboxStore.Delivery;
import com.commercesettle.payment.application.port.OutboxStore.Publication;
import com.commercesettle.payment.application.port.OutboxStore.Published;
import com.commercesettle.payment.application.service.OutboxDeliveryService;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {
  @Mock private OutboxDeliveryService service;
  @Mock private KafkaTemplate<String, byte[]> template;
  private final ArrayDeque<Runnable> coordinator = new ArrayDeque<>();
  private final ArrayDeque<Runnable> work = new ArrayDeque<>();

  @Test
  @DisplayName("커밋 신호를 합치고 슬롯 반환 때 복구 주기를 기다리지 않고 다음 원문을 발행한다")
  void coalescesWakeupsAndRefillsSlotsAfterPersistingResults() {
    var first = delivery();
    var second = delivery();
    var firstReply = new CompletableFuture<SendResult<String, byte[]>>();
    var secondReply = new CompletableFuture<SendResult<String, byte[]>>();
    when(service.claim(1, 240000, 10)).thenReturn(List.of(first), List.of(second), List.of());
    when(template.send(anyString(), anyString(), any(byte[].class)))
        .thenReturn(firstReply, secondReply);
    var relay = relay();

    for (int i = 0; i < 100; i++) {
      relay.wakeUp();
    }
    assertThat(coordinator).hasSize(1);
    verifyNoInteractions(service, template);
    coordinator.remove().run();
    work.remove().run();
    verify(service, never()).markPublished(any());
    firstReply.complete(result(first, 73));
    verify(service, never()).markPublished(any());
    work.remove().run();
    verify(service).markPublished(List.of(new Published(first, new Publication("topic", 2, 73))));
    coordinator.remove().run();
    work.remove().run();
    secondReply.complete(result(second, 74));
    work.remove().run();
    coordinator.remove().run();

    verify(template, times(2)).send("topic", "key", new byte[] {1, 2, 3});
    verify(service).markPublished(List.of(new Published(second, new Publication("topic", 2, 74))));
    verify(service, times(3)).claim(1, 240000, 10);
    assertThat(coordinator).isEmpty();
    assertThat(work).isEmpty();
  }

  @Test
  @DisplayName("마지막 선점이 끝나는 동안 들어온 신호도 다음 조회로 처리한다")
  void observesWakeupArrivingBeforeDrainFinishes() {
    var relay = relay();
    when(service.claim(1, 240000, 10))
        .thenAnswer(
            invocation -> {
              relay.wakeUp();
              return List.of();
            })
        .thenReturn(List.of());

    relay.wakeUp();
    coordinator.remove().run();

    verify(service, times(2)).claim(1, 240000, 10);
    assertThat(coordinator).isEmpty();
  }

  @Test
  @DisplayName("실제 실행 중 몰린 신호도 유실 없이 한 번 더 처리하고 멈춘다")
  void coalescesConcurrentSignalsWhileCoordinatorIsBusy() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(service.claim(1, 240000, 10))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("not released");
              }
              return List.of();
            })
        .thenReturn(List.of());
    try (var executor = Executors.newSingleThreadExecutor()) {
      var relay = new OutboxRelay(service, template, 1, 240000, 10, executor, work::add);
      try {
        relay.wakeUp();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 100; i++) {
          relay.wakeUp();
        }
        release.countDown();
        await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> verify(service, times(2)).claim(1, 240000, 10));
        executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        verify(service, times(2)).claim(1, 240000, 10);
      } finally {
        release.countDown();
        relay.stop();
      }
    }
  }

  @Test
  @DisplayName("깨움 제출이 거절돼도 커밋 스레드에서 실행하지 않고 복구 조회로 재개한다")
  void recoversRejectedWakeupWithoutRunningOnCaller() {
    var reject = new AtomicBoolean(true);
    var relay =
        new OutboxRelay(
            service,
            template,
            1,
            240000,
            10,
            task -> {
              if (reject.getAndSet(false)) {
                throw new RejectedExecutionException("temporarily unavailable");
              }
              coordinator.add(task);
            },
            work::add);
    when(service.claim(1, 240000, 10)).thenReturn(List.of());

    relay.wakeUp();
    verifyNoInteractions(service, template);
    relay.recover();
    coordinator.remove().run();

    verify(service).claim(1, 240000, 10);
    assertThat(coordinator).isEmpty();
  }

  @Test
  @DisplayName("전송 실행기가 종료되면 새 선점을 멈추고 임대를 보존한다")
  void stopsClaimingAfterSendExecutorRejectsWork() {
    when(service.claim(1, 240000, 10)).thenReturn(List.of(delivery()));
    var relay =
        new OutboxRelay(
            service,
            template,
            1,
            240000,
            10,
            coordinator::add,
            task -> {
              throw new RejectedExecutionException("stopped");
            });

    relay.wakeUp();
    coordinator.remove().run();
    relay.recover();

    verify(service).claim(1, 240000, 10);
    verify(service, never()).markFailed(any(), anyInt());
    verifyNoInteractions(template);
    assertThat(coordinator).isEmpty();
  }

  @Test
  @DisplayName("전송 요청의 즉시 실패는 재시도로 인계하고 슬롯을 반환한다")
  void retriesImmediateSendFailure() {
    var delivery = delivery();
    when(service.claim(1, 240000, 10)).thenReturn(List.of(delivery), List.of());
    when(template.send(anyString(), anyString(), any(byte[].class)))
        .thenThrow(new IllegalStateException("unavailable"));
    var relay = relay();

    relay.wakeUp();
    coordinator.remove().run();
    work.remove().run();
    coordinator.remove().run();

    verify(service).markFailed(delivery, 10);
    verify(service, never()).markPublished(any());
    verify(service, times(2)).claim(1, 240000, 10);
  }

  @Test
  @DisplayName("비동기 전송 실패도 재시도로 인계하고 슬롯을 반환한다")
  void retriesAsynchronousSendFailure() {
    var delivery = delivery();
    var reply = new CompletableFuture<SendResult<String, byte[]>>();
    when(service.claim(1, 240000, 10)).thenReturn(List.of(delivery), List.of());
    when(template.send(anyString(), anyString(), any(byte[].class))).thenReturn(reply);
    var relay = relay();

    relay.wakeUp();
    coordinator.remove().run();
    work.remove().run();
    reply.completeExceptionally(new IllegalStateException("unavailable"));
    work.remove().run();
    coordinator.remove().run();

    verify(service).markFailed(delivery, 10);
    verify(service, never()).markPublished(any());
  }

  @Test
  @DisplayName("결과 저장 실패에도 슬롯을 반환하고 다른 이벤트 발행을 계속한다")
  void refillsSlotEvenWhenResultPersistenceFails() {
    var delivery = delivery();
    var reply = new CompletableFuture<SendResult<String, byte[]>>();
    when(service.claim(1, 240000, 10)).thenReturn(List.of(delivery), List.of());
    when(template.send(anyString(), anyString(), any(byte[].class))).thenReturn(reply);
    when(service.markPublished(any())).thenThrow(new IllegalStateException("database unavailable"));
    var relay = relay();

    relay.wakeUp();
    coordinator.remove().run();
    work.remove().run();
    reply.complete(result(delivery, 73));
    work.remove().run();
    coordinator.remove().run();

    verify(service, times(2)).claim(1, 240000, 10);
    assertThat(coordinator).isEmpty();
    assertThat(work).isEmpty();
  }

  @Test
  @DisplayName("시작 때 대기 작업을 찾고 종료 후에는 새 신호를 받지 않는다")
  void startsRecoveryAndStopsNewWakeups() {
    when(service.claim(1, 240000, 10)).thenReturn(List.of());
    var relay = relay();

    relay.onApplicationReady();
    coordinator.remove().run();
    relay.stop();
    relay.wakeUp();
    relay.recover();

    verify(service).claim(1, 240000, 10);
    assertThat(coordinator).isEmpty();
  }

  @Test
  @DisplayName("저장 작업이 돌기 전에 끝난 전송 결과는 한 트랜잭션에 함께 저장한다")
  void persistsResultsCompletedBeforeFlushAsOneBatch() {
    var first = delivery();
    var second = delivery();
    var firstReply = new CompletableFuture<SendResult<String, byte[]>>();
    var secondReply = new CompletableFuture<SendResult<String, byte[]>>();
    when(service.claim(2, 240000, 10)).thenReturn(List.of(first, second), List.of());
    when(template.send(anyString(), anyString(), any(byte[].class)))
        .thenReturn(firstReply, secondReply);
    when(service.markPublished(any())).thenReturn(Set.of(first.id(), second.id()));
    var relay = new OutboxRelay(service, template, 2, 240000, 10, coordinator::add, work::add);

    relay.wakeUp();
    coordinator.remove().run();
    work.remove().run();
    work.remove().run();
    firstReply.complete(result(first, 73));
    secondReply.complete(result(second, 74));
    assertThat(work).hasSize(1);
    work.remove().run();
    coordinator.remove().run();

    verify(service)
        .markPublished(
            List.of(
                new Published(first, new Publication("topic", 2, 73)),
                new Published(second, new Publication("topic", 2, 74))));
    verify(service, times(2)).claim(2, 240000, 10);
    assertThat(work).isEmpty();
  }

  @Test
  @DisplayName("함께 저장한 결과도 이벤트마다 자기 전송 구간을 부모로 결과 구간을 열고 저장 뒤 닫는다")
  void keepsResultTracePerEventInBatch() {
    var first = delivery();
    var second = delivery();
    var firstReply = new CompletableFuture<SendResult<String, byte[]>>();
    var secondReply = new CompletableFuture<SendResult<String, byte[]>>();
    when(service.claim(2, 240000, 10)).thenReturn(List.of(first, second), List.of());
    when(template.send(anyString(), anyString(), any(byte[].class)))
        .thenReturn(firstReply, secondReply);
    var trace = new ArrayList<String>();
    var parents = new ArrayDeque<>(List.of("send-1", "send-2"));
    when(service.markPublished(any()))
        .thenAnswer(
            invocation -> {
              trace.add("persist");
              return Set.of(first.id(), second.id());
            });
    AsyncObservation observations =
        new AsyncObservation() {
          @Override
          public String capture() {
            return parents.remove();
          }

          @Override
          public Scope start(
              String operation, String parent, Map<String, String> ids, Timing timing) {
            if (!operation.equals("payment.outbox.result")) {
              return Scope.NOOP;
            }
            trace.add("open " + parent + " " + ids.get("event.id"));
            return () -> trace.add("close " + parent);
          }
        };
    var relay =
        new OutboxRelay(
            service, template, 2, 240000, 10, coordinator::add, work::add, observations);

    relay.wakeUp();
    coordinator.remove().run();
    work.remove().run();
    work.remove().run();
    firstReply.complete(result(first, 73));
    secondReply.complete(result(second, 74));
    work.remove().run();

    assertThat(trace)
        .containsExactly(
            "open send-1 " + first.id(),
            "open send-2 " + second.id(),
            "persist",
            "close send-2",
            "close send-1");
  }

  @Test
  @DisplayName("저장 작업을 맡길 실행기가 멈추면 결과를 버리고 새 선점을 멈춘다")
  void stopsClaimingWhenFlushCannotBeScheduled() {
    var delivery = delivery();
    var reply = new CompletableFuture<SendResult<String, byte[]>>();
    when(service.claim(1, 240000, 10)).thenReturn(List.of(delivery));
    when(template.send(anyString(), anyString(), any(byte[].class))).thenReturn(reply);
    var reject = new AtomicBoolean(false);
    var relay =
        new OutboxRelay(
            service,
            template,
            1,
            240000,
            10,
            coordinator::add,
            task -> {
              if (reject.get()) {
                throw new RejectedExecutionException("stopped");
              }
              work.add(task);
            });

    relay.wakeUp();
    coordinator.remove().run();
    work.remove().run();
    reject.set(true);
    reply.complete(result(delivery, 73));
    relay.recover();

    verify(service, never()).markPublished(any());
    verify(service).claim(1, 240000, 10);
    assertThat(coordinator).isEmpty();
    assertThat(work).isEmpty();
  }

  private OutboxRelay relay() {
    return new OutboxRelay(service, template, 1, 240000, 10, coordinator::add, work::add);
  }

  private static Delivery delivery() {
    return new Delivery(UUID.randomUUID(), 1, "topic", "key", new byte[] {1, 2, 3});
  }

  private static SendResult<String, byte[]> result(Delivery delivery, long offset) {
    return new SendResult<>(
        new ProducerRecord<>("topic", "key", delivery.body()),
        new RecordMetadata(new TopicPartition("topic", 2), offset, 0, 0, 3, 3));
  }
}
