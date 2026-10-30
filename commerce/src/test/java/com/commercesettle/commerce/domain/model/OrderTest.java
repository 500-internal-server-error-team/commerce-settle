package com.commercesettle.commerce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OrderTest {
  private static final Instant NOW = Instant.parse("2026-10-30T10:00:00.123456789Z");

  @Test
  @DisplayName("상품 사본과 혼합 결제 금액을 고정하고 최초 결제 회차와 10분 대기 기한을 만든다")
  void capturesProductAndMixedPayment() {
    Product product = product(5_000);
    Order order = order(product, 2, 2_000, Order.ExternalMethod.CREDIT_CARD, "test-card");

    product.change("새 이름", 8_000, ProductStatus.STOPPED, 0, NOW.plusSeconds(1));

    assertThat(order.getProductName()).isEqualTo("시험 상품");
    assertThat(order.getUnitPrice()).isEqualTo(5_000);
    assertThat(order.getAmounts()).isEqualTo(new OrderAmounts(10_000, 0, 0, 2_000));
    assertThat(order.getAmounts().externalAmount()).isEqualTo(8_000);
    assertThat(order.getStatus()).isEqualTo(Order.Status.PAYMENT_PENDING);
    assertThat(order.getFulfillmentStatus()).isEqualTo(Order.FulfillmentStatus.UNSHIPPED);
    assertThat(order.getPaymentCycleId()).isNotNull().isNotEqualTo(order.getId());
    assertThat(order.getPaymentWaitUntil()).isEqualTo(Instant.parse("2026-10-30T10:10:00.123456Z"));
  }

  @Test
  @DisplayName("마일리지 전액 결제에는 외부 수단과 시험 계정이 없다")
  void permitsFullMileageWithoutExternalMethod() {
    Order order = order(product(10_000), 1, 10_000, null, null);

    assertThat(order.getAmounts().externalAmount()).isZero();
    assertThat(order.getExternalMethod()).isNull();
    assertThat(order.getMethodAccountRef()).isNull();
  }

  @ParameterizedTest
  @CsvSource({"0,1000,0", "11,1000,0", "2,10000000,0", "1,1000,-1", "1,1000,1001"})
  @DisplayName("일반 주문 수량·상품대금·마일리지 범위를 벗어나면 주문을 만들지 않는다")
  void rejectsInvalidQuantityOrAmounts(int quantity, long price, long mileage) {
    assertThatThrownBy(
            () ->
                order(
                    product(price),
                    quantity,
                    mileage,
                    Order.ExternalMethod.CREDIT_CARD,
                    "test-card"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("외부 결제액이 있으면 수단과 유효한 시험 계정이 모두 필요하다")
  void requiresExternalTermsForExternalAmount() {
    Product product = product(1_000);

    assertThatThrownBy(() -> order(product, 1, 0, null, "test-card"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> order(product, 1, 0, Order.ExternalMethod.EASY_PAY, " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> order(product, 1, 0, Order.ExternalMethod.EASY_PAY, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("마일리지 전액 결제에 외부 수단이나 계정을 붙이지 않는다")
  void rejectsExternalTermsForFullMileage() {
    Product product = product(1_000);

    assertThatThrownBy(() -> order(product, 1, 1_000, Order.ExternalMethod.CREDIT_CARD, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> order(product, 1, 1_000, null, "test-card"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(longs = {1, 10_000_000})
  @DisplayName("최소·최대 상품대금 경계의 주문을 만든다")
  void acceptsMoneyBoundaries(long price) {
    Order order = order(product(price), 1, 0, Order.ExternalMethod.DEBIT_CARD, "test-card");

    assertThat(order.getAmounts().totalAmount()).isEqualTo(price);
  }

  @Test
  @DisplayName("쿠폰 부담은 하나만 적용하고 마일리지와 함께 쓰지 않는다")
  void rejectsOverlappingDiscountFunding() {
    assertThatThrownBy(() -> new OrderAmounts(10_000, 1_000, 1_000, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OrderAmounts(10_000, 0, 1_000, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OrderAmounts(10_000, 10_000, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @CsvSource({"00000000-0000-4000-8000-000000000001,2", "00000000-0000-4000-8000-000000000004,3"})
  @DisplayName("고정 seed·주문 ID로 배송 소요일을 정하고 공휴일 입력의 이후 변경을 차단한다")
  void capturesDeterministicDeliveryAndIndependentCalendar(UUID orderId, int expectedDays) {
    List<LocalDate> holidays = new ArrayList<>(List.of(LocalDate.of(2026, 12, 25)));
    OrderDelivery delivery = OrderDelivery.capture(orderId, 17, "holidays-v1", holidays);

    holidays.clear();

    assertThat(delivery.getDeliveryBusinessDays()).isEqualTo(expectedDays);
    assertThat(delivery.getDeliveryCalendar().holidays())
        .containsExactly(LocalDate.of(2026, 12, 25));
    assertThatThrownBy(() -> delivery.getDeliveryCalendar().holidays().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("입력과 반환 배열을 바꾸어도 주문 주소 암호문 사본은 바뀌지 않는다")
  void protectsAddressSnapshotFromArrayMutation() {
    SealedShippingAddress sealed =
        new SealedShippingAddress(new byte[] {1}, new byte[] {2}, new byte[] {3});
    OrderAddress address = OrderAddress.snapshot(UUID.randomUUID(), UUID.randomUUID(), sealed, NOW);

    sealed.recipient()[0] = 9;
    address.getSealed().phone()[0] = 9;

    assertThat(address.getSealed().recipient()).containsExactly((byte) 1);
    assertThat(address.getSealed().phone()).containsExactly((byte) 2);
  }

  private static Order order(
      Product product, int quantity, long mileage, Order.ExternalMethod method, String account) {
    return Order.pendingRegular(
        UUID.randomUUID(),
        product,
        quantity,
        mileage,
        method,
        account,
        17,
        "weekdays-v1",
        List.of(),
        "delivery-daily-v1",
        NOW);
  }

  private static Product product(long price) {
    return Product.register(
        UUID.randomUUID(), UUID.randomUUID(), "시험 상품", price, ProductStatus.ON_SALE, NOW);
  }
}
