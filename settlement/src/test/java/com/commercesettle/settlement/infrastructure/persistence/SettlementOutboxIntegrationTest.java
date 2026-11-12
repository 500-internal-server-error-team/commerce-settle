package com.commercesettle.settlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.port.OutboxStore.Delivery;
import com.commercesettle.settlement.application.port.OutboxStore.Publication;
import com.commercesettle.settlement.application.port.OutboxStore.PublishResult;
import com.commercesettle.settlement.application.port.SettlementEventOutbox;
import com.commercesettle.settlement.application.service.OutboxDeliveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL에서 Settlement Outbox 저장 규칙과 Relay 선점 순서를 확인한다. Relay는 끈다. */
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "spring.kafka.admin.auto-create=false",
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123",
      "app.internal-auth.senders[service\\:commerce].current=test-commerce-retention-credential-0123456789abcdef",
      "settlement.payout.mock-credential=test-settlement-credential-0123456789abcdef0123",
      "settlement.daily-batch.schedule-enabled=false",
      "settlement.payout-worker.enabled=false",
      "settlement.inbox-processor.enabled=false"
    })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SettlementOutboxIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  private static final OffsetDateTime OCCURRED_AT =
      OffsetDateTime.parse("2026-11-02T10:00:00.123456+09:00");
  private static final long LEASE_MS = 60_000;

  @Autowired SettlementEventOutbox outbox;
  @Autowired OutboxDeliveryService deliveries;
  @Autowired TransactionTemplate transactions;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  @AfterEach
  void deleteRows() {
    jdbc.update("DELETE FROM settlement.outbox");
  }

  @Test
  @DisplayName("발행 이벤트를 Settlement 토픽·지정 key·원문 bytes로 PENDING 저장한다")
  void storesEventWithTopicKeyAndBytes() throws Exception {
    UUID key = UUID.randomUUID();
    var event = event(UUID.randomUUID(), "RefundReservationResult", UUID.randomUUID(), 3);

    transactions.executeWithoutResult(status -> outbox.append(event, key));

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM settlement.outbox");
    assertThat(row)
        .containsEntry("id", event.eventId())
        .containsEntry("event_type", "RefundReservationResult")
        .containsEntry("event_version", 1)
        .containsEntry("aggregate_id", event.aggregateId())
        .containsEntry("aggregate_version", 3L)
        .containsEntry("topic", "commerce-settle.settlement.v1")
        .containsEntry("message_key", key.toString())
        .containsEntry("status", "PENDING");
    assertThat((byte[]) row.get("raw_body")).isEqualTo(mapper.writeValueAsBytes(event));
  }

  @Test
  @DisplayName("같은 이벤트는 한 건만 남고, 같은 eventId의 다른 본문·발행하지 않는 종류·낮아진 집계 버전은 거절한다")
  void rejectsConflictsAndUnknownTypes() {
    UUID aggregate = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    var event = event(eventId, "RefundLedgerReversed", aggregate, 5);
    transactions.executeWithoutResult(status -> outbox.append(event, aggregate));
    transactions.executeWithoutResult(status -> outbox.append(event, aggregate));

    for (var invalid :
        List.of(
            event(eventId, "RefundLedgerReversed", aggregate, 6),
            event(UUID.randomUUID(), "PaymentConfirmed", aggregate, 7),
            event(UUID.randomUUID(), "RefundReservationReleased", aggregate, 4))) {
      assertThatThrownBy(
              () -> transactions.executeWithoutResult(status -> outbox.append(invalid, aggregate)))
          // @Repository 예외 변환으로 IllegalArgumentException이 감싸진다.
          .isInstanceOf(InvalidDataAccessApiUsageException.class)
          .hasCauseInstanceOf(IllegalArgumentException.class);
    }
    assertThat(count()).isEqualTo(1);
  }

  @Test
  @DisplayName("트랜잭션 밖 호출은 거절하고, 호출자 트랜잭션이 롤백되면 남지 않는다")
  void followsCallerTransaction() {
    var event = event(UUID.randomUUID(), "SettlementNoticeRequested", UUID.randomUUID(), 0);

    assertThatThrownBy(() -> outbox.append(event, UUID.randomUUID()))
        .isInstanceOf(IllegalTransactionStateException.class);
    transactions.executeWithoutResult(
        status -> {
          outbox.append(event, UUID.randomUUID());
          status.setRollbackOnly();
        });

    assertThat(count()).isZero();
  }

  @Test
  @DisplayName("같은 집계의 앞선 미발행 이벤트를 추월해 선점하지 않는다")
  void claimKeepsAggregateOrder() {
    UUID aggregate = UUID.randomUUID();
    var first = event(UUID.randomUUID(), "RefundReservationResult", aggregate, 1);
    var second = event(UUID.randomUUID(), "RefundLedgerReversed", aggregate, 2);
    var other = event(UUID.randomUUID(), "RefundReservationResult", UUID.randomUUID(), 1);
    transactions.executeWithoutResult(
        status -> {
          outbox.append(first, aggregate);
          outbox.append(second, aggregate);
          outbox.append(other, aggregate);
        });
    // 앞선 이벤트가 재시도를 기다리는 동안 뒤 이벤트는 기다린다.
    jdbc.update(
        "UPDATE settlement.outbox SET status = 'RETRY', next_run_at = now() + interval '1 minute'"
            + " WHERE id = ?",
        first.eventId());

    assertThat(deliveries.claim(10, LEASE_MS, 10))
        .extracting(Delivery::id)
        .containsExactly(other.eventId());

    jdbc.update("UPDATE settlement.outbox SET next_run_at = now() WHERE id = ?", first.eventId());
    assertThat(deliveries.claim(10, LEASE_MS, 10))
        .extracting(Delivery::id)
        .containsExactly(first.eventId());
  }

  @Test
  @DisplayName("임대가 만료되면 다시 선점하고, 시도를 다 쓰면 REVIEW_REQUIRED로 남긴다")
  void reclaimsExpiredLeaseAndStopsAtAttemptLimit() {
    var event = event(UUID.randomUUID(), "RefundReservationResult", UUID.randomUUID(), 0);
    transactions.executeWithoutResult(status -> outbox.append(event, UUID.randomUUID()));
    Delivery claimed = deliveries.claim(1, LEASE_MS, 10).getFirst();
    assertThat(deliveries.claim(1, LEASE_MS, 10)).isEmpty();

    jdbc.update("UPDATE settlement.outbox SET lease_until = now() - interval '1 second'");
    Delivery reclaimed = deliveries.claim(1, LEASE_MS, 10).getFirst();
    assertThat(reclaimed.id()).isEqualTo(claimed.id());
    assertThat(deliveries.markFailed(claimed, 10)).isFalse();

    jdbc.update(
        "UPDATE settlement.outbox SET attempts = 10, lease_until = now() - interval '1 second'");
    assertThat(deliveries.claim(1, LEASE_MS, 10)).isEmpty();
    // 시도를 다 쓴 행의 전환은 선점마다가 아니라 복구 주기의 정리에서 한다.
    assertThat(jdbc.queryForObject("SELECT status FROM settlement.outbox", String.class))
        .isEqualTo("RUNNING");
    assertThat(deliveries.reviewExhausted(100, 10)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT status FROM settlement.outbox", String.class))
        .isEqualTo("REVIEW_REQUIRED");
    assertThat(deliveries.reviewExhausted(100, 10)).isZero();
  }

  @Test
  @DisplayName("발행 결과 저장은 같은 집계의 뒤 이벤트가 기다리는지 함께 알린다")
  void publishReportsWaitingSuccessor() {
    UUID aggregate = UUID.randomUUID();
    var first = event(UUID.randomUUID(), "RefundReservationResult", aggregate, 1);
    var second = event(UUID.randomUUID(), "RefundLedgerReversed", aggregate, 2);
    transactions.executeWithoutResult(
        status -> {
          outbox.append(first, aggregate);
          outbox.append(second, aggregate);
        });
    Delivery head = deliveries.claim(10, LEASE_MS, 10).getFirst();
    assertThat(head.id()).isEqualTo(first.eventId());
    assertThat(deliveries.markPublished(head, publication(head, 1)))
        .isEqualTo(PublishResult.PUBLISHED_WITH_SUCCESSOR);
    assertThat(deliveries.markPublished(head, publication(head, 1))).isEqualTo(PublishResult.STALE);

    Delivery tail = deliveries.claim(10, LEASE_MS, 10).getFirst();
    assertThat(tail.id()).isEqualTo(second.eventId());
    assertThat(deliveries.markPublished(tail, publication(tail, 2)))
        .isEqualTo(PublishResult.PUBLISHED);
  }

  @Test
  @DisplayName("결과 저장이 잠금을 기다리는 동안 다른 선점이 행을 가져가면 결과를 저장하지 않는다")
  void resultWaitingOnLockIsFencedByReclaim() throws Exception {
    var event = event(UUID.randomUUID(), "RefundReservationResult", UUID.randomUUID(), 0);
    transactions.executeWithoutResult(status -> outbox.append(event, UUID.randomUUID()));
    Delivery stale = deliveries.claim(1, LEASE_MS, 10).getFirst();
    var locked = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      // 다른 실행이 행을 잡고 임대 만료 뒤 재선점하는 상황을 같은 트랜잭션에서 재현한다.
      var reclaim =
          executor.submit(
              () ->
                  transactions.execute(
                      status -> {
                        jdbc.update(
                            "UPDATE settlement.outbox SET lease_until = clock_timestamp()"
                                + " - interval '1 second'");
                        locked.countDown();
                        try {
                          release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        return deliveries.claim(1, LEASE_MS, 10);
                      }));
      assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var result =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> deliveries.markFailed(stale, 10));
      // 결과 저장이 실제로 행 잠금을 기다리기 시작한 뒤 재선점을 커밋한다.
      long deadline = System.nanoTime() + 5_000_000_000L;
      while (jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Long.class) == 0
          && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      release.countDown();
      assertThat(reclaim.get(10, java.util.concurrent.TimeUnit.SECONDS)).hasSize(1);
      assertThat(result.get(10, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
    }
    assertThat(jdbc.queryForObject("SELECT status FROM settlement.outbox", String.class))
        .isEqualTo("RUNNING");
    assertThat(jdbc.queryForObject("SELECT attempts FROM settlement.outbox", Integer.class))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("결과 저장이 변경 없는 행 잠금을 기다리는 동안 임대가 끝나면 결과를 저장하지 않는다")
  void resultWaitingOnLockIsFencedByLeaseExpiry() throws Exception {
    assertThat(
            resultAfterLeaseExpiresWhileLocked(d -> deliveries.markPublished(d, publication(d, 1))))
        .isEqualTo(PublishResult.STALE);
    deleteRows();
    assertThat(resultAfterLeaseExpiresWhileLocked(d -> deliveries.markFailed(d, 10)))
        .isEqualTo(false);
  }

  /** 다른 트랜잭션이 같은 version의 행을 FOR UPDATE로만 잡고 있는 동안 임대를 만료시킨 뒤 결과를 돌려준다. */
  private Object resultAfterLeaseExpiresWhileLocked(
      java.util.function.Function<Delivery, Object> store) throws Exception {
    var event = event(UUID.randomUUID(), "RefundReservationResult", UUID.randomUUID(), 0);
    transactions.executeWithoutResult(status -> outbox.append(event, UUID.randomUUID()));
    Delivery delivery = deliveries.claim(1, LEASE_MS, 10).getFirst();
    // version은 그대로 두고 임대만 결과 저장의 잠금 대기 상한(1초)보다 먼저 끝나게 줄인다.
    jdbc.update(
        "UPDATE settlement.outbox SET lease_until = clock_timestamp() + interval '400 milliseconds'");
    var locked = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    Object result;
    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var holder =
          executor.submit(
              () ->
                  transactions.executeWithoutResult(
                      status -> {
                        jdbc.queryForList("SELECT id FROM settlement.outbox FOR UPDATE");
                        locked.countDown();
                        try {
                          release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                      }));
      assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var pending = java.util.concurrent.CompletableFuture.supplyAsync(() -> store.apply(delivery));
      long deadline = System.nanoTime() + 5_000_000_000L;
      while (jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Long.class) == 0
          && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      // 결과 저장이 잠금을 기다리는 동안 임대가 끝난 뒤에 잠금을 놓는다.
      while (Boolean.FALSE.equals(
          jdbc.queryForObject(
              "SELECT lease_until <= clock_timestamp() FROM settlement.outbox", Boolean.class))) {
        Thread.sleep(50);
      }
      release.countDown();
      holder.get(10, java.util.concurrent.TimeUnit.SECONDS);
      result = pending.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertThat(jdbc.queryForObject("SELECT status FROM settlement.outbox", String.class))
        .isEqualTo("RUNNING");
    return result;
  }

  private static Publication publication(Delivery delivery, long offset) {
    return new Publication(delivery.topic(), 0, offset);
  }

  private long count() {
    return jdbc.queryForObject("SELECT count(*) FROM settlement.outbox", Long.class);
  }

  private static EventEnvelope<Map<String, Object>> event(
      UUID eventId, String type, UUID aggregateId, long aggregateVersion) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("refundId", UUID.randomUUID().toString());
    return new EventEnvelope<>(
        eventId, type, 1, OCCURRED_AT, aggregateId, aggregateVersion, payload);
  }
}
