package com.commercesettle.commerce.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.commercesettle.commerce.application.port.AddressCipher;
import com.commercesettle.commerce.domain.model.Order;
import com.commercesettle.commerce.domain.model.OrderAddress;
import com.commercesettle.commerce.domain.model.Product;
import com.commercesettle.commerce.domain.model.ProductStatus;
import com.commercesettle.commerce.domain.model.ShippingAddress;
import com.commercesettle.commerce.domain.repository.OrderRepository;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "app.auth.session-epoch=00000000-0000-4000-8000-000000000000",
      "app.auth.rate-limit.identifier-hmac-key=order-storage-test-hmac-key-32bytes",
      "app.auth.password-reset.delivery-key=order-storage-test-delivery-key-32bytes",
      "app.address.encryption-key=order-storage-test-address-key-32bytes"
    })
@Testcontainers
@Timeout(30)
class OrderRepositoryIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  private static final Instant NOW = Instant.parse("2026-10-30T10:00:00.123456789Z");
  private static final ShippingAddress SHIPPING =
      new ShippingAddress("시험 수령인", "010-1234-5678", "06236", "서울 강남구 시험로 1", "101호");

  @Autowired OrderRepository orders;
  @Autowired SpringDataOrderRepository storedOrders;
  @Autowired AddressCipher cipher;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired DataSource dataSource;

  @Test
  @DisplayName("실제 DB 재조회로 가격·결제 구성·달력·암호화 주소와 마이크로초 시각을 보존한다")
  void roundTripsOrderAndEncryptedAddress() {
    Order order = order(UUID.randomUUID(), 2_000, NOW);
    OrderAddress address = address(order, UUID.randomUUID());

    save(order, address);
    Order found = orders.findByBuyerAndId(order.getBuyerId(), order.getId()).orElseThrow();
    OrderAddress foundAddress =
        orders.findAddressByBuyerAndOrderId(order.getBuyerId(), order.getId()).orElseThrow();

    assertThat(found.getAmounts().externalAmount()).isEqualTo(8_000);
    assertThat(found.getAmounts().mileageAmount()).isEqualTo(2_000);
    assertThat(found.getPaymentCycleId()).isEqualTo(order.getPaymentCycleId());
    assertThat(found.getCreatedAt()).isEqualTo(Instant.parse("2026-10-30T10:00:00.123456Z"));
    assertThat(found.getUpdatedAt()).isEqualTo(found.getCreatedAt());
    assertThat(found.getPaymentWaitUntil()).isEqualTo(found.getCreatedAt().plusSeconds(600));
    assertThat(found.getVersion()).isZero();
    assertThat(found.getDelivery().getDeliveryBusinessDays())
        .isEqualTo(order.getDelivery().getDeliveryBusinessDays());
    assertThat(found.getDelivery().getDeliveryCalendar())
        .isEqualTo(order.getDelivery().getDeliveryCalendar());
    assertThat(cipher.open(foundAddress.getId(), foundAddress.getSealed())).isEqualTo(SHIPPING);
    assertThatThrownBy(() -> cipher.open(UUID.randomUUID(), foundAddress.getSealed()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("마일리지 전액 결제는 DB에도 외부 수단·계정 없이 저장된다")
  void roundTripsFullMileageOrder() {
    Order order = order(UUID.randomUUID(), 10_000, NOW);

    save(order, address(order, UUID.randomUUID()));

    Order found = orders.findByBuyerAndId(order.getBuyerId(), order.getId()).orElseThrow();
    assertThat(found.getAmounts().externalAmount()).isZero();
    assertThat(found.getExternalMethod()).isNull();
    assertThat(found.getMethodAccountRef()).isNull();
  }

  @Test
  @DisplayName("주문·주소·목록 조회에서 다른 구매자의 자료를 반환하지 않는다")
  void scopesReadsAndPaginationToBuyer() {
    UUID buyer = UUID.randomUUID();
    Order older = order(buyer, 0, NOW.minusSeconds(1));
    Order sameTimeA = order(buyer, 0, NOW);
    Order sameTimeB = order(buyer, 0, NOW);
    Order other = order(UUID.randomUUID(), 0, NOW.plusSeconds(1));
    for (Order order : List.of(older, sameTimeA, sameTimeB, other)) {
      save(order, address(order, UUID.randomUUID()));
    }
    List<UUID> expected =
        List.of(sameTimeA.getId(), sameTimeB.getId()).stream()
            .sorted(Comparator.comparing(UUID::toString).reversed())
            .toList();

    var page = orders.findByBuyer(buyer, 0, 2);

    assertThat(page.items()).extracting(Order::getId).containsExactlyElementsOf(expected);
    assertThat(page.totalElements()).isEqualTo(3);
    assertThat(orders.findByBuyer(buyer, 1, 2).items())
        .extracting(Order::getId)
        .containsExactly(older.getId());
    assertThat(orders.findByBuyerAndId(buyer, other.getId())).isEmpty();
    assertThat(orders.findAddressByBuyerAndOrderId(buyer, other.getId())).isEmpty();
  }

  @Test
  @DisplayName("큰 offset용 SQL도 구매자·정렬·페이지와 금액·달력 사본을 보존한다")
  void nativeOffsetQueryPreservesOwnedOrderSnapshots() {
    UUID buyer = UUID.randomUUID();
    Order older = order(buyer, 2_000, NOW.minusSeconds(1));
    Order sameTimeA = order(buyer, 2_000, NOW);
    Order sameTimeB = order(buyer, 2_000, NOW);
    Order other = order(UUID.randomUUID(), 0, NOW.plusSeconds(1));
    for (Order order : List.of(older, sameTimeA, sameTimeB, other)) {
      save(order, address(order, UUID.randomUUID()));
    }
    List<UUID> newestIds =
        List.of(sameTimeA.getId(), sameTimeB.getId()).stream()
            .sorted(Comparator.comparing(UUID::toString).reversed())
            .toList();

    var newest = storedOrders.findByBuyerWithOffset(buyer, 0L, 2);
    var next = storedOrders.findByBuyerWithOffset(buyer, 2L, 1);

    assertThat(newest).extracting(Order::getId).containsExactlyElementsOf(newestIds);
    assertThat(next).extracting(Order::getId).containsExactly(older.getId());
    assertThat(storedOrders.countByBuyerId(buyer)).isEqualTo(3);
    Order found = next.getFirst();
    assertThat(found.getBuyerId()).isEqualTo(buyer);
    assertThat(found.getAmounts()).isEqualTo(older.getAmounts());
    assertThat(found.getExternalMethod()).isEqualTo(Order.ExternalMethod.CREDIT_CARD);
    assertThat(found.getDelivery().getDeliveryCalendar())
        .isEqualTo(older.getDelivery().getDeliveryCalendar());
  }

  @Test
  @DisplayName("호출자가 롤백하면 주문과 주소 사본도 함께 사라진다")
  void rollsBackWithCallerTransaction() {
    Order order = order(UUID.randomUUID(), 0, NOW);
    OrderAddress address = address(order, UUID.randomUUID());

    assertThatThrownBy(
            () ->
                tx().executeWithoutResult(
                        status -> {
                          orders.save(order, address);
                          throw new IllegalStateException("simulated caller failure");
                        }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(orders.findByBuyerAndId(order.getBuyerId(), order.getId())).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM commerce.order_addresses WHERE order_id=?",
                Long.class,
                order.getId()))
        .isZero();
  }

  @Test
  @DisplayName("주소 저장이 실패하면 먼저 저장한 주문도 롤백한다")
  void rollsBackOrderWhenAddressInsertFails() {
    Order original = order(UUID.randomUUID(), 0, NOW);
    UUID addressId = UUID.randomUUID();
    save(original, address(original, addressId));
    Order next = order(UUID.randomUUID(), 0, NOW);

    assertThatThrownBy(() -> save(next, address(next, addressId)))
        .isInstanceOf(DataAccessException.class);

    assertThat(orders.findByBuyerAndId(next.getBuyerId(), next.getId())).isEmpty();
    assertThat(orders.findAddressByBuyerAndOrderId(original.getBuyerId(), original.getId()))
        .isPresent();
  }

  @Test
  @DisplayName("한 주문에 두 주소 사본을 저장할 수 없다")
  void rejectsSecondAddressForOrder() {
    Order order = order(UUID.randomUUID(), 0, NOW);
    save(order, address(order, UUID.randomUUID()));

    assertThatThrownBy(() -> save(order, address(order, UUID.randomUUID())))
        .isInstanceOf(DataAccessException.class);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM commerce.order_addresses WHERE order_id=?",
                Long.class,
                order.getId()))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("트랜잭션 밖 저장과 다른 주문의 주소 연결을 거절한다")
  void requiresCallerTransactionAndMatchingAddress() {
    Order order = order(UUID.randomUUID(), 0, NOW);
    OrderAddress address = address(order, UUID.randomUUID());

    assertThatThrownBy(() -> orders.save(order, address))
        .isInstanceOf(IllegalTransactionStateException.class);
    Order other = order(UUID.randomUUID(), 0, NOW);
    assertThatThrownBy(() -> save(other, address))
        .isInstanceOf(DataAccessException.class)
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
    assertThat(orders.findByBuyerAndId(order.getBuyerId(), order.getId())).isEmpty();
    assertThat(orders.findByBuyerAndId(other.getBuyerId(), other.getId())).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "quantity=11",
        "gross_amount=1",
        "total_amount=1",
        "mileage_amount=-1",
        "external_method=NULL",
        "method_account_ref=NULL",
        "delivered_at=created_at",
        "refund_deadline_at=created_at"
      })
  @DisplayName("직접 SQL도 주문 금액·수량·외부 수단·배송 시각의 불변식을 우회하지 못한다")
  void databaseRejectsInvalidOrderValues(String assignment) {
    Order order = order(UUID.randomUUID(), 2_000, NOW);
    save(order, address(order, UUID.randomUUID()));

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE commerce.orders SET " + assignment + " WHERE id=?", order.getId()))
        .isInstanceOf(DataAccessException.class);

    assertThat(
            orders.findByBuyerAndId(order.getBuyerId(), order.getId()).orElseThrow().getAmounts())
        .isEqualTo(order.getAmounts());
  }

  @Test
  @DisplayName("다른 트랜잭션의 중복 키 확정을 기다리다 상한에 도달하면 주문까지 롤백한다")
  void boundsLockWaitAndRollsBackOrder() throws Exception {
    Order original = order(UUID.randomUUID(), 0, NOW);
    OrderAddress originalAddress = address(original, UUID.randomUUID());
    save(original, originalAddress);
    UUID blockedAddressId = UUID.randomUUID();
    Order waiting = order(UUID.randomUUID(), 0, NOW);

    try (var holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (var statement =
          holder.prepareStatement(
              """
          INSERT INTO commerce.order_addresses
            (id,order_id,recipient_ciphertext,phone_ciphertext,address_ciphertext,created_at,updated_at,version)
          SELECT ?,?,recipient_ciphertext,phone_ciphertext,address_ciphertext,created_at,updated_at,0
          FROM commerce.order_addresses WHERE id=?
          """)) {
        statement.setObject(1, blockedAddressId);
        statement.setObject(2, UUID.randomUUID());
        statement.setObject(3, originalAddress.getId());
        assertThat(statement.executeUpdate()).isEqualTo(1);
      }
      try {
        Instant started = Instant.now();
        Throwable failure = catchThrowable(() -> save(waiting, address(waiting, blockedAddressId)));

        assertThat(failure)
            .isInstanceOf(DataAccessException.class)
            .hasRootCauseInstanceOf(SQLException.class);
        Throwable root = failure;
        while (root.getCause() != null) {
          root = root.getCause();
        }
        assertThat(((SQLException) root).getSQLState()).isEqualTo("55P03");
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(6));
        assertThat(orders.findByBuyerAndId(waiting.getBuyerId(), waiting.getId())).isEmpty();
      } finally {
        holder.rollback();
      }
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM commerce.order_addresses WHERE id=?",
                Long.class,
                blockedAddressId))
        .isZero();
  }

  private void save(Order order, OrderAddress address) {
    tx().executeWithoutResult(status -> orders.save(order, address));
  }

  private TransactionTemplate tx() {
    return new TransactionTemplate(transactions);
  }

  private OrderAddress address(Order order, UUID addressId) {
    return OrderAddress.snapshot(addressId, order.getId(), cipher.seal(addressId, SHIPPING), NOW);
  }

  private static Order order(UUID buyerId, long mileage, Instant now) {
    Product product =
        Product.register(
            UUID.randomUUID(), UUID.randomUUID(), "시험 상품", 5_000, ProductStatus.ON_SALE, now);
    return Order.pendingRegular(
        buyerId,
        product,
        2,
        mileage,
        mileage == 10_000 ? null : Order.ExternalMethod.CREDIT_CARD,
        mileage == 10_000 ? null : "test-card",
        17,
        "holidays-v1",
        List.of(LocalDate.of(2026, 12, 25)),
        "delivery-daily-v1",
        now);
  }
}
