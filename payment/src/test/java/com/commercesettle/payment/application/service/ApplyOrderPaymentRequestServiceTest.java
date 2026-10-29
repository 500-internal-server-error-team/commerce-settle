package com.commercesettle.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload.ExternalMethod;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload.Status;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import com.commercesettle.payment.application.port.WaitingInboxEvent;
import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.model.PaymentJob;
import com.commercesettle.payment.domain.model.PaymentJobOperation;
import com.commercesettle.payment.domain.model.PaymentStatus;
import com.commercesettle.payment.domain.repository.PaymentJobRepository;
import com.commercesettle.payment.domain.repository.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApplyOrderPaymentRequestServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");

  private final FakeInbox inbox = new FakeInbox();
  private final FakePayments payments = new FakePayments();
  private final FakeJobs jobs = new FakeJobs();
  private final ApplyOrderPaymentRequestService service =
      new ApplyOrderPaymentRequestService(inbox, payments, jobs, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  @DisplayName("처리할 WAITING이 없으면 아무것도 바꾸지 않는다")
  void returnsFalseWhenNothingIsWaiting() {
    assertThat(service.processNext()).isFalse();
    assertThat(payments.all()).isEmpty();
  }

  @Test
  @DisplayName("카드 결제 요청은 PENDING 결제와 승인 작업을 만들고 결제 ID로 완료한다")
  void createsPaymentAndApprovalJobForCardPayment() {
    UUID inboxId = inbox.add(card(UUID.randomUUID(), UUID.randomUUID()));

    assertThat(service.processNext()).isTrue();

    Payment payment = payments.all().get(0);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(payment.getCreatedAt()).isEqualTo(NOW);
    assertThat(jobs.saved)
        .singleElement()
        .satisfies(
            job -> {
              assertThat(job.getPaymentId()).isEqualTo(payment.getId());
              assertThat(job.getOperation()).isEqualTo(PaymentJobOperation.APPROVE);
            });
    assertThat(inbox.applied).containsEntry(inboxId, payment.getId());
  }

  @Test
  @DisplayName("마일리지 전액 결제는 승인 작업 없이 결제만 만든다")
  void createsPaymentWithoutJobWhenExternalAmountIsZero() {
    UUID inboxId =
        inbox.add(
            payload(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Status.PAYMENT_PENDING,
                10_000,
                0,
                0,
                10_000,
                10_000,
                10_000,
                0,
                0,
                null,
                null));

    service.processNext();

    assertThat(payments.all()).hasSize(1);
    assertThat(jobs.saved).isEmpty();
    assertThat(inbox.applied).containsKey(inboxId);
  }

  @Test
  @DisplayName("커머스가 보낸 금액이 재계산과 다르면 결제를 만들지 않고 확인 대상으로 남긴다")
  void marksReviewWhenCommerceAmountsDiffer() {
    // 플랫폼 쿠폰 3,000원인데 외부 결제액을 23,000원(쿠폰 미차감)으로 보낸 경우
    UUID inboxId =
        inbox.add(
            payload(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Status.PAYMENT_PENDING,
                23_000,
                0,
                3_000,
                0,
                23_000,
                23_000,
                23_000,
                3_000,
                ExternalMethod.CREDIT_CARD,
                "card-ref"));

    service.processNext();

    assertThat(payments.all()).isEmpty();
    assertThat(inbox.reviewRequired).containsExactly(inboxId);
  }

  @Test
  @DisplayName("쿠폰과 마일리지를 함께 쓴 요청은 금액 규칙 위반으로 확인 대상으로 남긴다")
  void marksReviewWhenAmountRuleIsViolated() {
    UUID inboxId =
        inbox.add(
            payload(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Status.PAYMENT_PENDING,
                10_000,
                0,
                1_000,
                500,
                10_000,
                9_000,
                8_500,
                1_000,
                ExternalMethod.CREDIT_CARD,
                "card-ref"));

    service.processNext();

    assertThat(payments.all()).isEmpty();
    assertThat(inbox.reviewRequired).containsExactly(inboxId);
  }

  @Test
  @DisplayName("결제 대기 상태가 아닌 주문의 요청은 확인 대상으로 남긴다")
  void marksReviewWhenOrderIsNotPaymentPending() {
    UUID inboxId =
        inbox.add(
            payload(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Status.CANCELLED,
                10_000,
                0,
                0,
                0,
                10_000,
                10_000,
                10_000,
                0,
                ExternalMethod.CREDIT_CARD,
                "card-ref"));

    service.processNext();

    assertThat(payments.all()).isEmpty();
    assertThat(inbox.reviewRequired).containsExactly(inboxId);
  }

  @Test
  @DisplayName("같은 주문의 같은 요청이 다시 오면 기존 결제로 완료하고 새로 만들지 않는다")
  void reusesExistingPaymentForSameRequest() {
    UUID orderId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    inbox.add(card(orderId, cycleId));
    service.processNext();
    UUID existingId = payments.all().get(0).getId();
    UUID secondInboxId = inbox.add(card(orderId, cycleId));

    service.processNext();

    assertThat(payments.all()).hasSize(1);
    assertThat(jobs.saved).hasSize(1);
    assertThat(inbox.applied).containsEntry(secondInboxId, existingId);
  }

  @Test
  @DisplayName("같은 주문에 다른 회차의 요청이 오면 기존 결제를 유지하고 확인 대상으로 남긴다")
  void marksReviewForDifferentRequestOfSameOrder() {
    UUID orderId = UUID.randomUUID();
    inbox.add(card(orderId, UUID.randomUUID()));
    service.processNext();
    UUID secondInboxId = inbox.add(card(orderId, UUID.randomUUID()));

    service.processNext();

    assertThat(payments.all()).hasSize(1);
    assertThat(inbox.reviewRequired).containsExactly(secondInboxId);
  }

  private static OrderPaymentRequestedPayload card(UUID orderId, UUID cycleId) {
    return payload(
        orderId,
        cycleId,
        Status.PAYMENT_PENDING,
        10_000,
        0,
        0,
        0,
        10_000,
        10_000,
        10_000,
        0,
        ExternalMethod.CREDIT_CARD,
        "card-ref");
  }

  private static OrderPaymentRequestedPayload payload(
      UUID orderId,
      UUID cycleId,
      Status status,
      long gross,
      long sellerCoupon,
      long platformCoupon,
      long mileage,
      long base,
      long total,
      long external,
      long coupon,
      ExternalMethod method,
      String methodAccountRef) {
    return new OrderPaymentRequestedPayload(
        orderId,
        new UUID(0, 2),
        new UUID(0, 3),
        cycleId,
        new UUID(0, 5),
        0,
        status,
        "v1",
        OffsetDateTime.parse("2026-10-29T09:10:00+09:00"),
        method,
        methodAccountRef,
        gross,
        coupon,
        total,
        mileage,
        external,
        sellerCoupon,
        platformCoupon,
        base);
  }

  private static final class FakeInbox implements InboxStore {
    private final Queue<WaitingInboxEvent> waiting = new ArrayDeque<>();
    private final Map<UUID, UUID> applied = new HashMap<>();
    private final List<UUID> reviewRequired = new ArrayList<>();

    UUID add(OrderPaymentRequestedPayload payload) {
      UUID inboxId = UUID.randomUUID();
      waiting.add(
          new WaitingInboxEvent(
              inboxId,
              new EventEnvelope<>(
                  UUID.randomUUID(),
                  "OrderPaymentRequested",
                  1,
                  OffsetDateTime.parse("2026-10-29T09:00:00+09:00"),
                  payload.orderId(),
                  0,
                  payload)));
      return inboxId;
    }

    @Override
    public void saveWaiting(EventEnvelope<OrderPaymentRequestedPayload> event, Source source) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<WaitingInboxEvent> claimNextWaiting(Instant now) {
      return Optional.ofNullable(waiting.poll());
    }

    @Override
    public void markApplied(UUID inboxId, UUID resultRef, Instant now) {
      applied.put(inboxId, resultRef);
    }

    @Override
    public void markReviewRequired(UUID inboxId, Instant now) {
      reviewRequired.add(inboxId);
    }
  }

  private static final class FakePayments implements PaymentRepository {
    private final Map<UUID, Payment> byId = new HashMap<>();

    List<Payment> all() {
      return new ArrayList<>(byId.values());
    }

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
      return byId.values().stream().filter(p -> p.getOrderId().equals(orderId)).findFirst();
    }
  }

  private static final class FakeJobs implements PaymentJobRepository {
    private final List<PaymentJob> saved = new ArrayList<>();

    @Override
    public PaymentJob save(PaymentJob job) {
      saved.add(job);
      return job;
    }

    @Override
    public Optional<PaymentJob> findByOperationAndBusinessKey(
        PaymentJobOperation operation, UUID businessKey) {
      return saved.stream()
          .filter(j -> j.getOperation() == operation && j.getBusinessKey().equals(businessKey))
          .findFirst();
    }
  }
}
