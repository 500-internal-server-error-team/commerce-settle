package com.commercesettle.commerce.application.service;

import static com.commercesettle.commerce.support.ConcurrentTransactions.holdTransaction;
import static com.commercesettle.commerce.support.ConcurrentTransactions.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.domain.exception.InsufficientInventoryException;
import com.commercesettle.commerce.domain.exception.InvalidReservationTransitionException;
import com.commercesettle.commerce.domain.exception.ProductNotFoundException;
import com.commercesettle.commerce.domain.model.InventoryReservation.Outcome;
import com.commercesettle.commerce.domain.model.ProductStatus;
import com.commercesettle.commerce.support.ConcurrentTransactions.HeldTransaction;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL에서 주문 트랜잭션에 참여한 재고 예약·확정·해제·복원의 커밋 결과와 잠금을 확인한다. */
// 인증 설정은 필수 기동 값이다. 재고 예약 시험은 Redis 인증 경로를 호출하지 않는다.
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "app.auth.session-epoch=00000000-0000-4000-8000-000000000000",
      "app.auth.rate-limit.identifier-hmac-key=inventory-reservation-test-hmac-key-32b",
      "app.auth.password-reset.delivery-key=inventory-reservation-test-delivery-key-32b",
      "app.address.encryption-key=inventory-reservation-test-address-key-32b"
    })
@Testcontainers
class InventoryReservationIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  private static final Instant EXPIRES = Instant.parse("2026-10-30T01:10:00Z");

  @Autowired InventoryReservationService reservations;
  @Autowired ProductCatalogService catalog;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;

  @Test
  @DisplayName("주문 트랜잭션 밖에서 호출하면 거절한다")
  void rejectsCallOutsideTransaction() {
    UUID productId = stockedProduct(5);

    assertThatThrownBy(() -> reservations.lockInventoryAndReadProduct(productId))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThatThrownBy(
            () -> reservations.reserve(UUID.randomUUID(), productId, 1, UUID.randomUUID(), EXPIRES))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(count("inventory_reservations where product_id = ?", productId)).isZero();
  }

  @Test
  @DisplayName("재고 행을 잠근 뒤 상품을 읽고, 없는 상품은 찾을 수 없다고 거절한다")
  void locksInventoryAndReadsProduct() {
    UUID productId = stockedProduct(5);
    UUID missing = UUID.randomUUID();

    assertThat(inOrder(() -> reservations.lockInventoryAndReadProduct(productId).getId()))
        .isEqualTo(productId);
    assertThatThrownBy(() -> inOrder(() -> reservations.lockInventoryAndReadProduct(missing)))
        .isInstanceOf(ProductNotFoundException.class);
  }

  @Test
  @DisplayName("예약은 재고 수량·예약 행·회차를 업무 참조로 한 이력을 함께 커밋한다")
  void commitsReservationWithInventoryAndMovement() {
    UUID productId = stockedProduct(10);
    UUID orderId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();

    Outcome outcome = inOrder(() -> reservations.reserve(orderId, productId, 3, cycleId, EXPIRES));

    assertThat(outcome).isEqualTo(Outcome.APPLIED);
    assertQuantities(productId, 7, 3, 0);
    assertThat(reservationStatus(orderId)).isEqualTo("RESERVED");
    assertThat(
            count(
                "inventory_movements where product_id = ? and operation = 'ORDER_RESERVE'"
                    + " and business_ref = ? and available_delta = -3 and reserved_delta = 3",
                productId,
                cycleId))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("주문 트랜잭션이 롤백되면 예약·수량·이력도 남지 않는다")
  void rollsBackWithCallerTransaction() {
    UUID productId = stockedProduct(10);

    tx.executeWithoutResult(
        status -> {
          reservations.reserve(UUID.randomUUID(), productId, 3, UUID.randomUUID(), EXPIRES);
          status.setRollbackOnly();
        });

    assertQuantities(productId, 10, 0, 0);
    assertThat(count("inventory_reservations where product_id = ?", productId)).isZero();
    assertThat(count("inventory_movements where product_id = ?", productId)).isEqualTo(1);
  }

  @Test
  @DisplayName("같은 주문의 예약 재처리는 다시 반영하지 않고, 다른 요청이면 거절한다")
  void replaysSameReservationOnly() {
    UUID productId = stockedProduct(10);
    UUID orderId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    inOrder(() -> reservations.reserve(orderId, productId, 3, cycleId, EXPIRES));

    Outcome replay = inOrder(() -> reservations.reserve(orderId, productId, 3, cycleId, EXPIRES));

    assertThat(replay).isEqualTo(Outcome.ALREADY_APPLIED);
    assertThatThrownBy(
            () -> inOrder(() -> reservations.reserve(orderId, productId, 4, cycleId, EXPIRES)))
        .isInstanceOf(IllegalStateException.class);
    assertQuantities(productId, 7, 3, 0);
  }

  @Test
  @DisplayName("거절·재시도·확정·반품 입고를 거치면 회차별 이력이 남고 복원은 한 번만 반영한다")
  void followsRetryConsumeAndRestoreOnce() {
    UUID productId = stockedProduct(10);
    UUID orderId = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    inOrder(() -> reservations.reserve(orderId, productId, 3, first, EXPIRES));

    inOrder(() -> reservations.release(orderId, first));
    inOrder(() -> reservations.reserveAgain(orderId, second, EXPIRES.plusSeconds(600)));
    Outcome late = inOrder(() -> reservations.release(orderId, first));
    inOrder(() -> reservations.consume(orderId, second));
    Outcome repeated = inOrder(() -> reservations.consume(orderId, second));
    Outcome returned = inOrder(() -> reservations.restoreForReturn(orderId, UUID.randomUUID()));
    Outcome refunded = inOrder(() -> reservations.restoreForRefund(orderId, UUID.randomUUID()));

    assertThat(late).isEqualTo(Outcome.STALE_CYCLE);
    assertThat(repeated).isEqualTo(Outcome.ALREADY_APPLIED);
    assertThat(returned).isEqualTo(Outcome.APPLIED);
    assertThat(refunded).isEqualTo(Outcome.ALREADY_APPLIED);
    assertQuantities(productId, 10, 0, 0);
    assertThat(reservationStatus(orderId)).isEqualTo("RESTORED");
    assertThat(
            jdbc.queryForList(
                "select operation from commerce.inventory_movements"
                    + " where product_id = ? and operation <> 'SELLER_ADJUSTMENT'",
                String.class,
                productId))
        .containsExactlyInAnyOrder(
            "ORDER_RESERVE", "ORDER_RELEASE", "ORDER_RESERVE", "ORDER_CONSUME", "RETURN_RESTORE");
  }

  @Test
  @DisplayName("금지된 전이는 거절하고 예약 상태·수량·이력을 바꾸지 않는다")
  void rejectsForbiddenTransitionWithoutSideEffects() {
    UUID productId = stockedProduct(10);
    UUID orderId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    inOrder(() -> reservations.reserve(orderId, productId, 3, cycleId, EXPIRES));
    inOrder(() -> reservations.consume(orderId, cycleId));
    long movements = count("inventory_movements where product_id = ?", productId);

    assertThatThrownBy(() -> inOrder(() -> reservations.release(orderId, cycleId)))
        .isInstanceOf(InvalidReservationTransitionException.class);

    assertThat(reservationStatus(orderId)).isEqualTo("CONSUMED");
    assertQuantities(productId, 7, 0, 3);
    assertThat(count("inventory_movements where product_id = ?", productId)).isEqualTo(movements);
  }

  @Test
  @DisplayName("동시 예약은 가용 수량까지만 성공하고 초과 판매하지 않는다")
  void doesNotOversellUnderConcurrentReservations() throws Exception {
    UUID productId = stockedProduct(5);

    List<Object> results =
        runConcurrently(
            10,
            () ->
                inOrder(
                    () ->
                        reservations.reserve(
                            UUID.randomUUID(), productId, 1, UUID.randomUUID(), EXPIRES)));

    assertThat(results).filteredOn(Outcome.APPLIED::equals).hasSize(5);
    assertThat(results).filteredOn(InsufficientInventoryException.class::isInstance).hasSize(5);
    assertQuantities(productId, 0, 5, 0);
    assertThat(count("inventory_reservations where product_id = ?", productId)).isEqualTo(5);
  }

  @Test
  @Timeout(30)
  @DisplayName("다른 트랜잭션이 재고 행을 잠그고 있으면 대기 상한 안에 거절하고 예약을 바꾸지 않는다")
  void rejectsWithinLockTimeoutWhileInventoryIsLocked() throws Exception {
    UUID productId = stockedProduct(10);
    UUID orderId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    inOrder(() -> reservations.reserve(orderId, productId, 3, cycleId, EXPIRES));

    try (HeldTransaction held =
        holdTransaction(
            tx,
            () ->
                jdbc.queryForList(
                    "select id from commerce.product_inventory where product_id = ? for update",
                    productId))) {
      long started = System.nanoTime();
      assertThatThrownBy(() -> inOrder(() -> reservations.consume(orderId, cycleId)))
          .isInstanceOf(PessimisticLockingFailureException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    assertThat(reservationStatus(orderId)).isEqualTo("RESERVED");
    assertQuantities(productId, 7, 3, 0);
  }

  /** 주문 응용 서비스의 트랜잭션을 대신한다. */
  private <T> T inOrder(Supplier<T> work) {
    return tx.execute(status -> work.get());
  }

  private UUID stockedProduct(long quantity) {
    UUID seller = UUID.randomUUID();
    UUID productId = catalog.register(seller, key(), "키보드", 39_000, ProductStatus.ON_SALE).getId();
    catalog.adjustAvailable(seller, productId, key(), quantity, 0, "입고");
    return productId;
  }

  private void assertQuantities(UUID productId, long available, long reserved, long sold) {
    assertThat(
            jdbc.queryForMap(
                "select available_qty, reserved_qty, sold_qty from commerce.product_inventory"
                    + " where product_id = ?",
                productId))
        .containsEntry("available_qty", available)
        .containsEntry("reserved_qty", reserved)
        .containsEntry("sold_qty", sold);
  }

  private String reservationStatus(UUID orderId) {
    return jdbc.queryForObject(
        "select status from commerce.inventory_reservations where order_id = ?",
        String.class,
        orderId);
  }

  private long count(String fromWhere, Object... args) {
    return jdbc.queryForObject("select count(*) from commerce." + fromWhere, Long.class, args);
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }
}
