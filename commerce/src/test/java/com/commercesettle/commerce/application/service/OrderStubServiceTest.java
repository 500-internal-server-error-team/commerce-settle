package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.application.dto.CreateOrderCommand;
import com.commercesettle.commerce.application.dto.OrderView;
import com.commercesettle.commerce.application.exception.IdempotencyConflictException;
import com.commercesettle.commerce.application.exception.OrderStubException;
import com.commercesettle.commerce.domain.exception.VersionConflictException;
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
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OrderStubServiceTest {
  private static final UUID BUYER = id(1);
  private static final UUID OTHER = id(11);
  private static final Instant NOW = Instant.parse("2026-01-20T01:00:00.123456789Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private final OrderStubService orders = new OrderStubService(CLOCK, "67");

  @Test
  void samplesAreStableVariedAndFollowAmountAndTimelinePolicies() throws Exception {
    List<OrderView> samples = orders.list(BUYER, 0, 100).items();
    assertThat(samples)
        .hasSize(10)
        .isEqualTo(new OrderStubService(CLOCK, "67").list(BUYER, 0, 100).items());
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
    try (var executor = Executors.newFixedThreadPool(2)) {
      List<Callable<OrderView>> calls = new ArrayList<>();
      for (int i = 0; i < 12; i++) {
        calls.add(() -> orders.create(BUYER, "concurrent", command));
      }
      var results = executor.invokeAll(calls);
      for (var result : results) {
        assertThat(result.get()).isEqualTo(results.getFirst().get());
      }
    }
    assertThat(orders.list(BUYER, 0, 100).totalElements()).isEqualTo(11);
  }

  @Test
  void stubBeansAreAbsentUnlessExplicitlyEnabled() {
    new ApplicationContextRunner()
        .withUserConfiguration(OrderStubService.class, OrderStubController.class)
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(OrderStubService.class);
              assertThat(context).doesNotHaveBean(OrderStubController.class);
            });
  }

  private static CreateOrderCommand command(
      UUID product, int quantity, long mileage, long expected, String method, String account) {
    return new CreateOrderCommand(product, quantity, mileage, 0, expected, method, account);
  }

  private static UUID id(int suffix) {
    return UUID.fromString("00000000-0000-4000-8000-" + String.format("%012d", suffix));
  }
}
