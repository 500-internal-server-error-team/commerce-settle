package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import com.commercesettle.commerce.application.dto.CreateOrderCommand;
import com.commercesettle.commerce.application.dto.OrderView;
import com.commercesettle.commerce.application.exception.IdempotencyConflictException;
import com.commercesettle.commerce.application.exception.OrderStubException;
import com.commercesettle.commerce.application.port.OrderInventory;
import com.commercesettle.commerce.domain.exception.ProductNotFoundException;
import com.commercesettle.commerce.domain.exception.VersionConflictException;
import com.commercesettle.commerce.domain.model.Product;
import com.commercesettle.commerce.domain.model.ProductStatus;
import com.commercesettle.commerce.infrastructure.config.OrderStubConfiguration;
import com.commercesettle.commerce.presentation.controller.OrderStubController;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OrderStubServiceTest {
  private static final UUID BUYER = id(1);
  private static final UUID OTHER = id(11);
  private static final Instant NOW = Instant.parse("2026-01-20T01:00:00.123456789Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private final OrderStubConfiguration configuration = new OrderStubConfiguration();
  private final OrderStubService orders =
      configuration.orderStubService(CLOCK, "67", configuration.orderInventory());

  @Test
  void samplesAreStableVariedAndFollowAmountAndTimelinePolicies() throws Exception {
    OrderStubConfiguration restarted = new OrderStubConfiguration();
    List<OrderView> samples = orders.list(BUYER, 0, 100).items();
    assertThat(samples)
        .hasSize(10)
        .isEqualTo(
            restarted
                .orderStubService(CLOCK, "67", restarted.orderInventory())
                .list(BUYER, 0, 100)
                .items());
    assertThat(samples).extracting(OrderView::deliveryBusinessDays).contains(2, 3);
    assertThat(samples)
        .extracting(OrderView::status)
        .contains("PAYMENT_PENDING", "PAID", "PURCHASE_CONFIRMED");
    assertThat(samples)
        .extracting(OrderView::fulfillmentStatus)
        .contains("UNSHIPPED", "SHIPPED", "DELIVERED");
    assertThat(samples).extracting(OrderView::productId).contains(id(3), id(23), id(33));
    for (OrderView order : samples) {
      assertThat(orders.get(BUYER, order.id())).isEqualTo(order);
      assertThat(order.settlementBaseAmount())
          .isEqualTo(order.grossAmount() - order.sellerCouponAmount());
      assertThat(order.couponAmount())
          .isEqualTo(order.sellerCouponAmount() + order.platformCouponAmount());
      assertThat(order.totalAmount())
          .isEqualTo(order.settlementBaseAmount() - order.platformCouponAmount());
      assertThat(order.externalAmount() + order.mileageAmount()).isEqualTo(order.totalAmount());
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(("67:" + order.id()).getBytes(StandardCharsets.UTF_8));
      assertThat(order.deliveryBusinessDays()).isEqualTo((digest[31] & 1) == 0 ? 2 : 3);
      assertThat(order.createdAt().getNano() % 1_000).isZero();
      if (order.shippedAt() != null) {
        assertThat(order.paymentConfirmedAt()).isBefore(order.shippedAt());
        var day = order.shippedAt().atZone(ZoneId.of("Asia/Seoul"));
        int weekdays = 0;
        while (day.toInstant().isBefore(order.deliveryDueAt())) {
          day = day.plusDays(1);
          if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
            weekdays++;
          }
        }
        assertThat(weekdays).isEqualTo(order.deliveryBusinessDays());
        assertThat(day.toInstant()).isEqualTo(order.deliveryDueAt());
      }
      if (order.deliveredAt() != null) {
        assertThat(order.deliveredAt()).isEqualTo(order.deliveryDueAt()).isBefore(NOW);
        assertThat(order.refundDeadlineAt())
            .isEqualTo(order.deliveredAt().plus(168, ChronoUnit.HOURS));
      } else {
        assertThat(order.refundDeadlineAt()).isNull();
      }
      if (order.purchaseConfirmedAt() != null) {
        assertThat(order.purchaseConfirmedAt()).isAfter(order.refundDeadlineAt()).isBefore(NOW);
      }
    }
  }

  @Test
  void createsPendingSnapshotAndReplaysBeforeGeneratingAnotherOrder() {
    CreateOrderCommand command = command(id(3), 1, 1_000, 23_000, "CREDIT_CARD", "card-a");
    OrderView created = orders.create(BUYER, "create-order", command);
    assertThat(created.productName()).isEqualTo("수납 상자");
    assertThat(created.sellerId()).isEqualTo(id(2));
    assertThat(created.grossAmount()).isEqualTo(23_000);
    assertThat(created.externalAmount()).isEqualTo(22_000);
    assertThat(created.createdAt()).isEqualTo(NOW.truncatedTo(ChronoUnit.MICROS));
    assertThat(created.status()).isEqualTo("PAYMENT_PENDING");
    assertThat(created.paymentId()).isNull();
    assertThat(created.paymentConfirmedAt()).isNull();
    assertThat(created.shippedAt()).isNull();
    assertThat(created.deliveryDueAt()).isNull();
    assertThat(orders.create(BUYER, "create-order", command)).isEqualTo(created);
    assertThat(orders.list(BUYER, 0, 100).totalElements()).isEqualTo(11);
    assertThatThrownBy(
            () ->
                orders.create(
                    BUYER, "create-order", command(id(3), 2, 0, 46_000, "EASY_PAY", "wallet")))
        .isInstanceOf(IdempotencyConflictException.class);
  }

  @Test
  void validatesServerPriceAndMileageWithoutConsumingFailedKey() {
    assertThatThrownBy(
            () -> orders.create(BUYER, "retry-key", command(id(3), 1, 0, 1, "CREDIT_CARD", "card")))
        .isInstanceOf(VersionConflictException.class);
    OrderView created =
        orders.create(BUYER, "retry-key", command(id(3), 1, 0, 23_000, "CREDIT_CARD", "card"));
    assertThat(created.couponAmount()).isZero();
    assertThat(created.eventId()).isNull();
    OrderView mileage =
        orders.create(BUYER, "full-mileage", command(id(33), 1, 10_000, 10_000, null, null));
    assertThat(mileage.externalAmount()).isZero();
    assertThatThrownBy(
            () ->
                orders.create(
                    BUYER,
                    "wrong-method",
                    command(id(33), 1, 10_000, 10_000, "EASY_PAY", "wallet")))
        .isInstanceOf(OrderStubException.class);
    assertThatThrownBy(
            () ->
                orders.create(
                    BUYER, "excess-mileage", command(id(33), 1, 10_001, 10_000, null, null)))
        .isInstanceOf(OrderStubException.class);
  }

  @Test
  void automaticallySelectsVariedVirtualAddressesAndKeepsOrderSnapshots() {
    var samples = orders.list(BUYER, 0, 100).items();
    assertThat(samples.stream().map(OrderView::address).distinct().count()).isGreaterThan(1);
    assertThat(samples)
        .allSatisfy(
            order -> {
              assertThat(order.address().recipientName()).startsWith("테스트 수령인 ");
              assertThat(order.address().addressLine1()).startsWith("가상시 테스트구 ");
              assertThat(order.address().phone()).startsWith("000-");
              assertThat(order.couponAmount()).isZero();
              assertThat(order.sellerCouponAmount()).isZero();
              assertThat(order.platformCouponAmount()).isZero();
              assertThat(order.eventId()).isNull();
            });
    UUID arbitraryBuyer = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    CreateOrderCommand command = command(id(3), 1, 0, 23_000, "CREDIT_CARD", "card");
    OrderView created = orders.create(arbitraryBuyer, "automatic-address", command);
    assertThat(created.address()).isNotNull();
    assertThat(orders.get(arbitraryBuyer, created.id()).address()).isEqualTo(created.address());
    assertThat(orders.create(arbitraryBuyer, "automatic-address", command).address())
        .isEqualTo(created.address());
  }

  @Test
  void isolatesBuyersAndHandlesPagesBeyondIntegerMultiplicationRange() {
    UUID foreign = orders.list(OTHER, 0, 1).items().getFirst().id();
    assertThatThrownBy(() -> orders.get(BUYER, foreign))
        .isInstanceOf(OrderStubException.class)
        .extracting(e -> ((OrderStubException) e).kind())
        .isEqualTo(OrderStubException.Kind.NOT_FOUND);
    assertThat(orders.list(BUYER, Integer.MAX_VALUE, 100).items()).isEmpty();
    assertThat(orders.list(BUYER, 1, 3).items())
        .containsExactlyElementsOf(orders.list(BUYER, 0, 100).items().subList(3, 6));
    assertThat(orders.list(OTHER, 0, 100).items())
        .allSatisfy(o -> assertThat(o.buyerId()).isEqualTo(OTHER));
  }

  @Test
  void concurrentIdenticalRequestsCreateOneSnapshot() throws Exception {
    CreateOrderCommand command = command(id(3), 1, 0, 23_000, "CREDIT_CARD", "card");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      List<java.util.concurrent.Future<OrderView>> results = new ArrayList<>();
      try {
        for (int i = 0; i < 12; i++) {
          results.add(
              executor.submit(
                  () -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                      throw new IllegalStateException("Start timeout");
                    }
                    return orders.create(BUYER, "concurrent", command);
                  }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      } finally {
        start.countDown();
      }
      OrderView first = results.getFirst().get(5, TimeUnit.SECONDS);
      for (var result : results) {
        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(first);
      }
    }
    assertThat(orders.list(BUYER, 0, 100).totalElements()).isEqualTo(11);
  }

  @Test
  void stubBeansAreAbsentUnlessExplicitlyEnabled() {
    new ApplicationContextRunner()
        .withUserConfiguration(OrderStubConfiguration.class, OrderStubController.class)
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(OrderStubService.class);
              assertThat(context).doesNotHaveBean(OrderStubController.class);
              assertThat(context).doesNotHaveBean(OrderInventory.class);
            });
  }

  @Test
  void enabledStubWiresInventoryAndOrderBeansWithoutDatabase() {
    new ApplicationContextRunner()
        .withUserConfiguration(OrderStubConfiguration.class, OrderStubController.class)
        .withBean(Clock.class, () -> CLOCK)
        .withPropertyValues("app.order-stub.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(OrderStubService.class);
              assertThat(context).hasSingleBean(OrderStubController.class);
              assertThat(context).hasSingleBean(OrderInventory.class);
            });
  }

  @Test
  void readsCurrentProductForNewOrdersAndReplaysTheOriginalSnapshot() {
    AtomicInteger reads = new AtomicInteger();
    AtomicReference<Product> current =
        new AtomicReference<>(
            Product.register(id(3), id(2), "수납 상자", 23_000, ProductStatus.ON_SALE, NOW));
    OrderStubService subject =
        configuration.orderStubService(
            CLOCK,
            "67",
            productId -> {
              reads.incrementAndGet();
              assertThat(productId).isEqualTo(id(3));
              return current.get();
            });
    assertThat(reads).hasValue(0);

    CreateOrderCommand original = command(id(3), 1, 0, 23_000, "CREDIT_CARD", "card");
    OrderView first = subject.create(BUYER, "snapshot", original);
    current.set(Product.register(id(3), id(2), "변경 상품", 25_000, ProductStatus.ON_SALE, NOW));

    assertThat(subject.create(BUYER, "snapshot", original)).isEqualTo(first);
    assertThat(subject.get(BUYER, first.id()).productName()).isEqualTo("수납 상자");
    assertThat(subject.get(BUYER, first.id()).grossAmount()).isEqualTo(23_000);
    assertThat(reads).hasValue(1);
    assertThatThrownBy(
            () ->
                subject.create(
                    BUYER, "snapshot", command(id(3), 1, 0, 25_000, "CREDIT_CARD", "card")))
        .isInstanceOf(IdempotencyConflictException.class);
    assertThat(reads).hasValue(1);

    OrderView second =
        subject.create(BUYER, "new-price", command(id(3), 1, 0, 25_000, "CREDIT_CARD", "card"));
    assertThat(second.productName()).isEqualTo("변경 상품");
    assertThat(second.grossAmount()).isEqualTo(25_000);
    assertThat(reads).hasValue(2);
  }

  @Test
  void validatesTheVersionReturnedByInventoryInsteadOfAssumingZero() {
    Product product =
        spy(Product.register(id(3), id(2), "수납 상자", 23_000, ProductStatus.ON_SALE, NOW));
    doReturn(7L).when(product).getVersion();
    OrderStubService subject = configuration.orderStubService(CLOCK, "67", productId -> product);

    assertThatThrownBy(
            () ->
                subject.create(
                    BUYER, "version", command(id(3), 1, 0, 23_000, "CREDIT_CARD", "card")))
        .isInstanceOf(VersionConflictException.class);
    assertThat(subject.list(BUYER, 0, 100).totalElements()).isEqualTo(10);

    OrderView created =
        subject.create(
            BUYER,
            "version",
            new CreateOrderCommand(id(3), 1, 0, 7, 23_000, "CREDIT_CARD", "card"));
    assertThat(created.grossAmount()).isEqualTo(23_000);
    assertThat(subject.list(BUYER, 0, 100).totalElements()).isEqualTo(11);
  }

  @ParameterizedTest
  @EnumSource(
      value = ProductStatus.class,
      names = {"DRAFT", "STOPPED", "DELETED"})
  void rejectsNonSaleProductsWithoutSavingOrConsumingTheKey(ProductStatus status) {
    Product product =
        Product.register(
            id(3),
            id(2),
            "수납 상자",
            23_000,
            status == ProductStatus.DELETED ? ProductStatus.ON_SALE : status,
            NOW);
    if (status == ProductStatus.DELETED) {
      product.delete(0, NOW);
    }
    AtomicReference<Product> current = new AtomicReference<>(product);
    OrderStubService subject =
        configuration.orderStubService(CLOCK, "67", productId -> current.get());
    CreateOrderCommand command = command(id(3), 1, 0, 23_000, "CREDIT_CARD", "card");

    assertThatThrownBy(() -> subject.create(BUYER, "saleability", command))
        .isInstanceOf(ProductNotFoundException.class);
    assertThat(subject.list(BUYER, 0, 100).totalElements()).isEqualTo(10);

    current.set(Product.register(id(3), id(2), "수납 상자", 23_000, ProductStatus.ON_SALE, NOW));
    assertThat(subject.create(BUYER, "saleability", command).status()).isEqualTo("PAYMENT_PENDING");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 11})
  void rejectsInvalidQuantityBeforeAccessingInventory(int quantity) {
    OrderStubService subject =
        configuration.orderStubService(
            CLOCK,
            "67",
            productId -> {
              throw new AssertionError("Inventory must not be accessed");
            });

    assertThatThrownBy(
            () ->
                subject.create(
                    BUYER, "quantity", command(id(3), quantity, 0, 23_000, "CREDIT_CARD", "card")))
        .isInstanceOf(OrderStubException.class);
    assertThat(subject.list(BUYER, 0, 100).totalElements()).isEqualTo(10);
  }

  private static CreateOrderCommand command(
      UUID product, int quantity, long mileage, long expected, String method, String account) {
    return new CreateOrderCommand(product, quantity, mileage, 0, expected, method, account);
  }

  private static UUID id(int suffix) {
    return UUID.fromString("00000000-0000-4000-8000-" + String.format("%012d", suffix));
  }
}
