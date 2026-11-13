package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.domain.model.Order;
import com.commercesettle.commerce.domain.model.Product;
import com.commercesettle.commerce.domain.model.ProductStatus;
import com.commercesettle.commerce.domain.repository.OrderRepository;
import com.commercesettle.commerce.support.ConcurrentTransactions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "app.auth.session-epoch=00000000-0000-4000-8000-000000000000",
      "app.auth.rate-limit.identifier-hmac-key=demo-shipment-test-hmac-key-32bytes",
      "app.auth.password-reset.delivery-key=demo-shipment-test-delivery-key-32bytes",
      "app.address.encryption-key=demo-shipment-test-address-key-32bytes",
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123",
      "app.order-workflow.auto-shipment-enabled=true"
    })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(DemoAutoShipmentIntegrationTest.Beans.class)
class DemoAutoShipmentIntegrationTest {
  private static final Instant NOW = Instant.parse("2026-11-06T02:00:00Z");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  @Autowired OrderWorkflowService workflow;
  @Autowired OrderRefundService refunds;
  @Autowired OrderRepository orders;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired TestClock clock;

  @BeforeEach
  void reset() {
    clock.now = NOW;
    jdbc.execute(
        "TRUNCATE commerce.orders,commerce.audit_logs,commerce.outbox,commerce.notification_items,commerce.notification_deliveries");
  }

  @Test
  @DisplayName("판매자 없이 자동 발송하고 배송·환불 기한 뒤에만 구매확정 이벤트를 저장한다")
  void shipsAndConfirmsWithOriginalDeadlines() {
    Order order = save(Order.Status.PAID);

    assertThat(workflow.due(100, null)).containsExactly(order.getId());
    workflow.advance(order.getId());
    Order shipped = current(order);

    assertThat(shipped.getFulfillmentStatus()).isEqualTo(Order.FulfillmentStatus.SHIPPED);
    assertThat(shipped.getShippedAt()).isEqualTo(NOW);
    // 금요일 11시 발송: 주말을 제외한 2~3영업일은 화요일 또는 수요일이다.
    assertThat(shipped.getDeliveryDueAt())
        .isEqualTo(NOW.plusSeconds((shipped.getDelivery().getDeliveryBusinessDays() + 2) * 86400L));
    assertThat(shipped.getRefundDeadlineAt()).isNull();
    assertThat(count("commerce.audit_logs", "actor_kind='SYSTEM' AND actor_ref='demo-seller'"))
        .isEqualTo(1);

    clock.now = shipped.getDeliveryDueAt().minusNanos(1000);
    workflow.advance(order.getId());
    assertThat(current(order).getFulfillmentStatus()).isEqualTo(Order.FulfillmentStatus.SHIPPED);
    clock.now = shipped.getDeliveryDueAt();
    workflow.advance(order.getId());
    assertThat(current(order).getRefundDeadlineAt())
        .isEqualTo(shipped.getDeliveryDueAt().plusSeconds(604800));
    clock.now = current(order).getRefundDeadlineAt();
    workflow.advance(order.getId());
    assertThat(current(order).getStatus()).isEqualTo(Order.Status.PAID);
    clock.now = clock.now.plusNanos(1000);
    workflow.advance(order.getId());
    workflow.advance(order.getId());

    assertThat(current(order).getStatus()).isEqualTo(Order.Status.PURCHASE_CONFIRMED);
    assertThat(count("commerce.outbox", "event_type='PurchaseConfirmed'")).isEqualTo(1);
    assertThat(count("commerce.notification_items", "kind='SHIPMENT_STARTED'")).isEqualTo(1);
  }

  @Test
  @DisplayName("미결제·거절·환불 중인 주문은 자동 발송 후보와 직접 실행에서 제외한다")
  void excludesUnpaidDeclinedAndRefundingOrders() {
    Order unpaid = save(Order.Status.PAYMENT_PENDING);
    Order declined = save(Order.Status.DECLINED);
    Order refunding = save(Order.Status.PAID);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            ignored -> {
              Order found = orders.findLockedById(refunding.getId()).orElseThrow();
              found.beginRefund(true, NOW);
              orders.save(found);
            });

    assertThat(workflow.due(100, null)).isEmpty();
    for (Order order : List.of(unpaid, declined, refunding)) {
      workflow.advance(order.getId());
      assertThat(current(order).getFulfillmentStatus())
          .isEqualTo(Order.FulfillmentStatus.UNSHIPPED);
    }
    assertThat(count("commerce.audit_logs", "reason_code='SHIPMENT_STARTED'")).isZero();
  }

  @Test
  @DisplayName("판매자 대역도 비활성 판매자의 주문은 발송하지 않는다")
  void respectsSellerAccountState() {
    Order order = save(Order.Status.PAID);
    jdbc.update(
        "UPDATE commerce.seller_accounts SET status='SUSPENDED' WHERE id=?", order.getSellerId());

    assertThatThrownBy(() -> workflow.advance(order.getId()))
        .isInstanceOf(
            com.commercesettle.commerce.application.exception.AuthenticationFailedException.class);

    assertThat(current(order).getFulfillmentStatus()).isEqualTo(Order.FulfillmentStatus.UNSHIPPED);
    assertThat(count("commerce.audit_logs", "reason_code='SHIPMENT_STARTED'")).isZero();
  }

  @Test
  @DisplayName("자동 발송은 커서 뒤의 미발송 주문을 찾아 재실행·수동 발송과 경합해도 한 번만 저장한다")
  void paginatesAndSerializesConcurrentDispatch() throws Exception {
    Order first = save(Order.Status.PAID);
    Order second = save(Order.Status.PAID);
    var expected =
        List.of(first.getId(), second.getId()).stream()
            .sorted(java.util.Comparator.comparing(UUID::toString))
            .toList();
    assertThat(workflow.due(1, null)).containsExactly(expected.getFirst());
    assertThat(workflow.due(1, expected.getFirst())).containsExactly(expected.getLast());
    var turn = new AtomicInteger();

    var outcomes =
        ConcurrentTransactions.runConcurrently(
            2,
            () -> {
              if (turn.getAndIncrement() == 0) workflow.advance(first.getId());
              else
                workflow.ship(
                    first.getSellerId(), first.getId(), "demo-manual-race", 0, "test-parcel");
              return null;
            });

    assertThat(outcomes.stream().filter(java.util.Objects::isNull).count())
        .isGreaterThanOrEqualTo(1);
    assertThat(outcomes.stream().filter(java.util.Objects::nonNull))
        .allMatch(
            failure ->
                failure
                    instanceof
                    com.commercesettle.commerce.domain.exception.VersionConflictException);
    assertThat(current(first).getFulfillmentStatus()).isEqualTo(Order.FulfillmentStatus.SHIPPED);
    assertThat(count("commerce.audit_logs", "reason_code='SHIPMENT_STARTED'")).isEqualTo(1);
    assertThat(count("commerce.notification_items", "kind='SHIPMENT_STARTED'")).isEqualTo(1);
    assertThat(workflow.due(100, null)).containsExactly(second.getId());
    var shipped = current(first);

    workflow.advance(first.getId());

    assertThat(current(first).getVersion()).isEqualTo(shipped.getVersion());
    assertThat(current(first).getShippedAt()).isEqualTo(shipped.getShippedAt());
    assertThat(count("commerce.audit_logs", "reason_code='SHIPMENT_STARTED'")).isEqualTo(1);
    assertThat(count("commerce.notification_items", "kind='SHIPMENT_STARTED'")).isEqualTo(1);
  }

  @Test
  @DisplayName("미발송 환불 접수와 자동 발송이 경합하면 먼저 잠근 전이만 저장한다")
  void serializesRefundRequestAgainstAutomaticShipment() throws Exception {
    Order order = save(Order.Status.PAID);
    var turn = new AtomicInteger();

    var outcomes =
        ConcurrentTransactions.runConcurrently(
            2,
            () -> {
              if (turn.getAndIncrement() == 0) workflow.advance(order.getId());
              else
                refunds.create(
                    order.getBuyerId(),
                    order.getId(),
                    "demo-refund-race",
                    0,
                    com.commercesettle.commerce.domain.model.OrderRefundRequest.Kind.CANCEL,
                    "경합 시험");
              return null;
            });

    assertThat(outcomes.stream().filter(java.util.Objects::isNull).count())
        .isGreaterThanOrEqualTo(1);
    assertThat(outcomes.stream().filter(java.util.Objects::nonNull))
        .allMatch(
            failure ->
                failure
                    instanceof
                    com.commercesettle.commerce.domain.exception.VersionConflictException);
    Order found = current(order);
    if (found.getStatus() == Order.Status.REFUND_PENDING) {
      assertThat(found.getFulfillmentStatus()).isEqualTo(Order.FulfillmentStatus.UNSHIPPED);
      assertThat(count("commerce.audit_logs", "reason_code='SHIPMENT_STARTED'")).isZero();
      assertThat(count("commerce.order_refund_requests", "order_id='" + order.getId() + "'"))
          .isEqualTo(1);
    } else {
      assertThat(found.getStatus()).isEqualTo(Order.Status.PAID);
      assertThat(found.getFulfillmentStatus()).isEqualTo(Order.FulfillmentStatus.SHIPPED);
      assertThat(count("commerce.audit_logs", "reason_code='SHIPMENT_STARTED'")).isEqualTo(1);
      assertThat(count("commerce.order_refund_requests", "order_id='" + order.getId() + "'"))
          .isZero();
    }
  }

  @Test
  @DisplayName("자동 발송의 알림 저장이 실패하면 발송 시각·상태·감사도 함께 롤백한다")
  void rollsBackShipmentAndAuditWhenNotificationFails() {
    Order order = save(Order.Status.PAID);
    jdbc.execute(
        "CREATE FUNCTION commerce.reject_demo_shipment() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_demo_shipment BEFORE INSERT ON commerce.notification_items FOR EACH ROW EXECUTE FUNCTION commerce.reject_demo_shipment()");
    try {
      assertThatThrownBy(() -> workflow.advance(order.getId()))
          .isInstanceOf(RuntimeException.class);
    } finally {
      jdbc.execute("DROP TRIGGER reject_demo_shipment ON commerce.notification_items");
      jdbc.execute("DROP FUNCTION commerce.reject_demo_shipment()");
    }

    assertThat(current(order).getFulfillmentStatus()).isEqualTo(Order.FulfillmentStatus.UNSHIPPED);
    assertThat(current(order).getShippedAt()).isNull();
    assertThat(count("commerce.audit_logs", "reason_code='SHIPMENT_STARTED'")).isZero();
    assertThat(workflow.due(100, null)).containsExactly(order.getId());
  }

  private Order save(Order.Status status) {
    UUID seller = UUID.randomUUID(), buyer = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO commerce.buyer_accounts(id,email_normalized,password_hash,display_name,status,created_at,updated_at,version) VALUES (?,?,'hash','buyer','ACTIVE',now(),now(),0)",
        buyer,
        buyer + "@example.com");
    jdbc.update(
        "INSERT INTO commerce.seller_accounts(id,email_normalized,password_hash,display_name,status,payout_account_ref,created_at,updated_at,version) VALUES (?,?,'hash','seller','ACTIVE','test-account',now(),now(),0)",
        seller,
        seller + "@example.com");
    Product product =
        Product.register(UUID.randomUUID(), seller, "자동 발송 시험", 1000, ProductStatus.ON_SALE, NOW);
    Order order =
        Order.pendingRegular(
            buyer,
            product,
            1,
            0,
            Order.ExternalMethod.CREDIT_CARD,
            "test-card",
            17,
            "weekdays-v1",
            List.of(),
            "delivery-daily-v1",
            NOW);
    if (status != Order.Status.PAYMENT_PENDING)
      order.applyPayment(UUID.randomUUID(), order.getPaymentCycleId(), status, NOW, NOW);
    new TransactionTemplate(transactions).executeWithoutResult(ignored -> orders.save(order));
    return order;
  }

  private Order current(Order order) {
    return orders.findById(order.getId()).orElseThrow();
  }

  private long count(String table, String predicate) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + predicate, Long.class);
  }

  @TestConfiguration
  static class Beans {
    @Bean
    @Primary
    TestClock testClock() {
      return new TestClock();
    }
  }

  static class TestClock extends Clock {
    volatile Instant now = NOW;

    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }
}
