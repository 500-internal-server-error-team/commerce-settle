package com.commercesettle.commerce.infrastructure.persistence.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.commerce.application.port.OrderEventOutbox;
import com.commercesettle.commerce.application.port.OutboxStore;
import com.commercesettle.commerce.application.port.OutboxStore.Publication;
import com.commercesettle.commerce.application.service.OutboxDeliveryService;
import com.commercesettle.commerce.infrastructure.messaging.OutboxRelay;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringJUnitConfig(OutboxIntegrationTest.Config.class)
@DirtiesContext
@Timeout(60)
class OutboxIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3")
          .withInitScript("db/commerce.sql")
          .withCommand("postgres", "-c", "max_connections=20")
          .withCreateContainerCmdModifier(
              cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024));

  @Autowired private OrderEventOutbox writer;
  @Autowired private OutboxDeliveryService service;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;
  @Autowired private PlatformTransactionManager manager;
  @Autowired private DataSource dataSource;
  private TransactionTemplate tx;

  @BeforeEach
  void reset() {
    tx = new TransactionTemplate(manager);
    jdbc.execute("CREATE TABLE IF NOT EXISTS commerce.test_business (id uuid PRIMARY KEY)");
    jdbc.update("DELETE FROM commerce.outbox");
    jdbc.update("DELETE FROM commerce.test_business");
  }

  @Test
  @DisplayName("업무 저장 실패는 Outbox 저장도 함께 롤백한다")
  void rollsBackBusinessAndOutboxTogether() throws Exception {
    var event = event(UUID.randomUUID(), 0);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      jdbc.update(
                          "INSERT INTO commerce.test_business(id) VALUES (?)", event.aggregateId());
                      writer.appendPaymentRequested(event);
                      throw new IllegalStateException("simulated business failure");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.outbox", Long.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.test_business", Long.class))
        .isZero();
  }

  @Test
  @DisplayName("업무 트랜잭션 밖에서는 Outbox만 따로 저장할 수 없다")
  void refusesAppendOutsideBusinessTransaction() throws Exception {
    var event = event(UUID.randomUUID(), 0);
    assertThatThrownBy(() -> writer.appendPaymentRequested(event))
        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
  }

  @Test
  @DisplayName("커밋 전에는 발행 대상에 보이지 않고 원문 나노초 시각을 유지한다")
  void hidesUncommittedEventAndKeepsOriginalBytes() throws Exception {
    var event = event(UUID.randomUUID(), 0);
    tx.executeWithoutResult(
        status -> {
          writer.appendPaymentRequested(event);
          // 별도 DB 연결에서 읽어 실제 발행기와 같은 커밋 가시성을 확인한다.
          try (var connection = dataSource.getConnection()) {
            try (var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT count(*) FROM commerce.outbox")) {
              result.next();
              assertThat(result.getLong(1)).isZero();
            }
          } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(exception);
          }
        });
    var delivery = service.claim(8, 240000, 10).getFirst();
    assertThat(delivery.body()).containsExactly(mapper.writeValueAsBytes(event));
    assertThat(mapper.readTree(delivery.body()).get("occurredAt").asText())
        .isEqualTo("2026-01-01T00:00:00.123456789Z");
    assertThat(service.markPublished(delivery, new Publication(delivery.topic(), 0, 0))).isTrue();
    assertThat(jdbc.queryForObject("SELECT published_offset FROM commerce.outbox", Long.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT published_at IS NOT NULL FROM commerce.outbox", Boolean.class))
        .isTrue();
  }

  @Test
  @DisplayName("같은 ID 동시 저장은 한 건이며 다른 본문은 원본을 바꾸지 못한다")
  void deduplicatesConcurrentAppendAndRejectsChangedBody() throws Exception {
    var event = event(UUID.randomUUID(), 0);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return tx.execute(status -> writer.appendPaymentRequested(event));
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return tx.execute(status -> writer.appendPaymentRequested(event));
              });
      start.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(event.eventId());
      assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(event.eventId());
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.outbox", Long.class))
        .isEqualTo(1);
    var changed =
        new EventEnvelope<>(
            event.eventId(),
            event.eventType(),
            event.eventVersion(),
            event.occurredAt().plusSeconds(1),
            event.aggregateId(),
            event.aggregateVersion(),
            event.payload());
    assertThatThrownBy(
            () -> tx.executeWithoutResult(status -> writer.appendPaymentRequested(changed)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(jdbc.queryForObject("SELECT raw_body FROM commerce.outbox", byte[].class))
        .containsExactly(mapper.writeValueAsBytes(event));
  }

  @Test
  @DisplayName("재시도 대기와 수동 확인인 선행 이벤트를 후행이 추월하지 않는다")
  void blocksSuccessorBehindRetryAndReview() throws Exception {
    var first = event(UUID.randomUUID(), 0);
    var second = event(UUID.randomUUID(), 1);
    tx.executeWithoutResult(
        status -> {
          writer.appendPaymentRequested(first);
          writer.appendPaymentRequested(second);
        });
    var claimed = service.claim(8, 240000, 10);
    assertThat(claimed).extracting(OutboxStore.Delivery::id).containsExactly(first.eventId());
    assertThat(service.markFailed(claimed.getFirst(), 10)).isTrue();
    assertThat(service.claim(8, 240000, 10)).isEmpty();
    jdbc.update("UPDATE commerce.outbox SET status='REVIEW_REQUIRED' WHERE id=?", first.eventId());
    assertThat(service.claim(8, 240000, 10)).isEmpty();
  }

  @Test
  @DisplayName("만료 임대의 늦은 응답은 회수한 실행자의 결과를 덮어쓰지 못한다")
  void fencesExpiredAndReclaimedDelivery() throws Exception {
    var event = event(UUID.randomUUID(), 0);
    tx.executeWithoutResult(status -> writer.appendPaymentRequested(event));
    var original = service.claim(1, 240000, 10).getFirst();
    jdbc.update(
        "UPDATE commerce.outbox SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",
        event.eventId());
    assertThat(service.markPublished(original, new Publication(original.topic(), 0, 73))).isFalse();
    var reclaimed = service.claim(1, 240000, 10).getFirst();
    assertThat(reclaimed.id()).isEqualTo(original.id());
    assertThat(reclaimed.body()).containsExactly(original.body());
    assertThat(reclaimed.version()).isGreaterThan(original.version());
    assertThat(service.markFailed(original, 10)).isFalse();
    assertThat(service.markPublished(reclaimed, new Publication(reclaimed.topic(), 0, 74)))
        .isTrue();
  }

  @Test
  @DisplayName("마지막 시도에서 중단된 작업은 원본을 보존하고 수동 확인으로 전환한다")
  void stopsExpiredFinalAttemptWithoutDroppingOriginal() throws Exception {
    var event = event(UUID.randomUUID(), 0);
    tx.executeWithoutResult(status -> writer.appendPaymentRequested(event));
    service.claim(1, 240000, 1);
    jdbc.update(
        "UPDATE commerce.outbox SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",
        event.eventId());
    assertThat(service.claim(1, 240000, 1)).isEmpty();
    assertThat(jdbc.queryForObject("SELECT status FROM commerce.outbox", String.class))
        .isEqualTo("REVIEW_REQUIRED");
    assertThat(jdbc.queryForObject("SELECT raw_body FROM commerce.outbox", byte[].class))
        .containsExactly(mapper.writeValueAsBytes(event));
  }

  @Test
  @DisplayName("잠긴 소진 작업을 건너뛰고 다른 주문만 선점한다")
  void skipsLockedExhaustedEventWithoutLettingItsSuccessorPass() throws Exception {
    var exhausted = event(UUID.randomUUID(), 0);
    var successor = event(UUID.randomUUID(), 1);
    var independent = forAnotherOrder(event(UUID.randomUUID(), 0));
    tx.executeWithoutResult(
        status -> {
          writer.appendPaymentRequested(exhausted);
          writer.appendPaymentRequested(successor);
          writer.appendPaymentRequested(independent);
        });
    var original = service.claim(1, 240000, 1).getFirst();
    assertThat(original.id()).isEqualTo(exhausted.eventId());
    jdbc.update(
        "UPDATE commerce.outbox SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",
        exhausted.eventId());

    try (var executor = Executors.newSingleThreadExecutor();
        var holder = dataSource.getConnection()) {
      lockRow(holder, exhausted.eventId());
      try {
        var claimed = executor.submit(() -> service.claim(1, 240000, 1)).get(5, TimeUnit.SECONDS);
        assertThat(claimed)
            .extracting(OutboxStore.Delivery::id)
            .containsExactly(independent.eventId());
        assertThat(
                jdbc.queryForObject(
                    "SELECT status FROM commerce.outbox WHERE id=?",
                    String.class,
                    exhausted.eventId()))
            .isEqualTo("RUNNING");
        assertThat(
                jdbc.queryForObject(
                    "SELECT status FROM commerce.outbox WHERE id=?",
                    String.class,
                    successor.eventId()))
            .isEqualTo("PENDING");
      } finally {
        holder.rollback();
      }
    }

    assertThat(service.claim(1, 240000, 1)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM commerce.outbox WHERE id=?", String.class, exhausted.eventId()))
        .isEqualTo("REVIEW_REQUIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT raw_body FROM commerce.outbox WHERE id=?",
                byte[].class,
                exhausted.eventId()))
        .containsExactly(mapper.writeValueAsBytes(exhausted));
  }

  @Test
  @DisplayName("소진 작업 전환도 요청한 배치 크기를 넘지 않는다")
  void boundsExhaustionTransitionsByClaimLimit() throws Exception {
    for (int i = 0; i < 3; i++) {
      var event = forAnotherOrder(event(UUID.randomUUID(), 0));
      tx.executeWithoutResult(status -> writer.appendPaymentRequested(event));
    }
    service.claim(3, 240000, 1);
    jdbc.update("UPDATE commerce.outbox SET lease_until=clock_timestamp()-interval '1 second'");

    assertThat(service.claim(1, 240000, 1)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM commerce.outbox WHERE status='REVIEW_REQUIRED'", Long.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM commerce.outbox WHERE status='RUNNING'", Long.class))
        .isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("발행 결과 저장의 잠금 대기가 끝나면 로컬 변경을 롤백한다")
  void rollsBackCallbackAfterBoundedLockWait(boolean published) throws Exception {
    var event = event(UUID.randomUUID(), 0);
    tx.executeWithoutResult(status -> writer.appendPaymentRequested(event));
    var delivery = service.claim(1, 240000, 10).getFirst();
    UUID marker = UUID.randomUUID();

    try (var executor = Executors.newSingleThreadExecutor();
        var holder = dataSource.getConnection()) {
      lockRow(holder, event.eventId());
      try {
        var result =
            executor.submit(
                () ->
                    tx.executeWithoutResult(
                        status -> {
                          jdbc.update("INSERT INTO commerce.test_business(id) VALUES (?)", marker);
                          if (published) {
                            service.markPublished(
                                delivery, new Publication(delivery.topic(), 0, 73));
                          } else {
                            service.markFailed(delivery, 10);
                          }
                        }));
        assertLockTimeout(result);
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM commerce.test_business WHERE id=?", Long.class, marker))
            .isZero();
        assertThat(
                jdbc.queryForObject(
                    "SELECT status FROM commerce.outbox WHERE id=?", String.class, event.eventId()))
            .isEqualTo("RUNNING");
        assertThat(
                jdbc.queryForObject(
                    "SELECT version FROM commerce.outbox WHERE id=?", Long.class, event.eventId()))
            .isEqualTo(delivery.version());
      } finally {
        holder.rollback();
      }
    }
    assertThat(service.markPublished(delivery, new Publication(delivery.topic(), 0, 74))).isTrue();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("행 잠금을 기다리는 동안 만료된 임대로 결과를 저장하지 않는다")
  void refusesResultWhenLeaseExpiresWhileWaitingForRowLock(boolean published) throws Exception {
    var event = event(UUID.randomUUID(), 0);
    tx.executeWithoutResult(status -> writer.appendPaymentRequested(event));
    var delivery = service.claim(1, 240000, 10).getFirst();
    var callerPid = new CompletableFuture<Integer>();
    var startCallback = new CompletableFuture<Void>();

    try (var executor = Executors.newSingleThreadExecutor();
        var holder = dataSource.getConnection()) {
      try {
        var result =
            executor.submit(
                () ->
                    tx.execute(
                        status -> {
                          callerPid.complete(
                              jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                          startCallback.orTimeout(5, TimeUnit.SECONDS).join();
                          return published
                              ? service.markPublished(
                                  delivery, new Publication(delivery.topic(), 0, 73))
                              : service.markFailed(delivery, 10);
                        }));
        int pid = callerPid.get(5, TimeUnit.SECONDS);
        jdbc.update(
            "UPDATE commerce.outbox SET lease_until=clock_timestamp()+interval '500 milliseconds'"
                + " WHERE id=?",
            event.eventId());
        lockRow(holder, event.eventId());
        startCallback.complete(null);
        org.awaitility.Awaitility.await()
            .atMost(java.time.Duration.ofSeconds(2))
            .pollInterval(java.time.Duration.ofMillis(10))
            .until(
                () ->
                    jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid=? AND"
                            + " wait_event_type='Lock')",
                        Boolean.class,
                        pid));
        org.awaitility.Awaitility.await()
            .atMost(java.time.Duration.ofSeconds(2))
            .pollInterval(java.time.Duration.ofMillis(10))
            .until(
                () ->
                    jdbc.queryForObject(
                        "SELECT lease_until <= clock_timestamp() FROM commerce.outbox WHERE id=?",
                        Boolean.class,
                        event.eventId()));
        holder.rollback();
        assertThat(result.get(5, TimeUnit.SECONDS)).isFalse();
        assertThat(
                jdbc.queryForObject(
                    "SELECT status FROM commerce.outbox WHERE id=?", String.class, event.eventId()))
            .isEqualTo("RUNNING");
        assertThat(
                jdbc.queryForObject(
                    "SELECT version FROM commerce.outbox WHERE id=?", Long.class, event.eventId()))
            .isEqualTo(delivery.version());
      } finally {
        startCallback.complete(null);
        holder.rollback();
      }
    }
  }

  @Test
  @DisplayName("같은 주문 append의 advisory 잠금 대기를 제한하고 업무 저장도 롤백한다")
  void rollsBackAppendAfterBoundedAdvisoryLockWait() throws Exception {
    var event = event(UUID.randomUUID(), 0);
    try (var executor = Executors.newSingleThreadExecutor();
        var holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (var statement =
          holder.prepareStatement(
              "SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text),0))")) {
        statement.setString(1, event.aggregateId().toString());
        statement.execute();
      }
      try {
        var result =
            executor.submit(
                () ->
                    tx.executeWithoutResult(
                        status -> {
                          jdbc.update(
                              "INSERT INTO commerce.test_business(id) VALUES (?)",
                              event.aggregateId());
                          writer.appendPaymentRequested(event);
                        }));
        assertLockTimeout(result);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.test_business", Long.class))
            .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.outbox", Long.class))
            .isZero();
      } finally {
        holder.rollback();
      }
    }
    tx.executeWithoutResult(status -> writer.appendPaymentRequested(event));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.outbox", Long.class))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("다른 주문의 같은 이벤트 ID 삽입 경합도 잠금 상한으로 롤백한다")
  void boundsUniqueKeyWaitDuringAppend() throws Exception {
    var original = event(UUID.randomUUID(), 0);
    var conflicting = forAnotherOrder(original);
    try (var executor = Executors.newSingleThreadExecutor()) {
      tx.executeWithoutResult(
          status -> {
            writer.appendPaymentRequested(original);
            var result =
                executor.submit(
                    () ->
                        tx.executeWithoutResult(
                            other -> {
                              jdbc.update(
                                  "INSERT INTO commerce.test_business(id) VALUES (?)",
                                  conflicting.aggregateId());
                              writer.appendPaymentRequested(conflicting);
                            }));
            assertLockTimeout(result);
          });
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.test_business", Long.class))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.outbox", Long.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT raw_body FROM commerce.outbox", byte[].class))
        .containsExactly(mapper.writeValueAsBytes(original));
  }

  @Test
  @DisplayName("테이블 잠금으로 선점이 막혀도 대기 상한 뒤 업무 저장을 롤백한다")
  void boundsClaimWaitOnTableLock() throws Exception {
    UUID marker = UUID.randomUUID();
    try (var executor = Executors.newSingleThreadExecutor();
        var holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (var statement = holder.createStatement()) {
        statement.execute("LOCK TABLE commerce.outbox IN ACCESS EXCLUSIVE MODE");
      }
      try {
        var result =
            executor.submit(
                () ->
                    tx.executeWithoutResult(
                        status -> {
                          jdbc.update("INSERT INTO commerce.test_business(id) VALUES (?)", marker);
                          service.claim(1, 240000, 10);
                        }));
        assertLockTimeout(result);
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM commerce.test_business WHERE id=?", Long.class, marker))
            .isZero();
      } finally {
        holder.rollback();
      }
    }
  }

  @Test
  @DisplayName("호출자의 짧은 제한은 유지하고 커밋 뒤 풀 연결에 제한을 남기지 않는다")
  void preservesShorterCallerLimitsAndRestoresPooledConnection() throws Exception {
    var event = event(UUID.randomUUID(), 0);
    int connectionPid =
        tx.execute(
            status -> {
              jdbc.execute("SET LOCAL lock_timeout='50ms'; SET LOCAL statement_timeout='500ms'");
              writer.appendPaymentRequested(event);
              assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("50ms");
              assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class))
                  .isEqualTo("500ms");
              return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            });
    var connections = new java.util.ArrayList<Connection>();
    var inspected = new java.util.HashSet<Integer>();
    int total = ((HikariDataSource) dataSource).getHikariPoolMXBean().getTotalConnections();
    try {
      for (int i = 0; i < total; i++) {
        var connection = dataSource.getConnection();
        connections.add(connection);
        try (var statement = connection.createStatement();
            var result =
                statement.executeQuery(
                    "SELECT"
                        + " pg_backend_pid(),current_setting('lock_timeout'),current_setting('statement_timeout')")) {
          assertThat(result.next()).isTrue();
          inspected.add(result.getInt(1));
          assertThat(result.getString(2)).isEqualTo("0");
          assertThat(result.getString(3)).isEqualTo("0");
        }
      }
    } finally {
      for (var connection : connections) {
        connection.close();
      }
    }
    assertThat(inspected).contains(connectionPid);
  }

  @Test
  @DisplayName("Kafka 발행과 ACK가 지연돼도 DB 트랜잭션과 풀 연결을 점유하지 않는다")
  @SuppressWarnings("unchecked")
  void releasesDatabaseBeforeWaitingForKafka() throws Exception {
    var event = event(UUID.randomUUID(), 0);
    tx.executeWithoutResult(status -> writer.appendPaymentRequested(event));
    org.springframework.kafka.core.KafkaTemplate<String, byte[]> template =
        org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);
    var response =
        new CompletableFuture<org.springframework.kafka.support.SendResult<String, byte[]>>();
    var sending = new CountDownLatch(1);
    var releaseSend = new CountDownLatch(1);
    org.mockito.Mockito.when(
            template.send(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(byte[].class)))
        .thenAnswer(
            invocation -> {
              sending.countDown();
              if (!releaseSend.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("send was not released");
              }
              return response;
            });
    try (var executor = Executors.newSingleThreadExecutor()) {
      try {
        var dispatcher = new OutboxRelay(service, template, 1, 240000, 10, executor, executor);
        dispatcher.wakeUp();
        assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections())
            .isZero();
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND"
                        + " state='idle in transaction'",
                    Long.class))
            .isZero();
        releaseSend.countDown();
        executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertThat(response).isNotCompleted();
        assertThat(((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections())
            .isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM commerce.outbox", String.class))
            .isEqualTo("RUNNING");

        response.complete(
            new org.springframework.kafka.support.SendResult<>(
                new org.apache.kafka.clients.producer.ProducerRecord<>(
                    "commerce-settle.commerce.v1",
                    event.aggregateId().toString(),
                    mapper.writeValueAsBytes(event)),
                new org.apache.kafka.clients.producer.RecordMetadata(
                    new org.apache.kafka.common.TopicPartition("commerce-settle.commerce.v1", 0),
                    73,
                    0,
                    0,
                    1,
                    1)));
        executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject("SELECT status FROM commerce.outbox", String.class))
            .isEqualTo("PUBLISHED");
      } finally {
        releaseSend.countDown();
      }
    }
  }

  private void lockRow(Connection connection, UUID id) throws SQLException {
    connection.setAutoCommit(false);
    try (var statement =
        connection.prepareStatement("SELECT id FROM commerce.outbox WHERE id=? FOR UPDATE")) {
      statement.setObject(1, id);
      statement.execute();
    }
  }

  private void assertLockTimeout(java.util.concurrent.Future<?> result) {
    assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(DataAccessException.class)
        .satisfies(
            exception -> {
              Throwable cause = exception;
              while (cause.getCause() != null) {
                cause = cause.getCause();
              }
              assertThat(cause).isInstanceOf(SQLException.class);
              assertThat(((SQLException) cause).getSQLState()).isEqualTo("55P03");
            });
  }

  private EventEnvelope<OrderPaymentRequestedPayload> forAnotherOrder(
      EventEnvelope<OrderPaymentRequestedPayload> event) throws Exception {
    var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(event);
    UUID orderId = UUID.randomUUID();
    node.put("aggregateId", orderId.toString());
    ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("payload"))
        .put("orderId", orderId.toString());
    return mapper.treeToValue(
        node, new TypeReference<EventEnvelope<OrderPaymentRequestedPayload>>() {});
  }

  private EventEnvelope<OrderPaymentRequestedPayload> event(UUID id, long version)
      throws Exception {
    var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(EVENT_JSON);
    node.put("eventId", id.toString());
    node.put("aggregateVersion", version);
    ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("payload"))
        .put("orderVersion", version);
    node.put("occurredAt", "2026-01-01T00:00:00.123456789Z");
    return mapper.readValue(mapper.writeValueAsBytes(node), new TypeReference<>() {});
  }

  @Configuration
  @EnableTransactionManagement
  static class Config {
    @Bean(destroyMethod = "close")
    HikariDataSource dataSource() {
      String url = POSTGRES.getJdbcUrl();
      var pool = new HikariDataSource();
      pool.setJdbcUrl(url + (url.contains("?") ? "&" : "?") + "socketTimeout=10&connectTimeout=5");
      pool.setUsername(POSTGRES.getUsername());
      pool.setPassword(POSTGRES.getPassword());
      pool.setMaximumPoolSize(4);
      pool.setMinimumIdle(0);
      pool.setConnectionTimeout(2000);
      return pool;
    }

    @Bean
    JdbcTemplate jdbc(DataSource source) {
      return new JdbcTemplate(source);
    }

    @Bean
    NamedParameterJdbcTemplate named(JdbcTemplate jdbc) {
      return new NamedParameterJdbcTemplate(jdbc);
    }

    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
      var factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(source);
      factory.setPackagesToScan("com.commercesettle.commerce.domain");
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      // 앱의 Spring Boot 기본 컬럼 이름 규칙과 같게 맞춰 domain 엔티티를 실제 스키마로 검증한다.
      factory.setJpaPropertyMap(
          java.util.Map.of(
              "hibernate.hbm2ddl.auto",
              "validate",
              "hibernate.default_schema",
              "commerce",
              "hibernate.physical_naming_strategy",
              "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
      return factory;
    }

    @Bean
    PlatformTransactionManager manager(EntityManagerFactory factory) {
      return new JpaTransactionManager(factory);
    }

    @Bean
    ObjectMapper mapper() {
      return new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    OrderEventOutbox writer(
        NamedParameterJdbcTemplate jdbc,
        ObjectMapper mapper,
        org.springframework.context.ApplicationEventPublisher events) {
      return new JdbcOrderEventOutbox(jdbc, mapper, events);
    }

    @Bean
    OutboxStore store(NamedParameterJdbcTemplate jdbc) {
      return new JdbcOutboxStore(jdbc);
    }

    @Bean
    OutboxDeliveryService service(OutboxStore store) {
      return new OutboxDeliveryService(store);
    }
  }

  static final String EVENT_JSON =
"""
{
  "eventId": "00000000-0000-0000-0000-000000000006",
  "eventType": "OrderPaymentRequested",
  "eventVersion": 1,
  "occurredAt": "2026-01-01T00:00:00Z",
  "aggregateId": "00000000-0000-0000-0000-000000000001",
  "aggregateVersion": 0,
  "payload": {
    "orderId": "00000000-0000-0000-0000-000000000001",
    "buyerId": "00000000-0000-0000-0000-000000000002",
    "sellerId": "00000000-0000-0000-0000-000000000003",
    "paymentCycleId": "00000000-0000-0000-0000-000000000004",
    "productId": "00000000-0000-0000-0000-000000000005",
    "orderVersion": 0,
    "status": "PAYMENT_PENDING",
    "policyVersion": "test-v1",
    "paymentWaitUntil": "2026-01-01T00:10:00Z",
    "externalMethod": null,
    "methodAccountRef": null,
    "grossAmount": 10000,
    "couponAmount": 0,
    "totalAmount": 10000,
    "mileageAmount": 10000,
    "externalAmount": 0,
    "sellerCouponAmount": 0,
    "platformCouponAmount": 0,
    "settlementBaseAmount": 10000
  }
}
""";
}
