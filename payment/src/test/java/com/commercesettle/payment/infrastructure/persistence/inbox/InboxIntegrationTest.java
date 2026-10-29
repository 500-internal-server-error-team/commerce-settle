package com.commercesettle.payment.infrastructure.persistence.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import com.commercesettle.payment.application.service.AcceptOrderPaymentRequestService;
import com.commercesettle.payment.infrastructure.messaging.OrderPaymentRequestedListener;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringJUnitConfig(InboxIntegrationTest.Config.class)
@DirtiesContext
@Timeout(60)
class InboxIntegrationTest {
  private static final String TOPIC = "commerce-settle.commerce.v1";
  private static final String KEY = "00000000-0000-0000-0000-000000000001";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3")
          .withCommand("postgres", "-c", "max_connections=20")
          .withCreateContainerCmdModifier(
              cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024));

  @Autowired private OrderPaymentRequestAcceptor acceptor;
  @Autowired private ObjectMapper mapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager manager;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM payment.inbox_conflicts");
    jdbc.update("DELETE FROM payment.inbox");
  }

  @Test
  @DisplayName("원본과 WAITING 작업이 커밋된 뒤에만 Listener가 ack한다")
  void acknowledgesCommittedWaitingWorkWithOriginalNanoseconds() {
    byte[] body = body();
    var acknowledged = new AtomicBoolean();
    var listener = new OrderPaymentRequestedListener(mapper, acceptor);

    listener.receive(
        new ConsumerRecord<>(TOPIC, 0, 0L, KEY, body),
        () -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          assertThat(
                  jdbc.queryForObject(
                      "SELECT count(*) FROM payment.inbox WHERE status='WAITING' AND next_run_at IS NOT NULL",
                      Long.class))
              .isEqualTo(1);
          assertThat(jdbc.queryForObject("SELECT raw_body FROM payment.inbox", byte[].class))
              .containsExactly(body);
          acknowledged.set(true);
        });

    assertThat(acknowledged).isTrue();
    assertThat(
            jdbc.queryForObject("SELECT payload->>'occurredAt' FROM payment.inbox", String.class))
        .isEqualTo("2026-01-01T00:00:00.123456789Z");
    assertThat(jdbc.queryForObject("SELECT result_ref IS NULL FROM payment.inbox", Boolean.class))
        .isTrue();
  }

  @Test
  @DisplayName("유효한 대문자 UUID도 원문을 바꾸지 않고 수신한다")
  void preservesUppercaseUuidInOriginalBody() {
    byte[] uppercase =
        new String(body(), StandardCharsets.UTF_8)
            .replace("00000000-0000-0000-0000-000000000006", "00000000-0000-0000-0000-00000000000A")
            .getBytes(StandardCharsets.UTF_8);
    var acknowledged = new AtomicBoolean();

    new OrderPaymentRequestedListener(mapper, acceptor)
        .receive(new ConsumerRecord<>(TOPIC, 0, 0L, KEY, uppercase), () -> acknowledged.set(true));

    assertThat(acknowledged).isTrue();
    assertThat(jdbc.queryForObject("SELECT event_id::text FROM payment.inbox", String.class))
        .isEqualTo("00000000-0000-0000-0000-00000000000a");
    assertThat(jdbc.queryForObject("SELECT raw_body FROM payment.inbox", byte[].class))
        .containsExactly(uppercase);
  }

  @Test
  @DisplayName("수신 트랜잭션 실패는 처리 대기 작업도 함께 롤백한다")
  void rollsBackInboxAndWaitingWorkTogether() throws Exception {
    byte[] body = body();
    var event = event(body);
    var tx = new TransactionTemplate(manager);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      acceptor.accept(event, new Source(TOPIC, 0, 0L, body));
                      throw new IllegalStateException("simulated transaction failure");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.inbox", Long.class)).isZero();
  }

  @Test
  @DisplayName("동시 재전달도 수신 원본과 대기 작업을 한 번만 만든다")
  void deduplicatesConcurrentRedelivery() throws Exception {
    byte[] body = body();
    var event = event(body);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                start.await();
                acceptor.accept(event, new Source(TOPIC, 0, 0L, body));
                return true;
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                acceptor.accept(event, new Source(TOPIC, 0, 1L, body));
                return true;
              });
      start.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.inbox", Long.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.inbox_conflicts", Long.class))
        .isZero();
  }

  @Test
  @DisplayName("같은 ID의 다른 본문은 원래 작업을 덮어쓰지 않고 격리 후 ack한다")
  void preservesConflictingBodyWithoutOverwritingOriginal() throws Exception {
    byte[] original = body();
    acceptor.accept(event(original), new Source(TOPIC, 0, 0L, original));
    var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(original);
    ((com.fasterxml.jackson.databind.node.ObjectNode) json.get("payload"))
        .put("policyVersion", "test-v2");
    byte[] conflicting = mapper.writeValueAsBytes(json);
    var acknowledged = new AtomicBoolean();
    var listener = new OrderPaymentRequestedListener(mapper, acceptor);

    listener.receive(
        new ConsumerRecord<>(TOPIC, 0, 1L, KEY, conflicting),
        () -> {
          assertThat(
                  jdbc.queryForObject("SELECT count(*) FROM payment.inbox_conflicts", Long.class))
              .isEqualTo(1);
          acknowledged.set(true);
        });
    listener.receive(new ConsumerRecord<>(TOPIC, 0, 1L, KEY, conflicting), () -> {});

    assertThat(acknowledged).isTrue();
    assertThat(jdbc.queryForObject("SELECT raw_body FROM payment.inbox", byte[].class))
        .containsExactly(original);
    assertThat(jdbc.queryForObject("SELECT raw_body FROM payment.inbox_conflicts", byte[].class))
        .containsExactly(conflicting);
    assertThat(jdbc.queryForObject("SELECT status FROM payment.inbox", String.class))
        .isEqualTo("WAITING");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.inbox_conflicts", Long.class))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("충돌 격리 저장이 실패하면 원본 offset을 ack하지 않는다")
  void doesNotAcknowledgeFailedConflictPersistence() throws Exception {
    byte[] original = body();
    acceptor.accept(event(original), new Source(TOPIC, 0, 0L, original));
    var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(original);
    ((com.fasterxml.jackson.databind.node.ObjectNode) json.get("payload"))
        .put("policyVersion", "test-v2");
    byte[] conflicting = mapper.writeValueAsBytes(json);
    var acknowledged = new AtomicBoolean();
    var listener = new OrderPaymentRequestedListener(mapper, acceptor);
    jdbc.execute(
        "ALTER TABLE payment.inbox_conflicts ADD CONSTRAINT test_reject_conflict CHECK(false) NOT VALID");
    try {
      assertThatThrownBy(
              () ->
                  listener.receive(
                      new ConsumerRecord<>(TOPIC, 0, 1L, KEY, conflicting),
                      () -> acknowledged.set(true)))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(acknowledged).isFalse();
      assertThat(jdbc.queryForObject("SELECT raw_body FROM payment.inbox", byte[].class))
          .containsExactly(original);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.inbox_conflicts", Long.class))
          .isZero();
    } finally {
      jdbc.execute("ALTER TABLE payment.inbox_conflicts DROP CONSTRAINT test_reject_conflict");
    }
  }

  @Test
  @DisplayName("미완료 중복 수신의 잠금 대기는 상한 내 실패하고 ack하지 않는다")
  void boundsUncommittedDuplicateWaitWithoutAcknowledging() throws Exception {
    byte[] body = body();
    var event = event(body);
    var inserted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var acknowledged = new AtomicBoolean();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                new TransactionTemplate(manager)
                    .executeWithoutResult(
                        status -> {
                          acceptor.accept(event, new Source(TOPIC, 0, 0L, body));
                          inserted.countDown();
                          try {
                            if (!release.await(10, TimeUnit.SECONDS))
                              throw new IllegalStateException("test lock was not released");
                          } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(error);
                          }
                        });
                return true;
              });
      try {
        assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
        var duplicate =
            executor.submit(
                () -> {
                  new OrderPaymentRequestedListener(mapper, acceptor)
                      .receive(
                          new ConsumerRecord<>(TOPIC, 0, 1L, KEY, body),
                          () -> acknowledged.set(true));
                  return true;
                });
        assertThatThrownBy(() -> duplicate.get(3, TimeUnit.SECONDS))
            .isInstanceOf(java.util.concurrent.ExecutionException.class)
            .hasCauseInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(acknowledged).isFalse();
      } finally {
        release.countDown();
      }
      assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.inbox", Long.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.inbox_conflicts", Long.class))
        .isZero();
    new OrderPaymentRequestedListener(mapper, acceptor)
        .receive(new ConsumerRecord<>(TOPIC, 0, 1L, KEY, body), () -> acknowledged.set(true));
    assertThat(acknowledged).isTrue();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.inbox", Long.class)).isEqualTo(1);
  }

  private byte[] body() {
    return EVENT_JSON
        .replace("2026-01-01T00:00:00Z", "2026-01-01T00:00:00.123456789Z")
        .getBytes(StandardCharsets.UTF_8);
  }

  private EventEnvelope<OrderPaymentRequestedPayload> event(byte[] body) throws Exception {
    return mapper.readValue(body, new TypeReference<>() {});
  }

  @Configuration
  @EnableTransactionManagement
  static class Config {
    @Bean
    DataSource dataSource() {
      String url = POSTGRES.getJdbcUrl();
      return new DriverManagerDataSource(
          url + (url.contains("?") ? "&" : "?") + "socketTimeout=10&connectTimeout=5",
          POSTGRES.getUsername(),
          POSTGRES.getPassword());
    }

    @Bean(initMethod = "migrate")
    Flyway flyway(DataSource source) {
      return Flyway.configure()
          .dataSource(source)
          .schemas("payment")
          .locations("classpath:db/migration")
          .load();
    }

    @Bean
    @DependsOn("flyway")
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
    InboxStore store(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
      return new JdbcInboxStore(jdbc, mapper, "payment.v1");
    }

    @Bean
    OrderPaymentRequestAcceptor acceptor(InboxStore inbox) {
      return new AcceptOrderPaymentRequestService(inbox);
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
