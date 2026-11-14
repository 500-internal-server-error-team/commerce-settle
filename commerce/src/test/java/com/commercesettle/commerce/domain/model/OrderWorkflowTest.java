package com.commercesettle.commerce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.domain.exception.OrderStateException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderWorkflowTest {
  private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

  @Test
  @DisplayName("시연 기한은 주문별 스냅샷이며 배송 정각·환불 마감 경계를 보존한다")
  void preservesDemoSnapshotsAndRealDeadlineBoundaries() {
    var first = order(List.of());
    first.configureDemo(UUID.randomUUID(), 5, 10);
    paid(first);
    first.ship(NOW);
    var second = order(List.of());
    second.configureDemo(UUID.randomUUID(), 2, 3);
    paid(second);
    second.ship(NOW);
    assertThat(first.getDeliveryDueAt()).isEqualTo(NOW.plusSeconds(5));
    assertThat(second.getDeliveryDueAt()).isEqualTo(NOW.plusSeconds(2));
    assertThat(first.deliverIfDue(NOW.plusSeconds(5).minusNanos(1))).isFalse();
    assertThat(first.deliverIfDue(NOW.plusSeconds(5))).isTrue();
    assertThat(first.getRefundDeadlineAt()).isEqualTo(NOW.plusSeconds(15));
    assertThat(first.confirmPurchaseIfDue(NOW.plusSeconds(15))).isFalse();
    assertThat(first.confirmPurchaseIfDue(NOW.plusSeconds(15).plusNanos(1000))).isTrue();
    assertThatThrownBy(() -> first.configureDemo(UUID.randomUUID(), 2, 3))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  @DisplayName("발송 당일·주말·보관한 공휴일을 제외하고 도착과 환불 마감을 고정한다")
  void preservesDeliveryCalendarAndStrictConfirmationBoundary() {
    var o = order(List.of(LocalDate.of(2026, 11, 2)));
    paid(o);
    o.ship(NOW);

    assertThat(o.getDeliveryDueAt()).isEqualTo(Instant.parse("2026-11-04T10:00:00Z"));
    assertThat(o.deliverIfDue(o.getDeliveryDueAt().minusNanos(1))).isFalse();
    assertThat(o.deliverIfDue(o.getDeliveryDueAt().plusSeconds(20))).isTrue();
    assertThat(o.getDeliveredAt()).isEqualTo(o.getDeliveryDueAt());
    assertThat(o.getRefundDeadlineAt()).isEqualTo(Instant.parse("2026-11-11T10:00:00Z"));
    assertThat(o.confirmPurchaseIfDue(o.getRefundDeadlineAt())).isFalse();
    assertThat(o.confirmPurchaseIfDue(o.getRefundDeadlineAt().plusSeconds(1))).isTrue();
    assertThat(o.confirmPurchaseIfDue(o.getRefundDeadlineAt().plusSeconds(2))).isFalse();
  }

  @Test
  @DisplayName("배송 작업이 늦어도 마감 뒤 반품을 접수하지 않는다")
  void rejectsLateRefundBeforeDelayedDeliveryJob() {
    var o = order(List.of());
    paid(o);
    o.ship(NOW);
    assertThatThrownBy(() -> o.beginRefund(false, o.getDeliveryDueAt().plusSeconds(168 * 3600 + 1)))
        .isInstanceOf(OrderStateException.class);
    assertThat(o.getStatus()).isEqualTo(Order.Status.PAID);
  }

  @Test
  @DisplayName("마감 정각 접수는 허용하고 진행 중 반품은 자동 구매확정을 막는다")
  void acceptsRefundAtDeadlineAndBlocksConfirmation() {
    var o = order(List.of());
    paid(o);
    o.ship(NOW);
    o.beginRefund(false, o.getDeliveryDueAt().plusSeconds(168 * 3600));
    assertThat(o.getStatus()).isEqualTo(Order.Status.REFUND_PENDING);
    assertThat(o.confirmPurchaseIfDue(o.getRefundDeadlineAt().plusSeconds(1))).isFalse();
    o.resumeAfterRefund(o.getRefundDeadlineAt().plusSeconds(2));
    assertThat(o.confirmPurchaseIfDue(o.getRefundDeadlineAt().plusSeconds(2))).isTrue();
  }

  @Test
  @DisplayName("확정 거절 뒤 새 회차를 만들며 이전 회차의 늦은 거절은 무시한다")
  void ignoresOldCycleAfterRetry() {
    var o = order(List.of());
    var payment = UUID.randomUUID();
    var old = o.getPaymentCycleId();
    assertThat(o.applyPayment(payment, old, Order.Status.DECLINED, null, NOW)).isTrue();
    o.retryPayment(UUID.randomUUID(), NOW.plusSeconds(1));
    assertThat(o.applyPayment(payment, old, Order.Status.DECLINED, null, NOW.plusSeconds(2)))
        .isFalse();
    assertThat(o.getStatus()).isEqualTo(Order.Status.PAYMENT_PENDING);
    assertThatThrownBy(() -> o.retryPayment(UUID.randomUUID(), NOW.plusSeconds(3)))
        .isInstanceOf(OrderStateException.class);
  }

  @Test
  @DisplayName("취소 접수 뒤 확정 결과가 먼저 도착하면 결제 완료로 반영한다")
  void acceptsConfirmedPaymentDuringCancellationRace() {
    var o = order(List.of());
    o.requestCancellation(NOW);
    assertThat(o.getStatus()).isEqualTo(Order.Status.CANCEL_PENDING);
    paid(o);
    assertThat(o.getStatus()).isEqualTo(Order.Status.PAID);
    assertThatThrownBy(
            () ->
                o.applyPayment(
                    o.getPaymentId(), o.getPaymentCycleId(), Order.Status.CANCELLED, null, NOW))
        .isInstanceOf(OrderStateException.class);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("승인 전 반품은 철회·거절로만 재개하고 승인 뒤에는 철회하지 않는다")
  void protectsRefundDecisionAndFailureLifecycle(boolean approve) {
    var o = order(List.of());
    paid(o);
    o.ship(NOW);
    o.beginRefund(false, NOW);
    var r =
        OrderRefundRequest.create(
            UUID.randomUUID(), o, OrderRefundRequest.Kind.RETURN, "시험 반품", NOW);
    r.decide(approve, NOW);
    assertThatThrownBy(() -> r.withdraw(NOW)).isInstanceOf(OrderStateException.class);
    if (approve) {
      r.linkRefund(UUID.randomUUID(), NOW);
      r.fail(NOW);
      assertThat(r.getStatus()).isEqualTo(OrderRefundRequest.Status.FAILED_REVIEW);
      assertThat(o.getStatus()).isEqualTo(Order.Status.REFUND_PENDING);
      assertThat(r.receive(true, NOW)).isTrue();
      assertThat(r.receive(true, NOW)).isFalse();
      assertThatThrownBy(() -> r.receive(false, NOW)).isInstanceOf(OrderStateException.class);
      assertThat(r.getReceiptResalable()).isTrue();
      assertThatThrownBy(() -> r.close("종결", "근거", NOW)).isInstanceOf(OrderStateException.class);
    } else assertThat(r.getStatus()).isEqualTo(OrderRefundRequest.Status.REJECTED);
  }

  @Test
  @DisplayName("취소 대기 중 거절 결과가 와도 재시도를 열지 않고 승인 차단 확정을 기다린다")
  void keepsCancellationIntentAcrossDeclinedResult() {
    var o = order(List.of());
    var payment = UUID.randomUUID();
    var cycle = o.getPaymentCycleId();
    o.requestCancellation(NOW);
    assertThat(o.applyPayment(payment, cycle, Order.Status.DECLINED, null, NOW)).isFalse();
    assertThat(o.getStatus()).isEqualTo(Order.Status.CANCEL_PENDING);
    assertThatThrownBy(() -> o.retryPayment(UUID.randomUUID(), NOW))
        .isInstanceOf(OrderStateException.class);
    assertThat(o.applyPayment(payment, cycle, Order.Status.CANCELLED, null, NOW)).isTrue();
    assertThat(o.getStatus()).isEqualTo(Order.Status.CANCELLED);
  }

  private static void paid(Order o) {
    o.applyPayment(UUID.randomUUID(), o.getPaymentCycleId(), Order.Status.PAID, NOW, NOW);
  }

  private static Order order(List<LocalDate> holidays) {
    return Order.pendingRegular(
        UUID.fromString("00000000-0000-4000-8000-000000000001"),
        UUID.randomUUID(),
        Product.register(
            UUID.randomUUID(), UUID.randomUUID(), "시험 상품", 10000, ProductStatus.ON_SALE, NOW),
        1,
        0,
        Order.ExternalMethod.CREDIT_CARD,
        "test-card",
        17,
        "test-calendar",
        holidays,
        "test-policy",
        NOW);
  }
}
