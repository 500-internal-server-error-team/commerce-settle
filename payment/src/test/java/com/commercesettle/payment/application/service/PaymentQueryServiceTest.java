package com.commercesettle.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.payment.application.dto.PaymentPageView;
import com.commercesettle.payment.application.dto.PaymentView;
import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload.ExternalMethod;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import com.commercesettle.payment.application.port.WaitingInboxEvent;
import com.commercesettle.payment.domain.exception.PaymentNotFoundException;
import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.model.PaymentAmounts;
import com.commercesettle.payment.domain.repository.PaymentRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentQueryServiceTest {
  private static final UUID BUYER = new UUID(0, 2);
  private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

  private final FakePayments payments = new FakePayments();
  private final FakeInbox inbox = new FakeInbox();
  private final PaymentQueryService service = new PaymentQueryService(payments, inbox);

  @Test
  @DisplayName("외부 결제액이 있으면 결제수단을 결제를 처음 만든 요청 원문에서 읽는다")
  void readsMethodFromOriginalRequest() {
    Payment card = payments.save(payment(BUYER, new PaymentAmounts(10_000, 0, 0, 2_000)));
    inbox.originals.put(card.getId(), ExternalMethod.EASY_PAY);

    PaymentView view = service.get(BUYER, card.getId());

    assertThat(view.method()).isEqualTo("EASY_PAY");
    assertThat(view.providerId()).isNull();
    assertThat(view.externalAmount()).isEqualTo(8_000);
  }

  @Test
  @DisplayName("전액 마일리지 결제는 결제수단을 null로 두고 원문을 읽지 않는다")
  void leavesMethodNullForMileageOnly() {
    Payment mileage = payments.save(payment(BUYER, new PaymentAmounts(10_000, 0, 0, 10_000)));

    assertThat(service.get(BUYER, mileage.getId()).method()).isNull();
    assertThat(inbox.lookups).isZero();
  }

  @Test
  @DisplayName("다른 구매자의 결제는 없는 결제와 같이 거절한다")
  void rejectsOthersPayment() {
    Payment others = payments.save(payment(new UUID(0, 9), new PaymentAmounts(10_000, 0, 0, 0)));

    assertThatThrownBy(() -> service.get(BUYER, others.getId()))
        .isInstanceOf(PaymentNotFoundException.class);
    assertThatThrownBy(() -> service.get(BUYER, UUID.randomUUID()))
        .isInstanceOf(PaymentNotFoundException.class);
  }

  @Test
  @DisplayName("시작 위치가 int 범위를 넘는 페이지는 행을 읽지 않고 전체 건수만 돌려준다")
  void returnsEmptyPageBeyondOffsetRange() {
    payments.save(payment(BUYER, new PaymentAmounts(10_000, 0, 0, 0)));

    PaymentPageView page = service.list(BUYER, null, Integer.MAX_VALUE, 100);

    assertThat(page.items()).isEmpty();
    assertThat(page.totalElements()).isEqualTo(1);
    assertThat(page.totalPages()).isEqualTo(1);
    assertThat(payments.pageReads).isZero();
  }

  private static Payment payment(UUID buyerId, PaymentAmounts amounts) {
    return Payment.create(
        UUID.randomUUID(),
        0,
        UUID.randomUUID(),
        buyerId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        amounts,
        "v1",
        NOW);
  }

  private static final class FakePayments implements PaymentRepository {
    private final Map<UUID, Payment> byId = new HashMap<>();
    private int pageReads;

    @Override
    public Payment save(Payment payment) {
      byId.put(payment.getId(), payment);
      return payment;
    }

    @Override
    public Optional<Payment> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Payment> findByOrderId(UUID orderId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Payment> findByIdAndBuyerId(UUID id, UUID buyerId) {
      return findById(id).filter(p -> p.getBuyerId().equals(buyerId));
    }

    @Override
    public List<Payment> findByBuyer(UUID buyerId, UUID orderId, int page, int size) {
      pageReads++;
      return new ArrayList<>(byId.values());
    }

    @Override
    public long countByBuyer(UUID buyerId, UUID orderId) {
      return byId.values().stream().filter(p -> p.getBuyerId().equals(buyerId)).count();
    }
  }

  private static final class FakeInbox implements InboxStore {
    private final Map<UUID, ExternalMethod> originals = new HashMap<>();
    private int lookups;

    @Override
    public Optional<OrderPaymentRequestedPayload> findOriginalRequest(UUID paymentId) {
      lookups++;
      ExternalMethod method = originals.get(paymentId);
      if (method == null) {
        return Optional.empty();
      }
      return Optional.of(
          new OrderPaymentRequestedPayload(
              UUID.randomUUID(),
              BUYER,
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              0,
              OrderPaymentRequestedPayload.Status.PAYMENT_PENDING,
              "v1",
              OffsetDateTime.parse("2026-11-02T09:10:00+09:00"),
              method,
              "ref",
              10_000,
              0,
              10_000,
              2_000,
              8_000,
              0,
              0,
              10_000));
    }

    @Override
    public void saveWaiting(EventEnvelope<OrderPaymentRequestedPayload> event, Source source) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<WaitingInboxEvent> claimNextWaiting(Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<WaitingInboxEvent> claimWaitingEvent(UUID eventId, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void markApplied(UUID inboxId, UUID resultRef, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void markReviewRequired(UUID inboxId, Instant now) {
      throw new UnsupportedOperationException();
    }
  }
}
