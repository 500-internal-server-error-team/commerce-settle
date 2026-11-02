package com.commercesettle.commerce.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.commercesettle.commerce.application.port.AddressCipher;
import com.commercesettle.commerce.application.port.NotificationGateway;
import com.commercesettle.commerce.application.port.NotificationStore;
import com.commercesettle.commerce.application.port.RetentionStore;
import com.commercesettle.commerce.application.port.RetentionStore.Target;
import com.commercesettle.commerce.application.service.RetentionService;
import com.commercesettle.commerce.domain.exception.OrderStateException;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.Notification;
import com.commercesettle.commerce.domain.model.Order;
import com.commercesettle.commerce.domain.model.OrderAddress;
import com.commercesettle.commerce.domain.model.Product;
import com.commercesettle.commerce.domain.model.ProductStatus;
import com.commercesettle.commerce.domain.model.ShippingAddress;
import com.commercesettle.commerce.domain.repository.OrderRepository;
import com.commercesettle.commerce.infrastructure.persistence.notification.NotificationReceiptStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "app.retention.enabled=true",
      "app.retention.cron=-",
      "app.retention.batch-size=2",
      "app.retention.max-batches=1",
      "app.auth.session-epoch=00000000-0000-4000-8000-000000000000",
      "app.auth.rate-limit.identifier-hmac-key=retention-test-hmac-key-32bytes-long",
      "app.auth.password-reset.delivery-key=retention-test-delivery-key-32bytes",
      "app.address.encryption-key=retention-test-address-key-32bytes-long",
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123"
    })
@Testcontainers
@Import(RetentionIntegrationTest.TimeConfig.class)
@Timeout(30)
class RetentionIntegrationTest {
  private static final Instant NOW = Instant.parse("2026-11-02T01:00:00.123456Z");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  @Autowired JdbcTemplate jdbc;
  @Autowired OrderRepository orders;
  @Autowired AddressCipher cipher;
  @Autowired RetentionStore store;
  @Autowired NotificationStore notifications;
  @Autowired NotificationReceiptStore receipts;
  @Autowired PlatformTransactionManager manager;
  @Autowired ObjectMapper mapper;
  @Autowired ConfigurableApplicationContext context;

  @Autowired
  @Qualifier("retentionScheduler")
  ThreadPoolTaskScheduler scheduler;

  @BeforeEach
  void clean() throws Exception {
    scheduler.submit(() -> {}).get(5, TimeUnit.SECONDS);
    for (String table :
        List.of(
            "retention_history",
            "retention_checks",
            "notification_retired_keys",
            "notification_attempts",
            "notification_mock_receipts",
            "notification_items",
            "notification_deliveries",
            "audit_logs",
            "outbox",
            "inbox",
            "idempotency_records",
            "order_refund_requests",
            "order_addresses",
            "orders")) jdbc.update("DELETE FROM commerce." + table);
  }

  @Test
  @DisplayName("종결 시각 재조회와 정확히 90일 경계에서 주소 암호문만 제거한다")
  void masksAtExactRetentionBoundary() {
    var due = closed(NOW.minus(Duration.ofDays(90)));
    var young = closed(NOW.minus(Duration.ofDays(90)).plusNanos(1000));
    var found = orders.findById(due.getId()).orElseThrow();
    assertThat(found.getClosedAt()).isEqualTo(NOW.minus(Duration.ofDays(90)));
    assertThat(due(NOW, 10)).containsExactly(due.getId());

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE commerce.order_addresses SET masked_at=? WHERE order_id=?",
                    time(NOW),
                    due.getId()))
        .isInstanceOf(DataAccessException.class);
    assertThat(process(Target.ORDER, due.getId(), NOW)).isEqualTo("MASKED");
    assertThat(process(Target.ORDER, due.getId(), NOW)).isNull();

    var address = orders.findAddressByBuyerAndOrderId(due.getBuyerId(), due.getId()).orElseThrow();
    assertThat(address.getMaskedAt()).isEqualTo(NOW);
    assertThatThrownBy(address::getSealed).isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT recipient_ciphertext FROM commerce.order_addresses WHERE order_id=?",
                byte[].class,
                due.getId()))
        .containsExactly((byte) 0);
    assertThat(
            jdbc.queryForObject(
                "SELECT masked_at FROM commerce.order_addresses WHERE order_id=?",
                OffsetDateTime.class,
                young.getId()))
        .isNull();
    assertThat(orders.findById(due.getId()).orElseThrow().getAmounts()).isEqualTo(due.getAmounts());
    assertThat(count("retention_history")).isEqualTo(1);
  }

  @Test
  @DisplayName("후보 조회 뒤 생긴 보존 보류와 미완료 반품을 마스킹 직전에 다시 검사한다")
  void rechecksHoldsAndOpenReturns() {
    var hold = closed(NOW.minus(Duration.ofDays(91)));
    var dispute = closed(NOW.minus(Duration.ofDays(91)));
    assertThat(due(NOW, 10)).hasSize(2);
    jdbc.update(
        "UPDATE commerce.order_addresses SET hold_reason='DISPUTE' WHERE order_id=?", hold.getId());
    jdbc.update(
        """
        INSERT INTO commerce.order_refund_requests(id,order_id,buyer_id,seller_id,kind,status,accepted_at,reason,created_at,updated_at)
        VALUES (?,?,?,?,'RETURN','FAILED_REVIEW',?,'test',?,?)
        """,
        UUID.randomUUID(),
        dispute.getId(),
        dispute.getBuyerId(),
        dispute.getSellerId(),
        time(NOW),
        time(NOW),
        time(NOW));

    assertThat(process(Target.ORDER, hold.getId(), NOW)).isEqualTo("HELD");
    assertThat(process(Target.ORDER, dispute.getId(), NOW)).isEqualTo("HELD");

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM commerce.order_addresses WHERE masked_at IS NOT NULL",
                Long.class))
        .isZero();
    assertThat(due(NOW, 10)).isEmpty();
    assertThatThrownBy(() -> hold.beginRefund(false, NOW)).isInstanceOf(OrderStateException.class);
  }

  @Test
  @DisplayName("다른 트랜잭션이 잠근 주문은 건너뛰고 나머지를 처리하며 재시작 뒤 회수한다")
  void skipsLockedOrdersAndResumesWithNewService() throws Exception {
    var locked = closed(NOW.minus(Duration.ofDays(91)));
    var available = closed(NOW.minus(Duration.ofDays(91)));
    try (var holder = jdbc.getDataSource().getConnection()) {
      holder.setAutoCommit(false);
      try (var statement =
          holder.prepareStatement("SELECT id FROM commerce.orders WHERE id=? FOR UPDATE")) {
        statement.setObject(1, locked.getId());
        statement.executeQuery().close();
      }
      assertThat(process(Target.ORDER, locked.getId(), NOW)).isNull();
      assertThat(process(Target.ORDER, available.getId(), NOW)).isEqualTo("MASKED");
      holder.rollback();
    }
    var restarted = new RetentionService(store, Clock.fixed(NOW, ZoneOffset.UTC), manager);
    restarted.run(1, 2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM commerce.order_addresses WHERE masked_at IS NOT NULL",
                Long.class))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("주소 잠금 시간 초과는 주소와 이력을 함께 롤백한다")
  void boundsAddressLockAndRollsBack() throws Exception {
    var order = closed(NOW.minus(Duration.ofDays(91)));
    try (var holder = jdbc.getDataSource().getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      holder.setAutoCommit(false);
      try (var statement =
          holder.prepareStatement(
              "SELECT id FROM commerce.order_addresses WHERE order_id=? FOR UPDATE")) {
        statement.setObject(1, order.getId());
        statement.executeQuery().close();
      }
      var work = executor.submit(() -> process(Target.ORDER, order.getId(), NOW));
      var error = catchThrowable(() -> work.get(3, TimeUnit.SECONDS));
      assertThat(error)
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(DataAccessException.class);
      assertThat(
              ((SQLException) ((DataAccessException) error.getCause()).getMostSpecificCause())
                  .getSQLState())
          .isEqualTo("55P03");
      holder.rollback();
    }
    assertThat(count("retention_history")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT masked_at FROM commerce.order_addresses WHERE order_id=?",
                OffsetDateTime.class,
                order.getId()))
        .isNull();
    assertThat(process(Target.ORDER, order.getId(), NOW)).isEqualTo("MASKED");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("일부 대상 이력 저장 실패는 해당 주소만 롤백하고 다음 실행에서 재개한다")
  void retriesPartialFailureWithoutUndoingSuccess(boolean failureHistoryUnavailable) {
    var failed = closed(NOW.minus(Duration.ofDays(92)));
    var good = closed(NOW.minus(Duration.ofDays(91)));
    jdbc.execute(
        "CREATE FUNCTION commerce.fail_retention() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.target_id='"
            + failed.getId()
            + (failureHistoryUnavailable ? "'" : "' AND NEW.result='MASKED'")
            + " THEN RAISE EXCEPTION 'test failure'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER fail_retention BEFORE INSERT ON commerce.retention_history FOR EACH ROW EXECUTE FUNCTION commerce.fail_retention()");
    try {
      new RetentionService(store, Clock.fixed(NOW, ZoneOffset.UTC), manager).run(1, 3);
      assertThat(
              jdbc.queryForObject(
                  "SELECT masked_at FROM commerce.order_addresses WHERE order_id=?",
                  OffsetDateTime.class,
                  failed.getId()))
          .isNull();
      assertThat(
              jdbc.queryForObject(
                  "SELECT masked_at FROM commerce.order_addresses WHERE order_id=?",
                  OffsetDateTime.class,
                  good.getId()))
          .isEqualTo(time(NOW));
      assertThat(
              jdbc.queryForList(
                  "SELECT result FROM commerce.retention_checks WHERE target_id=?",
                  String.class,
                  failed.getId()))
          .isEqualTo(failureHistoryUnavailable ? List.of() : List.of("FAILED"));
    } finally {
      jdbc.execute("DROP TRIGGER fail_retention ON commerce.retention_history");
      jdbc.execute("DROP FUNCTION commerce.fail_retention()");
    }
    new RetentionService(store, Clock.fixed(NOW.plusSeconds(61), ZoneOffset.UTC), manager)
        .run(1, 3);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM commerce.order_addresses WHERE masked_at IS NOT NULL",
                Long.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM commerce.retention_history WHERE result='MASKED'",
                Long.class))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("연결된 모든 거래가 90일을 지났을 때만 발송·항목·시도·수신을 함께 제거한다")
  void purgesOnlyFullyExpiredGroups() throws Exception {
    var expired = closed(NOW.minus(Duration.ofDays(91)));
    var young = closed(NOW.minus(Duration.ofDays(89)));
    var held = closed(NOW.minus(Duration.ofDays(91)));
    jdbc.update(
        "UPDATE commerce.order_addresses SET hold_reason='DISPUTE' WHERE order_id=?", held.getId());
    UUID purge = delivery("SENT", expired);
    UUID mixed = delivery("SENT", expired, young);
    UUID hold = delivery("SENT", held);
    UUID pending = delivery("PENDING", closed(NOW.minus(Duration.ofDays(91))));
    sentinels(expired);
    long outbox = count("outbox");
    long inbox = count("inbox");
    long audit = count("audit_logs");

    assertThat(process(Target.DELIVERY, purge, NOW)).isEqualTo("PURGED");
    assertThat(process(Target.DELIVERY, mixed, NOW)).isEqualTo("HELD");
    assertThat(process(Target.DELIVERY, hold, NOW)).isEqualTo("HELD");
    assertThat(process(Target.DELIVERY, pending, NOW)).isEqualTo("HELD");

    for (String table :
        List.of(
            "notification_deliveries",
            "notification_items",
            "notification_attempts",
            "notification_mock_receipts")) {
      String key = table.equals("notification_deliveries") ? "id" : "delivery_id";
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM commerce." + table + " WHERE " + key + "=?",
                  Long.class,
                  purge))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM commerce." + table + " WHERE " + key + "=?",
                  Long.class,
                  pending))
          .isEqualTo(1);
    }
    assertThat(count("notification_retired_keys")).isEqualTo(1);
    assertThat(count("outbox")).isEqualTo(outbox);
    assertThat(count("inbox")).isEqualTo(inbox);
    assertThat(count("audit_logs")).isEqualTo(audit);
    assertThat(count("idempotency_records")).isEqualTo(1);
    assertThat(process(Target.DELIVERY, purge, NOW)).isNull();
  }

  @Test
  @DisplayName("삭제 도중 실패하면 발송·시도·수신·항목과 멱등키를 모두 원상태로 롤백한다")
  void rollsBackEntireNotificationGraph() {
    var expired = closed(NOW.minus(Duration.ofDays(91)));
    UUID id = delivery("SENT", expired);
    jdbc.execute(
        "CREATE FUNCTION commerce.fail_purge() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.result='PURGED' THEN RAISE EXCEPTION 'test failure'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER fail_purge BEFORE INSERT ON commerce.retention_history FOR EACH ROW EXECUTE FUNCTION commerce.fail_purge()");
    try {
      assertThatThrownBy(() -> process(Target.DELIVERY, id, NOW))
          .isInstanceOf(DataAccessException.class);
      for (String table :
          List.of(
              "notification_deliveries",
              "notification_items",
              "notification_attempts",
              "notification_mock_receipts")) assertThat(count(table)).isEqualTo(1);
      assertThat(count("notification_retired_keys")).isZero();
      assertThat(count("retention_history")).isZero();
    } finally {
      jdbc.execute("DROP TRIGGER fail_purge ON commerce.retention_history");
      jdbc.execute("DROP FUNCTION commerce.fail_purge()");
    }
    assertThat(process(Target.DELIVERY, id, NOW)).isEqualTo("PURGED");
  }

  @Test
  @DisplayName("발송 임대와 보존 보류가 먼저 커밋되면 삭제하지 않고 수신 멱등 기록을 유지한다")
  void protectsLeasesAndConcurrentHolds() throws Exception {
    var expired = closed(NOW.minus(Duration.ofDays(91)));
    UUID id = delivery("PENDING", expired);
    var claimed = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var worker =
          executor.submit(
              () ->
                  new TransactionTemplate(manager)
                      .executeWithoutResult(
                          status -> {
                            var lease = notifications.claim(NOW);
                            assertThat(lease.lease().notification().id()).isEqualTo(id);
                            claimed.countDown();
                            awaitRelease(release);
                          }));
      assertThat(claimed.await(5, TimeUnit.SECONDS)).isTrue();
      try {
        assertThat(process(Target.DELIVERY, id, NOW)).isNull();
      } finally {
        release.countDown();
      }
      worker.get(5, TimeUnit.SECONDS);
    }
    assertThat(process(Target.DELIVERY, id, NOW)).isEqualTo("HELD");
    assertThat(count("notification_mock_receipts")).isEqualTo(1);
    var held = closed(NOW.minus(Duration.ofDays(91)));
    UUID heldDelivery = delivery("SENT", held);
    try (var holder = jdbc.getDataSource().getConnection()) {
      holder.setAutoCommit(false);
      try (var statement =
          holder.prepareStatement("SELECT id FROM commerce.orders WHERE id=? FOR UPDATE")) {
        statement.setObject(1, held.getId());
        statement.executeQuery().close();
      }
      try (var statement =
          holder.prepareStatement(
              "UPDATE commerce.order_addresses SET hold_reason='DISPUTE' WHERE order_id=?")) {
        statement.setObject(1, held.getId());
        statement.executeUpdate();
      }
      assertThat(process(Target.DELIVERY, heldDelivery, NOW)).isNull();
      holder.commit();
    }
    assertThat(process(Target.DELIVERY, heldDelivery, NOW)).isEqualTo("HELD");
    assertThat(count("notification_mock_receipts")).isEqualTo(2);
  }

  @Test
  @DisplayName("동시 중복 실행은 한 번의 삭제와 이력으로 수렴한다")
  void convergesConcurrentDuplicateRuns() throws Exception {
    var expired = closed(NOW.minus(Duration.ofDays(91)));
    UUID id = delivery("SENT", expired);
    var start = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return process(Target.DELIVERY, id, NOW);
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return process(Target.DELIVERY, id, NOW);
              });
      assertThat(Arrays.asList(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("PURGED", null);
    }
    assertThat(count("notification_deliveries")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM commerce.retention_history WHERE result='PURGED'",
                Long.class))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("종결 주문의 반품 개시는 같은 주문 잠금에서 거절되어 보존 판단과 경합하지 않는다")
  void rejectsReturnOpeningUnderTheRetentionLock() throws Exception {
    var expired = closed(NOW.minus(Duration.ofDays(91)));
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var opening =
          executor.submit(
              () ->
                  new TransactionTemplate(manager)
                      .executeWithoutResult(
                          status -> {
                            var order = orders.findLockedById(expired.getId()).orElseThrow();
                            locked.countDown();
                            try {
                              if (!release.await(5, TimeUnit.SECONDS))
                                throw new IllegalStateException("test latch expired");
                            } catch (InterruptedException error) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(error);
                            }
                            order.beginRefund(false, NOW);
                            orders.save(order);
                          }));
      assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
      try {
        assertThat(process(Target.ORDER, expired.getId(), NOW)).isNull();
      } finally {
        release.countDown();
      }
      assertThatThrownBy(() -> opening.get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(OrderStateException.class);
    }
    assertThat(orders.findById(expired.getId()).orElseThrow().getStatus())
        .isEqualTo(Order.Status.CANCELLED);
    assertThat(count("order_refund_requests")).isZero();
    assertThat(count("retention_history")).isZero();
    assertThat(process(Target.ORDER, expired.getId(), NOW)).isEqualTo("MASKED");
  }

  @Test
  @DisplayName("판매 묶음의 주문 수가 늘어도 잠금·재검사 SQL 호출 수를 늘리지 않는다")
  void boundsQueriesForGroupedNotifications() {
    var a = closed(NOW.minus(Duration.ofDays(91)));
    var b = closed(NOW.minus(Duration.ofDays(91)));
    var c = closed(NOW.minus(Duration.ofDays(91)));
    var one = delivery("SENT", a);
    var many = delivery("SENT", a, b, c);
    var observed = org.mockito.Mockito.spy(jdbc);
    var adapter = new JdbcRetentionStore(observed);
    var tx = new TransactionTemplate(manager);

    assertThat(tx.<String>execute(status -> adapter.process(Target.DELIVERY, one, NOW)))
        .isEqualTo("PURGED");
    long singleCalls =
        org.mockito.Mockito.mockingDetails(observed).getInvocations().stream()
            .filter(
                call -> call.getArguments().length > 0 && call.getArguments()[0] instanceof String)
            .count();
    org.mockito.Mockito.clearInvocations(observed);
    assertThat(tx.<String>execute(status -> adapter.process(Target.DELIVERY, many, NOW)))
        .isEqualTo("PURGED");
    long groupedCalls =
        org.mockito.Mockito.mockingDetails(observed).getInvocations().stream()
            .filter(
                call -> call.getArguments().length > 0 && call.getArguments()[0] instanceof String)
            .count();
    assertThat(groupedCalls).isEqualTo(singleCalls);
    assertThat(count("notification_deliveries")).isZero();
  }

  @Test
  @DisplayName("한도 뒤 중단된 실행은 보류된 앞 대상을 재선택하지 않고 다음 대상부터 이어간다")
  void resumesBoundedRunsWithoutStarvingBehindHolds() {
    var held = closed(NOW.minus(Duration.ofDays(92)));
    var ready = closed(NOW.minus(Duration.ofDays(91)));
    jdbc.update(
        "UPDATE commerce.order_addresses SET hold_reason='DISPUTE' WHERE order_id=?", held.getId());
    var first = new RetentionService(store, Clock.fixed(NOW, ZoneOffset.UTC), manager);
    first.run(1, 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT result FROM commerce.retention_checks WHERE target_id=?",
                String.class,
                held.getId()))
        .isEqualTo("HELD");
    assertThat(
            jdbc.queryForObject(
                "SELECT masked_at FROM commerce.order_addresses WHERE order_id=?",
                OffsetDateTime.class,
                ready.getId()))
        .isNull();

    new RetentionService(store, Clock.fixed(NOW, ZoneOffset.UTC), manager).run(1, 1);

    assertThat(
            jdbc.queryForObject(
                "SELECT masked_at FROM commerce.order_addresses WHERE order_id=?",
                OffsetDateTime.class,
                ready.getId()))
        .isEqualTo(time(NOW));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM commerce.retention_history WHERE result='HELD'", Long.class))
        .isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  @DisplayName("연결 주문이 잠긴 발송을 반복 선택하지 않고 뒤의 발송을 정리하며 다음 실행에서 회수한다")
  void skipsBusyDeliveriesAndRecoversAfterOrderUnlock(int batchSize) throws Exception {
    var busy = new ArrayList<Order>();
    var busyDeliveries = new ArrayList<UUID>();
    for (int index = 0; index < batchSize; index++) {
      var order = closed(NOW.minus(Duration.ofDays(91)));
      busy.add(order);
      UUID id = delivery("SENT", order);
      busyDeliveries.add(id);
      jdbc.update(
          "UPDATE commerce.notification_deliveries SET created_at=? WHERE id=?",
          time(NOW.minus(Duration.ofDays(93)).plusSeconds(index)),
          id);
    }
    UUID ready = delivery("SENT", closed(NOW.minus(Duration.ofDays(91))));
    var service = new RetentionService(store, Clock.fixed(NOW, ZoneOffset.UTC), manager);

    try (var holder = jdbc.getDataSource().getConnection()) {
      holder.setAutoCommit(false);
      for (Order order : busy) {
        try (var statement =
            holder.prepareStatement("SELECT id FROM commerce.orders WHERE id=? FOR UPDATE")) {
          statement.setObject(1, order.getId());
          statement.executeQuery().close();
        }
      }
      service.run(batchSize, 3);

      assertThat(notifications.find(ready)).isEmpty();
      for (UUID id : busyDeliveries) assertThat(notifications.find(id)).isPresent();
      assertThat(count("notification_retired_keys")).isEqualTo(1);
      holder.rollback();
    }

    new RetentionService(store, Clock.fixed(NOW, ZoneOffset.UTC), manager).run(batchSize, 3);

    assertThat(count("notification_deliveries")).isZero();
    assertThat(count("notification_retired_keys")).isEqualTo(batchSize + 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM commerce.retention_history WHERE result='PURGED'",
                Long.class))
        .isEqualTo(batchSize + 1);
  }

  @Test
  @DisplayName("실제 기동 완료 이벤트는 전용 실행기에서 보존 대상을 다시 찾는다")
  void resumesFromApplicationReadyEvent() throws Exception {
    var expired = closed(NOW.minus(Duration.ofDays(91)));
    context.publishEvent(
        new ApplicationReadyEvent(
            new SpringApplication(RetentionIntegrationTest.class),
            new String[0],
            context,
            Duration.ZERO));
    scheduler.submit(() -> {}).get(5, TimeUnit.SECONDS);
    assertThat(
            jdbc.queryForObject(
                "SELECT masked_at FROM commerce.order_addresses WHERE order_id=?",
                OffsetDateTime.class,
                expired.getId()))
        .isNotNull();
  }

  @Test
  @DisplayName("기한이 지난 운영 재처리를 거절하고 기한 안의 원키 재처리는 유지한다")
  void rejectsExpiredReplayWithoutExtendingDeadlineForHolds() {
    var expired = closed(NOW.minus(Duration.ofDays(90)));
    var young = closed(NOW.minus(Duration.ofDays(89)));
    UUID old = delivery("REVIEW_REQUIRED", expired);
    UUID live = delivery("REVIEW_REQUIRED", young);
    jdbc.update(
        "UPDATE commerce.order_addresses SET hold_reason='DISPUTE' WHERE order_id=?",
        expired.getId());
    var tx = new TransactionTemplate(manager);

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> notifications.replay(old, 0, "test", "test", NOW)))
        .isInstanceOf(Notification.StateConflictException.class);
    tx.executeWithoutResult(status -> notifications.replay(live, 0, "test", "test", NOW));

    assertThat(notifications.find(old).orElseThrow().status())
        .isEqualTo(Notification.Status.REVIEW_REQUIRED);
    assertThat(notifications.find(old).orElseThrow().version()).isZero();
    assertThat(notifications.find(live).orElseThrow().status())
        .isEqualTo(Notification.Status.PENDING);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM commerce.notification_attempts WHERE operation='REPLAY'",
                Long.class))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("삭제 뒤 동일 원업무는 무변경이고 다른 본문은 충돌하며 늦은 수신 기록은 생기지 않는다")
  void retainsBusinessDeduplicationAndRejectsLateReceipts() {
    var expired = closed(NOW.minus(Duration.ofDays(91)));
    UUID delivery = delivery("SENT", expired);
    jdbc.update(
        "INSERT INTO commerce.buyer_accounts(id,email_normalized,password_hash,display_name,status,created_at,updated_at,version) VALUES (?,?,'test','test','ACTIVE',?,?,0)",
        expired.getBuyerId(),
        expired.getBuyerId() + "@test.invalid",
        time(NOW),
        time(NOW));
    var payload =
        mapper
            .createObjectNode()
            .put("orderId", expired.getId().toString())
            .put("totalAmount", 5000);
    var draft =
        new NotificationStore.Draft(
            "commerce",
            UUID.randomUUID(),
            Notification.Kind.SHIPMENT_STARTED,
            expired.getId(),
            ActorKind.BUYER,
            expired.getBuyerId(),
            NOW.minus(Duration.ofDays(92)),
            payload);
    assertThat(process(Target.DELIVERY, delivery, NOW)).isEqualTo("PURGED");
    var tx = new TransactionTemplate(manager);

    tx.executeWithoutResult(status -> notifications.append(draft));
    var changed =
        new NotificationStore.Draft(
            draft.source(),
            UUID.randomUUID(),
            draft.kind(),
            draft.businessRef(),
            draft.recipientKind(),
            draft.recipientId(),
            draft.occurredAt(),
            payload.deepCopy().put("totalAmount", 6000));
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> notifications.append(changed)))
        .isInstanceOf(Notification.ContentConflictException.class);
    assertThatThrownBy(() -> receipts.save(delivery, "1".repeat(64), "ACCEPTED", NOW))
        .isInstanceOfSatisfying(
            NotificationGateway.Failure.class,
            error -> assertThat(error.code()).isEqualTo("NOTIFICATION_RETENTION_EXPIRED"));

    assertThat(count("notification_deliveries")).isZero();
    assertThat(count("notification_items")).isZero();
    assertThat(count("notification_mock_receipts")).isZero();
    assertThat(count("notification_retired_keys")).isEqualTo(1);
  }

  @Test
  @DisplayName("내부 운영 보류와 해제는 주문 잠금으로 직렬화하고 다음 실행에서 다시 처리한다")
  void releasesHoldForImmediateRecheck() {
    var expired = closed(NOW.minus(Duration.ofDays(91)));
    UUID delivery = delivery("SENT", expired);
    var service = new RetentionService(store, Clock.fixed(NOW, ZoneOffset.UTC), manager);
    service.hold(expired.getId(), "DISPUTE");
    assertThat(process(Target.ORDER, expired.getId(), NOW)).isEqualTo("HELD");
    assertThat(process(Target.DELIVERY, delivery, NOW)).isEqualTo("HELD");

    service.hold(expired.getId(), null);

    assertThat(process(Target.ORDER, expired.getId(), NOW)).isEqualTo("MASKED");
    assertThat(process(Target.DELIVERY, delivery, NOW)).isEqualTo("PURGED");
  }

  @Test
  @DisplayName("삭제가 발송 수신 기록보다 먼저 커밋되면 기다리던 수신 저장을 거절한다")
  void rejectsReceiptWaitingBehindDeletion() throws Exception {
    var expired = closed(NOW.minus(Duration.ofDays(91)));
    UUID id = delivery("SENT", expired);
    var receiver =
        new java.util.concurrent.atomic.AtomicReference<
            java.util.concurrent.Future<NotificationGateway.Receipt>>();
    try (var executor = Executors.newSingleThreadExecutor()) {
      new TransactionTemplate(manager)
          .executeWithoutResult(
              status -> {
                assertThat(store.process(Target.DELIVERY, id, NOW)).isEqualTo("PURGED");
                receiver.set(
                    executor.submit(() -> receipts.save(id, "1".repeat(64), "ACCEPTED", NOW)));
                org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(3))
                    .until(
                        () ->
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%FOR KEY SHARE%'",
                                    Long.class)
                                > 0);
              });
      assertThatThrownBy(() -> receiver.get().get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(NotificationGateway.Failure.class);
    }
    assertThat(count("notification_mock_receipts")).isZero();
    assertThat(count("notification_deliveries")).isZero();
  }

  private static void awaitRelease(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test latch expired");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
  }

  private void sentinels(Order order) throws Exception {
    UUID event = UUID.randomUUID();
    String envelope =
        mapper.writeValueAsString(
            Map.of(
                "eventId",
                event,
                "eventType",
                "RetentionSentinel",
                "eventVersion",
                1,
                "aggregateId",
                order.getId(),
                "aggregateVersion",
                0,
                "occurredAt",
                NOW.toString(),
                "payload",
                Map.of()));
    jdbc.update(
        """
        INSERT INTO commerce.outbox(id,event_type,event_version,aggregate_id,aggregate_version,topic,occurred_at,message_key,payload,raw_body)
        VALUES (?,'RetentionSentinel',1,?,0,'test',?,?,CAST(? AS jsonb),convert_to(?,'UTF8'))
        """,
        event,
        order.getId(),
        time(NOW),
        order.getId().toString(),
        envelope,
        envelope);
    jdbc.update(
        """
        INSERT INTO commerce.inbox(id,consumer,event_id,event_type,event_version,occurred_at,payload,raw_body,body_hash,
          source_topic,source_partition,source_offset,aggregate_id,aggregate_version,status)
        VALUES (?,'commerce-notifications.v1',?,'RetentionSentinel',1,?,CAST(? AS jsonb),convert_to(?,'UTF8'),?,'test',0,0,?,0,'RECEIVED')
        """,
        UUID.randomUUID(),
        event,
        time(NOW),
        envelope,
        envelope,
        "1".repeat(64),
        order.getId());
    jdbc.update(
        """
        INSERT INTO commerce.audit_logs(id,actor_kind,actor_ref,action,aggregate_type,aggregate_id,correlation_id,occurred_at)
        VALUES (?,'SYSTEM','test','ORDER_CLOSED','ORDER',?,'retention-test',?)
        """,
        UUID.randomUUID(),
        order.getId(),
        time(NOW));
    jdbc.update(
        """
        INSERT INTO commerce.idempotency_records(id,actor_kind,actor_id,operation,key_hash,request_hash,resource_id,status,created_at,updated_at)
        VALUES (?,'BUYER',?,'CREATE_ORDER',?,?,?,'COMPLETED',?,?)
        """,
        UUID.randomUUID(),
        order.getBuyerId().toString(),
        "1".repeat(64),
        "1".repeat(64),
        order.getId(),
        time(NOW),
        time(NOW));
  }

  private UUID delivery(String status, Order... linked) {
    UUID id = UUID.randomUUID();
    boolean sale = linked.length > 1;
    UUID recipient = sale ? linked[0].getSellerId() : linked[0].getBuyerId();
    String recipientKind = sale ? "SELLER" : "BUYER";
    String kind = sale ? "SALE_COMPLETED" : "SHIPMENT_STARTED";
    Instant at = NOW.minus(Duration.ofDays(92));
    jdbc.update(
        """
        INSERT INTO commerce.notification_deliveries(id,kind,recipient_kind,recipient_id,channel,dedup_key,item_count,first_occurred_at,total_amount,
          template_version,title,body,status,bucket_start,bucket_end,next_run_at,sent_at,created_at,updated_at,attempt_count)
        VALUES (?,?,?,?,'MOCK_KAKAO',?,?,?,?,'test-v1','test title','test body',?,?,?,?,?,?,?,1)
        """,
        id,
        kind,
        recipientKind,
        recipient,
        id.toString(),
        linked.length,
        time(at),
        sale ? 5000 * linked.length : 0,
        status,
        sale ? time(at) : null,
        sale ? time(at.plusSeconds(60)) : null,
        time(at),
        status.equals("SENT") ? time(at) : null,
        time(at),
        time(at));
    for (Order order : linked) {
      jdbc.update(
          """
          INSERT INTO commerce.notification_items(id,delivery_id,source_service,source_event_id,kind,business_ref,recipient_kind,recipient_id,occurred_at,payload,created_at)
          VALUES (?,?,'commerce',?,?,?,?,?,?,jsonb_build_object('orderId',CAST(? AS text),'totalAmount',5000),?)
          """,
          UUID.randomUUID(),
          id,
          UUID.randomUUID(),
          kind,
          order.getId(),
          recipientKind,
          recipient,
          time(at),
          order.getId().toString(),
          time(at));
    }
    jdbc.update(
        "INSERT INTO commerce.notification_attempts(id,delivery_id,attempt_no,operation,status,started_at,finished_at) VALUES (?,?,1,'SEND','SUCCEEDED',?,?)",
        UUID.randomUUID(),
        id,
        time(at),
        time(at));
    jdbc.update(
        "INSERT INTO commerce.notification_mock_receipts(id,delivery_id,request_hash,result,processed_at) VALUES (?,?,?,'ACCEPTED',?)",
        UUID.randomUUID(),
        id,
        "1".repeat(64),
        time(at));
    return id;
  }

  private Order closed(Instant at) {
    var product =
        Product.register(
            UUID.randomUUID(),
            UUID.fromString("00000000-0000-4000-8000-000000000150"),
            "retention test",
            5000,
            ProductStatus.ON_SALE,
            at.minusSeconds(60));
    var order =
        Order.pendingRegular(
            UUID.randomUUID(),
            product,
            1,
            0,
            Order.ExternalMethod.CREDIT_CARD,
            "test-card",
            17,
            "weekdays-v1",
            List.of(),
            "retention-test-v1",
            at.minusSeconds(60));
    order.requestCancellation(at.minusSeconds(1));
    order.applyPayment(
        UUID.randomUUID(), order.getPaymentCycleId(), Order.Status.CANCELLED, null, at);
    UUID address = UUID.randomUUID();
    var snapshot =
        OrderAddress.snapshot(
            address,
            order.getId(),
            cipher.seal(
                address, new ShippingAddress("test", "010-0000", "12345", "test street", "")),
            at);
    new TransactionTemplate(manager).executeWithoutResult(status -> orders.save(order, snapshot));
    return order;
  }

  private List<UUID> due(Instant now, int limit) {
    return new TransactionTemplate(manager)
        .execute(status -> store.due(Target.ORDER, now, limit, Set.of()));
  }

  private String process(Target target, UUID id, Instant now) {
    return new TransactionTemplate(manager).execute(status -> store.process(target, id, now));
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM commerce." + table, Long.class);
  }

  private static OffsetDateTime time(Instant now) {
    return now.atOffset(ZoneOffset.UTC);
  }

  @TestConfiguration
  static class TimeConfig {
    @Bean
    @Primary
    Clock retentionTestClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}
