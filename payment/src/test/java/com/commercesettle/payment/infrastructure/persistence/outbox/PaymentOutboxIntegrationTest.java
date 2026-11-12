package com.commercesettle.payment.infrastructure.persistence.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.PaymentAcceptedPayload;
import com.commercesettle.payment.application.port.OutboxStore.Delivery;
import com.commercesettle.payment.application.port.OutboxStore.Publication;
import com.commercesettle.payment.application.port.OutboxStore.Published;
import com.commercesettle.payment.application.port.PaymentEventOutbox;
import com.commercesettle.payment.application.service.OutboxDeliveryService;
import com.commercesettle.payment.infrastructure.messaging.OutboxAppended;
import com.commercesettle.payment.infrastructure.persistence.TransactionLimits;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringJUnitConfig(PaymentOutboxIntegrationTest.Config.class)
@DirtiesContext
@Timeout(60)
class PaymentOutboxIntegrationTest {
  private static final UUID PAYMENT_ID = new UUID(0, 1);
  private static final UUID ORDER_ID = new UUID(0, 2);
  private static final UUID CYCLE_ID = new UUID(0, 3);
  private static final OffsetDateTime OCCURRED_AT =
      OffsetDateTime.parse("2026-10-30T00:00:00.123456Z");

  private static final AtomicInteger STATEMENTS = new AtomicInteger();

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3")
          .withInitScript("db/payment.sql")
          .withCommand("postgres", "-c", "max_connections=20")
          .withCreateContainerCmdModifier(
              cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024));

  @Autowired private PaymentEventOutbox outbox;
  @Autowired private ObjectMapper mapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager manager;
  @Autowired private AtomicInteger appended;
  @Autowired private OutboxDeliveryService delivery;
  @Autowired private DataSource dataSource;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM payment.outbox");
    appended.set(0);
  }

  @Test
  @DisplayName("PaymentAccepted를 결제 topic·결제 ID key·원본 bytes로 저장하고 저장 신호를 낸다")
  void storesPaymentAccepted() throws Exception {
    EventEnvelope<PaymentAcceptedPayload> event = event(UUID.randomUUID(), 3);

    UUID eventId = inTransaction(() -> outbox.appendPaymentAccepted(event));

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM payment.outbox");
    assertThat(eventId).isEqualTo(event.eventId());
    assertThat(row)
        .containsEntry("id", event.eventId())
        .containsEntry("event_type", "PaymentAccepted")
        .containsEntry("event_version", 1)
        .containsEntry("aggregate_id", PAYMENT_ID)
        .containsEntry("aggregate_version", 0L)
        .containsEntry("topic", "commerce-settle.payment.v1")
        .containsEntry("message_key", PAYMENT_ID.toString())
        .containsEntry("status", "PENDING");
    assertThat((byte[]) row.get("raw_body")).containsExactly(mapper.writeValueAsBytes(event));
    assertThat(appended).hasValue(1);
  }

  @Test
  @DisplayName("같은 이벤트를 다시 저장하면 한 건만 남고 저장 신호도 한 번이다")
  void ignoresSameEventTwice() {
    EventEnvelope<PaymentAcceptedPayload> event = event(UUID.randomUUID(), 3);

    inTransaction(() -> outbox.appendPaymentAccepted(event));
    inTransaction(() -> outbox.appendPaymentAccepted(event));

    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.outbox", Long.class)).isEqualTo(1);
    assertThat(appended).hasValue(1);
  }

  @Test
  @DisplayName("같은 eventId에 다른 본문이면 거절한다")
  void rejectsDifferentBodyForSameEventId() {
    UUID eventId = UUID.randomUUID();
    inTransaction(() -> outbox.appendPaymentAccepted(event(eventId, 3)));

    assertThatThrownBy(() -> inTransaction(() -> outbox.appendPaymentAccepted(event(eventId, 4))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.outbox", Long.class)).isEqualTo(1);
  }

  @Test
  @DisplayName("같은 결제의 더 낮은 버전 새 이벤트는 거절하고 이미 저장한 이전 이벤트 재저장은 허용한다")
  void rejectsLowerVersionButKeepsReplayOfStoredEvent() {
    EventEnvelope<PaymentAcceptedPayload> older = event(UUID.randomUUID(), PAYMENT_ID, 3, 1);
    inTransaction(() -> outbox.appendPaymentAccepted(older));
    inTransaction(() -> outbox.appendPaymentAccepted(event(UUID.randomUUID(), PAYMENT_ID, 3, 2)));

    assertThatThrownBy(
            () ->
                inTransaction(
                    () -> outbox.appendPaymentAccepted(event(UUID.randomUUID(), PAYMENT_ID, 3, 1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("backwards");
    inTransaction(() -> outbox.appendPaymentAccepted(older));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.outbox", Long.class)).isEqualTo(2);
    assertThat(appended).hasValue(2);
  }

  @Test
  @DisplayName("한 트랜잭션에서 같은 결제의 이벤트 두 건을 저장해도 SQL 왕복은 잠금 1회와 이벤트당 1회다")
  void appendsWithOneStatementPerEventAndOneLockPerAggregate() {
    int statements =
        new TransactionTemplate(manager)
            .execute(
                status -> {
                  TransactionLimits.apply(jdbc);
                  int before = STATEMENTS.get();
                  outbox.appendPaymentAccepted(event(UUID.randomUUID(), PAYMENT_ID, 3, 1));
                  outbox.appendPaymentAccepted(event(UUID.randomUUID(), PAYMENT_ID, 3, 2));
                  return STATEMENTS.get() - before;
                });

    assertThat(statements).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.outbox", Long.class)).isEqualTo(2);
  }

  @Test
  @DisplayName("선점은 시도 상한을 넘긴 행을 건너뛰기만 하고 확인 대상 전환은 주기 정리에서 한다")
  void reviewsExhaustedRowsOnlyInPeriodicCleanup() {
    inTransaction(() -> outbox.appendPaymentAccepted(event(UUID.randomUUID(), 3)));
    jdbc.update(
        "UPDATE payment.outbox SET status='RETRY',attempts=10,next_run_at=clock_timestamp()");

    assertThat(delivery.claim(10, 240000, 10)).isEmpty();
    assertThat(jdbc.queryForObject("SELECT status FROM payment.outbox", String.class))
        .isEqualTo("RETRY");
    assertThat(delivery.reviewExhausted(100, 10)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT status FROM payment.outbox", String.class))
        .isEqualTo("REVIEW_REQUIRED");
  }

  @Test
  @DisplayName("이벤트 종류·버전·집계 ID가 계약과 다르면 저장하지 않는다")
  void rejectsInvalidEnvelope() {
    var payload = new PaymentAcceptedPayload(PAYMENT_ID, ORDER_ID, 3, CYCLE_ID);
    var wrongType =
        new EventEnvelope<>(
            UUID.randomUUID(), "PaymentConfirmed", 1, OCCURRED_AT, PAYMENT_ID, 0, payload);
    var wrongVersion =
        new EventEnvelope<>(
            UUID.randomUUID(), "PaymentAccepted", 2, OCCURRED_AT, PAYMENT_ID, 0, payload);
    var wrongAggregate =
        new EventEnvelope<>(
            UUID.randomUUID(), "PaymentAccepted", 1, OCCURRED_AT, ORDER_ID, 0, payload);

    for (var event : List.of(wrongType, wrongVersion, wrongAggregate)) {
      assertThatThrownBy(() -> inTransaction(() -> outbox.appendPaymentAccepted(event)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.outbox", Long.class)).isZero();
  }

  @Test
  @DisplayName("트랜잭션 밖에서 호출하면 거절한다")
  void requiresCallerTransaction() {
    assertThatThrownBy(() -> outbox.appendPaymentAccepted(event(UUID.randomUUID(), 3)))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.outbox", Long.class)).isZero();
  }

  @Test
  @DisplayName("호출자 트랜잭션이 롤백되면 Outbox도 남지 않는다")
  void rollsBackWithCaller() {
    var template = new TransactionTemplate(manager);

    template.executeWithoutResult(
        status -> {
          outbox.appendPaymentAccepted(event(UUID.randomUUID(), 3));
          status.setRollbackOnly();
        });

    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.outbox", Long.class)).isZero();
  }

  @Test
  @DisplayName("여러 발행 결과를 한 번에 저장하되 임대·버전이 맞는 행만 PUBLISHED로 바꾼다")
  void marksOnlyCurrentLeasesPublishedInOneBatch() {
    var payments = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    for (var payment : payments) {
      inTransaction(() -> outbox.appendPaymentAccepted(event(UUID.randomUUID(), payment, 3)));
    }
    var claimed = delivery.claim(10, 240000, 10);
    assertThat(claimed).hasSize(3);
    var current = claimed.get(0);
    var reclaimed = claimed.get(1);
    var expired = claimed.get(2);
    // 다른 발행기가 다시 선점한 행과 임대가 끝난 행은 이 결과로 덮어쓰지 않는다.
    jdbc.update("UPDATE payment.outbox SET version=version+1 WHERE id=?", reclaimed.id());
    jdbc.update(
        "UPDATE payment.outbox SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",
        expired.id());

    var updated =
        delivery.markPublished(
            List.of(
                new Published(current, new Publication(current.topic(), 1, 10)),
                new Published(reclaimed, new Publication(reclaimed.topic(), 1, 11)),
                new Published(expired, new Publication(expired.topic(), 1, 12))));

    assertThat(updated).containsExactly(current.id());
    assertThat(
            jdbc.queryForMap(
                "SELECT status,published_partition,published_offset,version,lease_until"
                    + " FROM payment.outbox WHERE id=?",
                current.id()))
        .containsEntry("status", "PUBLISHED")
        .containsEntry("published_partition", 1)
        .containsEntry("published_offset", 10L)
        .containsEntry("version", current.version() + 1)
        .containsEntry("lease_until", null);
    assertThat(
            jdbc.queryForList(
                "SELECT status FROM payment.outbox WHERE id IN (?,?) AND published_offset IS NULL",
                String.class,
                reclaimed.id(),
                expired.id()))
        .containsExactly("RUNNING", "RUNNING");
  }

  @Test
  @DisplayName("배치 중 한 행의 잠금 대기가 끝나면 배치 전체를 롤백한다")
  void rollsBackWholeBatchAfterBoundedLockWait() throws Exception {
    var claimed = claimTwo();
    var batch = published(claimed);

    try (var executor = Executors.newSingleThreadExecutor();
        var holder = dataSource.getConnection()) {
      try {
        lockRow(holder, claimed.get(1).id());
        var result = executor.submit(() -> delivery.markPublished(batch));

        assertLockTimeout(result);
        for (var row : claimed) {
          assertThat(
                  jdbc.queryForMap(
                      "SELECT status,version,published_offset FROM payment.outbox WHERE id=?",
                      row.id()))
              .containsEntry("status", "RUNNING")
              .containsEntry("version", row.version())
              .containsEntry("published_offset", null);
        }
      } finally {
        holder.rollback();
      }
    }
    assertThat(delivery.markPublished(batch))
        .containsExactlyInAnyOrder(claimed.get(0).id(), claimed.get(1).id());
  }

  @Test
  @DisplayName("행 잠금을 기다리는 동안 임대가 끝난 행만 빼고 나머지 결과를 저장한다")
  void skipsLeaseExpiredWhileWaitingForRowLock() throws Exception {
    var claimed = claimTwo();
    var current = claimed.get(0);
    var expiring = claimed.get(1);
    var callerPid = new CompletableFuture<Integer>();
    var start = new CompletableFuture<Void>();

    try (var executor = Executors.newSingleThreadExecutor();
        var holder = dataSource.getConnection()) {
      try {
        var result =
            executor.submit(
                () ->
                    new TransactionTemplate(manager)
                        .execute(
                            status -> {
                              callerPid.complete(
                                  jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                              start.orTimeout(5, TimeUnit.SECONDS).join();
                              return delivery.markPublished(published(claimed));
                            }));
        int pid = callerPid.get(5, TimeUnit.SECONDS);
        jdbc.update(
            "UPDATE payment.outbox SET lease_until=clock_timestamp()+interval '500 milliseconds'"
                + " WHERE id=?",
            expiring.id());
        lockRow(holder, expiring.id());
        start.complete(null);
        await()
            .atMost(Duration.ofSeconds(2))
            .pollInterval(Duration.ofMillis(10))
            .until(
                () ->
                    jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid=? AND"
                            + " wait_event_type='Lock')",
                        Boolean.class,
                        pid));
        await()
            .atMost(Duration.ofSeconds(2))
            .pollInterval(Duration.ofMillis(10))
            .until(
                () ->
                    jdbc.queryForObject(
                        "SELECT lease_until <= clock_timestamp() FROM payment.outbox WHERE id=?",
                        Boolean.class,
                        expiring.id()));
        holder.rollback();

        assertThat(result.get(5, TimeUnit.SECONDS)).containsExactly(current.id());
        assertThat(
                jdbc.queryForObject(
                    "SELECT status FROM payment.outbox WHERE id=?", String.class, current.id()))
            .isEqualTo("PUBLISHED");
        assertThat(
                jdbc.queryForMap(
                    "SELECT status,version,published_offset FROM payment.outbox WHERE id=?",
                    expiring.id()))
            .containsEntry("status", "RUNNING")
            .containsEntry("version", expiring.version())
            .containsEntry("published_offset", null);
      } finally {
        start.complete(null);
        holder.rollback();
      }
    }
  }

  private List<Delivery> claimTwo() {
    for (int i = 0; i < 2; i++) {
      inTransaction(
          () -> outbox.appendPaymentAccepted(event(UUID.randomUUID(), UUID.randomUUID(), 3)));
    }
    var claimed = delivery.claim(10, 240000, 10);
    assertThat(claimed).hasSize(2);
    return claimed;
  }

  private static List<Published> published(List<Delivery> deliveries) {
    return deliveries.stream()
        .map(d -> new Published(d, new Publication(d.topic(), 0, deliveries.indexOf(d))))
        .toList();
  }

  private static void lockRow(Connection connection, UUID id) throws SQLException {
    connection.setAutoCommit(false);
    try (var statement =
        connection.prepareStatement("SELECT id FROM payment.outbox WHERE id=? FOR UPDATE")) {
      statement.setObject(1, id);
      statement.execute();
    }
  }

  private static void assertLockTimeout(Future<?> result) {
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

  private <T> T inTransaction(Supplier<T> action) {
    return new TransactionTemplate(manager).execute(status -> action.get());
  }

  private static EventEnvelope<PaymentAcceptedPayload> event(UUID eventId, long orderVersion) {
    return event(eventId, PAYMENT_ID, orderVersion);
  }

  private static EventEnvelope<PaymentAcceptedPayload> event(
      UUID eventId, UUID paymentId, long orderVersion) {
    return event(eventId, paymentId, orderVersion, 0);
  }

  private static EventEnvelope<PaymentAcceptedPayload> event(
      UUID eventId, UUID paymentId, long orderVersion, long aggregateVersion) {
    return new EventEnvelope<>(
        eventId,
        "PaymentAccepted",
        1,
        OCCURRED_AT,
        paymentId,
        aggregateVersion,
        new PaymentAcceptedPayload(paymentId, ORDER_ID, orderVersion, CYCLE_ID));
  }

  @Configuration
  @EnableTransactionManagement
  static class Config {
    @Bean
    DataSource dataSource() {
      String url = POSTGRES.getJdbcUrl();
      DataSource target =
          new DriverManagerDataSource(
              url + (url.contains("?") ? "&" : "?") + "socketTimeout=10&connectTimeout=5",
              POSTGRES.getUsername(),
              POSTGRES.getPassword());
      // 저장소가 보내는 SQL 문장 수를 세기 위해 문장 생성만 센다.
      return new DelegatingDataSource(target) {
        @Override
        public Connection getConnection() throws SQLException {
          Connection connection = super.getConnection();
          return (Connection)
              Proxy.newProxyInstance(
                  Connection.class.getClassLoader(),
                  new Class<?>[] {Connection.class},
                  (proxy, method, args) -> {
                    if (method.getName().startsWith("prepare")
                        || method.getName().equals("createStatement")) {
                      STATEMENTS.incrementAndGet();
                    }
                    try {
                      return method.invoke(connection, args);
                    } catch (InvocationTargetException exception) {
                      throw exception.getCause();
                    }
                  });
        }
      };
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
    PlatformTransactionManager manager(DataSource source) {
      return new DataSourceTransactionManager(source);
    }

    @Bean
    ObjectMapper mapper() {
      return new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    AtomicInteger appended() {
      return new AtomicInteger();
    }

    @Bean
    OutboxDeliveryService delivery(NamedParameterJdbcTemplate jdbc) {
      return new OutboxDeliveryService(new JdbcOutboxStore(jdbc));
    }

    @Bean
    PaymentEventOutbox outbox(
        NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, AtomicInteger appended) {
      ApplicationEventPublisher events =
          event -> {
            if (event instanceof OutboxAppended) {
              appended.incrementAndGet();
            }
          };
      return new JdbcPaymentEventOutbox(jdbc, mapper, events);
    }
  }
}
